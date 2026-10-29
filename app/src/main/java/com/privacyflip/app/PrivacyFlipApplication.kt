package com.privacyflip.app

import android.app.Application
import rikka.shizuku.Shizuku

class PrivacyFlipApplication : Application() {
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        MobileDataShizuku.warmConnect(this)
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
