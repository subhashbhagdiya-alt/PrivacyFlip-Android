package com.privacyflip.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Talks to the Shizuku app so the lock/unlock automation can run without root. */
internal object ShizukuBridge {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4242

    @Volatile
    private var service: IUserService? = null

    @Volatile
    private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IUserService.Stub.asInterface(binder) else null
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isRunning() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
            // Shizuku went away between the check and the request.
        }
    }

    /** Blocking: call only from a background thread. Returns null on success or a failure reason. */
    fun apply(feature: Feature, on: Boolean): String? {
        if (!isRunning()) return "Shizuku is not running"
        if (!hasPermission()) return "Shizuku permission not granted"
        val svc = service ?: bind() ?: return "could not connect to the Shizuku service"
        val raw = try {
            svc.apply(feature.key, on)
        } catch (e: Exception) {
            service = null
            return "Shizuku call failed (${e.javaClass.simpleName})"
        }
        val code = raw.substringAfter("EXIT:").substringBefore("\n").toIntOrNull() ?: -1
        if (code == 0) return null
        val detail = raw.substringAfter("\n", "").take(80)
        return "command failed (exit $code${if (detail.isNotBlank()) ": $detail" else ""})"
    }

    private fun bind(): IUserService? {
        val waiter = CountDownLatch(1)
        latch = waiter
        val args = Shizuku.UserServiceArgs(ComponentName("com.privacyflip.app", UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .version(1)
        Shizuku.bindUserService(args, connection)
        waiter.await(6, TimeUnit.SECONDS)
        return service
    }
}

internal object ShizukuBackend : AutomationBackend {
    override val name = "Shizuku"
    override fun supports(feature: Feature) = true
    override fun apply(feature: Feature, on: Boolean): String? = ShizukuBridge.apply(feature, on)
}
