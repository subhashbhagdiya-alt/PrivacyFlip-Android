package com.privacyflip.app

import android.app.Application
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class PrivacyFlipApplication : Application() {
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        // Restore every owned feature as soon as Shizuku is usable. Do not make
        // Wi-Fi/Bluetooth/Location recovery depend on Mobile Data probing.
        MobileDataShizuku.warmConnect(this)
        if (ShizukuAutomation.hasPermission()) {
            RestoreRecovery.recoverIfUnlocked(this, "Shizuku reconnected")
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        MobileDataShizuku.resetConnection()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            RestoreRecovery.recoverIfUnlocked(this, "Shizuku permission granted")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }
}
