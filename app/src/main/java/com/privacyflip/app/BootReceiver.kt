package com.privacyflip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray

/**
 * Restores the opt-in screen monitor after reboot and records matching boot rules.
 * Rule actions remain demo-only; this receiver never changes Android system settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)

        // Monitoring is opt-in and only restarted if the user enabled it previously.
        if (prefs.getBoolean("screen_monitor_enabled", false)) {
            runCatching {
                val serviceIntent = Intent(context, ScreenMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }

        if (prefs.getBoolean("paused", false)) return
        val raw = prefs.getString("rules", null) ?: return
        val rules = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return
        }

        val matched = mutableListOf<String>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optBoolean("enabled", true) && rule.optString("trigger") == "Phone starts") {
                rule.put("runs", (rule.optInt("runs", 0) + 1).coerceAtMost(1_000_000))
                matched += rule.optString("name", "Untitled")
            }
        }
        if (matched.isEmpty()) return

        val oldLogs = prefs.getString("logs", "") ?: ""
        val message = "Phone started: ${matched.joinToString(", ")}. Demo action logged; system settings unchanged."
        prefs.edit()
            .putString("rules", rules.toString())
            .putString("logs", (message + "\n" + oldLogs).take(6000))
            .apply()
    }
}