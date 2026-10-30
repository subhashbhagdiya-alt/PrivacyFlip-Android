package com.privacyflip.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small bridge to the user's explicitly-authorized Shizuku service.
 * Callbacks are delivered on the main thread; a shell-side command can fail
 * on particular Android releases or manufacturer builds and that result is returned.
 */
object ShizukuAutomation {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isBinderRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun readState(context: Context, option: String, callback: (String) -> Unit) {
        val appContext = context.applicationContext
        if (!isBinderRunning()) { callback("UNKNOWN|Shizuku is not running."); return }
        if (!hasPermission()) { callback("UNKNOWN|Privacy Flip Shizuku permission is missing."); return }
        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext, FlipShellUserService::class.java)
        ).daemon(false).tag("privacy_flip_state_" + option).version(2)
            .processNameSuffix("privacy_flip_state_" + option)
        val completed = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || completed.get()) { finish("UNKNOWN|State service did not connect."); return }
                Thread {
                    val answer = runCatching {
                        IPrivacyFlipShell.Stub.asInterface(service).getOptionState(option)
                    }.getOrElse { "UNKNOWN|" + (it.message ?: "State query failed.") }
                    finish(answer)
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                finish("UNKNOWN|State service disconnected.")
            }
            private fun finish(answer: String) {
                if (!completed.compareAndSet(false, true)) return
                mainHandler.post {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    callback(answer)
                }
            }
        }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { if (completed.compareAndSet(false, true)) callback("UNKNOWN|" + (it.message ?: "Could not bind state service.")) }
    }

    fun apply(context: Context, option: String, enabled: Boolean, callback: (String) -> Unit) {
        val appContext = context.applicationContext
        if (!isBinderRunning()) {
            callback("ERROR: Shizuku is not running. Start it, then retry.")
            return
        }
        if (!hasPermission()) {
            callback("ERROR: Privacy Flip has not been granted Shizuku permission yet.")
            return
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(appContext, FlipShellUserService::class.java)
        ).daemon(false)
            .tag("privacy_flip_" + option)
            .version(2)
            .processNameSuffix("privacy_flip_" + option)

        val completed = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || completed.get()) {
                    finish("ERROR: Shizuku shell service did not connect.")
                    return
                }
                Thread {
                    val answer = runCatching {
                        IPrivacyFlipShell.Stub.asInterface(service).setOption(option, enabled)
                    }.getOrElse { "ERROR: " + (it.message ?: "Shizuku command failed.") }
                    finish(answer)
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish("ERROR: Shizuku shell service disconnected.")
            }

            private fun finish(answer: String) {
                if (!completed.compareAndSet(false, true)) return
                mainHandler.post {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    callback(answer)
                }
            }
        }

        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                if (completed.compareAndSet(false, true)) {
                    callback("ERROR: " + (it.message ?: "Could not connect to Shizuku."))
                }
            }
    }
}