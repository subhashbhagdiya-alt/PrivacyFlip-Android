package com.privacyflip.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** User-consented Shizuku bridge. No network connection is used. */
object MobileDataShizuku {
    const val ACTION_NAME = "Auto: Mobile data OFF on lock / ON on unlock"
    const val TRIGGER_NAME = "Phone locks / screen turns OFF"

    private data class Request(val enabled: Boolean, val callback: (String) -> Unit)
    private data class StateRequest(val callback: (String) -> Unit)
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = mutableListOf<Request>()
    private val pendingState = mutableListOf<StateRequest>()
    @Volatile private var remote: IDataAutomationService? = null
    private var binding = false
    private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val api = IDataAutomationService.Stub.asInterface(service)
            val requests: List<Request>
            val stateRequests: List<StateRequest>
            synchronized(lock) {
                remote = api
                binding = false
                requests = pending.toList()
                pending.clear()
                stateRequests = pendingState.toList()
                pendingState.clear()
            }
            if (api == null) {
                requests.forEach { it.callback("ERROR|Could not connect to Shizuku service") }
                stateRequests.forEach { it.callback("UNKNOWN|service|Could not connect to Shizuku service") }
            } else {
                requests.forEach { runRequest(api, it) }
                stateRequests.forEach { runStateRequest(api, it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { remote = null; binding = false }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { remote = null; binding = false }
            failPending("Shizuku service disconnected; restart Shizuku and retry")
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(lock) { remote = null; binding = false }
            failPending("Shizuku returned an empty service binding")
        }
    }

    fun status(): String = try {
        when {
            !Shizuku.pingBinder() -> "Shizuku service is not running. Start it first."
            Shizuku.isPreV11() -> "This Shizuku server version is too old. Update Shizuku."
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku is connected and Privacy Flip permission is granted."
            else -> "Shizuku is running, but Privacy Flip permission has not been granted."
        }
    } catch (t: Throwable) {
        "Shizuku status unavailable: " + (t.message ?: t.javaClass.simpleName)
    }

    fun hasPermission(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun setMobileDataEnabled(context: Context, enabled: Boolean, callback: (String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            callback("ERROR|Shizuku is not running. Start Shizuku after reboot and try again.")
            return
        }
        val permissionGranted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        if (!permissionGranted) {
            callback("ERROR|Grant Privacy Flip permission in the Shizuku app first.")
            return
        }

        var api: IDataAutomationService? = null
        var shouldBind = false
        synchronized(lock) {
            api = remote
            if (api == null) {
                pending.add(Request(enabled, callback))
                if (!binding) { binding = true; shouldBind = true }
            }
        }
        if (api != null) {
            runRequest(api!!, Request(enabled, callback))
        } else if (shouldBind) {
            try {
                val serviceArgs = Shizuku.UserServiceArgs(
                    ComponentName(context.applicationContext, MobileDataUserService::class.java)
                ).tag("privacy_flip_mobile_data").version(1).daemon(true)
                args = serviceArgs
                Shizuku.bindUserService(serviceArgs, connection)
            } catch (t: Throwable) {
                synchronized(lock) { binding = false }
                failPending("Unable to bind Shizuku service: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }


    /** Reads the current data state. UNKNOWN is deliberately not converted to ON or OFF. */
    fun getMobileDataState(context: Context, callback: (String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            callback("UNKNOWN|shizuku|Shizuku is not running")
            return
        }
        val permissionGranted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        if (!permissionGranted) {
            callback("UNKNOWN|permission|Grant Privacy Flip permission in Shizuku first")
            return
        }

        var api: IDataAutomationService? = null
        var shouldBind = false
        synchronized(lock) {
            api = remote
            if (api == null) {
                pendingState.add(StateRequest(callback))
                if (!binding) { binding = true; shouldBind = true }
            }
        }
        if (api != null) {
            runStateRequest(api!!, StateRequest(callback))
        } else if (shouldBind) {
            try {
                val serviceArgs = Shizuku.UserServiceArgs(
                    ComponentName(context.applicationContext, MobileDataUserService::class.java)
                ).tag("privacy_flip_mobile_data").version(1).daemon(true)
                args = serviceArgs
                Shizuku.bindUserService(serviceArgs, connection)
            } catch (t: Throwable) {
                synchronized(lock) { binding = false }
                failPending("Unable to bind Shizuku service: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun runStateRequest(api: IDataAutomationService, request: StateRequest) {
        worker.execute {
            val result = try {
                api.getMobileDataState()
            } catch (t: Throwable) {
                "UNKNOWN|" + (t.message ?: t.javaClass.simpleName)
            }
            request.callback(result)
        }
    }

    private fun runRequest(api: IDataAutomationService, request: Request) {
        worker.execute {
            val result = try {
                api.setMobileDataEnabled(request.enabled)
            } catch (t: Throwable) {
                "ERROR|" + (t.message ?: t.javaClass.simpleName)
            }
            request.callback(result)
        }
    }

    private fun failPending(message: String) {
        val requests: List<Request>
        synchronized(lock) { requests = pending.toList(); pending.clear() }
        requests.forEach { it.callback("ERROR|" + message) }
    }
}
