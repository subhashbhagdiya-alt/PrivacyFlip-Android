package com.privacyflip.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Optional. It performs the system "lock screen" action and, because the system keeps an enabled
 * accessibility service alive, it also watches screen on/off/unlock as a second, sturdier host
 * for the lock/unlock automation. It declares no event types and never reads screen content.
 */
class PrivacyFlipAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: PrivacyFlipAccessibilityService? = null
            private set
    }

    private var receiverRegistered = false

    // Only the lock/unlock engine is called here; the monitor service handles rules and presets,
    // so nothing runs twice. The engine ignores a repeated lock or unlock.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> LockUnlockEngine.onEvent(context, "Screen turns off")
                Intent.ACTION_SCREEN_ON -> LockUnlockEngine.onEvent(context, "Screen turns on")
                Intent.ACTION_USER_PRESENT -> LockUnlockEngine.onEvent(context, "Phone unlocked")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerScreenReceiver()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        unregisterScreenReceiver()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        unregisterScreenReceiver()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // The monitor service still covers these events.
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Already unregistered.
        }
        receiverRegistered = false
    }
}
