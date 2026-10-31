package com.privacyflip.app

import android.app.Application
import rikka.shizuku.Shizuku

class PrivacyFlipApplication : Application() {
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        MobileDataShizuku.warmConnect(this) { result ->
            if (result.startsWith("ENABLED") || result.startsWith("DISABLED")) {
                RestoreRecovery.recoverIfUnlocked(this, "Shizuku reconnected")
            }
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        MobileDataShizuku.resetConnection()
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
    }
}
