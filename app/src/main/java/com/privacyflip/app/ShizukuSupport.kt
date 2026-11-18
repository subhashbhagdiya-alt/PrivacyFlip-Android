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

    /** Short description for the system report, e.g. "uid 2000, API 13". */
    fun describe(): String = try {
        if (isRunning()) "uid ${Shizuku.getUid()}, API ${Shizuku.getVersion()}" else "no binder"
    } catch (_: Throwable) {
        "unknown"
    }

    fun requestPermission() {
        try {
            if (isRunning() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
            // Shizuku went away between the check and the request.
        }
    }

    /** Blocking. Returns null when the Shizuku user service is connected, otherwise the reason it is not. */
    fun connectionTest(): String? {
        if (!isRunning()) return "Shizuku is not running"
        if (!hasPermission()) return "Shizuku permission not granted"
        return if (service != null || bind() != null) null else "could not bind the Shizuku user service"
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

    /** Blocking. Returns null on success or a failure reason. */
    fun setBrightness(value: Int, auto: Boolean): String? {
        val raw = call { it.setBrightness(value, auto) }
        if (raw.startsWith("FAILED")) return raw.removePrefix("FAILED").trim()
        val code = raw.substringAfter("EXIT:").substringBefore("\n").toIntOrNull() ?: -1
        return if (code == 0) null else "command failed (exit $code)"
    }

    /** Blocking. One line per step, each starting with OK or FAILED. */
    fun keepAlive(): String = call { it.keepAlive() }

    /** Blocking. Starts with OK or FAILED. */
    fun enableAccessibility(): String = call { it.enableAccessibility() }

    private fun call(block: (IUserService) -> String): String {
        if (!isRunning()) return "FAILED Shizuku is not running"
        if (!hasPermission()) return "FAILED Shizuku permission not granted"
        val svc = service ?: bind() ?: return "FAILED could not connect to the Shizuku service"
        return try {
            block(svc)
        } catch (e: Exception) {
            service = null
            "FAILED Shizuku call failed (${e.javaClass.simpleName})"
        }
    }

    private fun bind(): IUserService? {
        val waiter = CountDownLatch(1)
        latch = waiter
        val args = Shizuku.UserServiceArgs(ComponentName("com.privacyflip.app", UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .version(3)
        Shizuku.bindUserService(args, connection)
        waiter.await(6, TimeUnit.SECONDS)
        return service
    }
}

internal object ShizukuBackend : AutomationBackend {
    override val name = "Shizuku"
    override fun supports(feature: Feature) = true
    override fun apply(feature: Feature, on: Boolean): String? = ShizukuBridge.apply(feature, on)
    override fun setBrightness(value: Int, auto: Boolean): String? = ShizukuBridge.setBrightness(value, auto)
}
