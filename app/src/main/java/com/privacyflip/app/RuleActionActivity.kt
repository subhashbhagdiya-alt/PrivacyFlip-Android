package com.privacyflip.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Opens the Android settings page selected by a user tapping a rule notification.
 * Android requires user confirmation for the actual system change.
 */
class RuleActionActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "com.privacyflip.app.EXTRA_RULE_ACTION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION).orEmpty().trim().lowercase()
        if (action == "toggle do not disturb") {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!manager.isNotificationPolicyAccessGranted) {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Could not open Do Not Disturb access settings", Toast.LENGTH_LONG).show() }
                finish()
                return
            }
            runCatching {
                manager.setInterruptionFilter(
                    if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
            }.onSuccess {
                val enabled = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                Toast.makeText(this, "Do Not Disturb " + if (enabled) "enabled" else "disabled", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Could not change Do Not Disturb", Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        val target = when (action) {
            "open wi-fi settings", "wifi settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "open location/gps settings", "location settings" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "open bluetooth settings", "bluetooth settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "open mobile data settings", "open internet panel", "open internet connectivity panel" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "open app permissions", "app permissions" ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            "open airplane mode settings" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "open camera privacy controls", "open microphone privacy controls", "open privacy settings", "privacy settings" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            "open notification settings", "notification settings" ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else -> null
        }
        if (target == null) {
            Toast.makeText(this, "This rule has no supported system action.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            startActivity(target)
        } catch (_: Exception) {
            Toast.makeText(this, "This settings page is unavailable on this device.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
