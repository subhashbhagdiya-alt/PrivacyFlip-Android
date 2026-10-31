package com.privacyflip.app

import android.app.KeyguardManager
import android.content.Context

/**
 * Retries only restore records owned by Privacy Flip.
 * Never restores while the keyguard is still locked.
 */
object RestoreRecovery {
    private val pairedOptions = listOf(
        "wifi", "bluetooth", "location", "camera_privacy", "microphone_privacy"
    )

    fun recoverIfUnlocked(context: Context, source: String) {
        val app = context.applicationContext
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != false) return

        val prefs = app.getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)
        val log: (String) -> Unit = { message ->
            val old = prefs.getString("logs", "") ?: ""
            prefs.edit().putString("logs", "Recovery ($source): $message\\n$old".take(6000)).apply()
        }

        pairedOptions.forEach { option ->
            if (prefs.getBoolean("paired_restore_" + option + "_owned", false)) {
                PairedFeatureRestoreController.onUnlock(app, option, log)
            }
        }
        if (prefs.getBoolean("mobile_data_restore_owned", false)) {
            MobileDataRestoreController.onUnlock(app, log)
        }
    }
}
