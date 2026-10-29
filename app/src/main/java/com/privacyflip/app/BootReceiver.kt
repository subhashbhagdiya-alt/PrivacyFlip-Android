package com.privacyflip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

        val matched = mutableListOf<Pair<String, String>>()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optBoolean("enabled", true) && rule.optString("trigger") == "Phone starts") {
                rule.put("runs", (rule.optInt("runs", 0) + 1).coerceAtMost(1_000_000))
                matched += rule.optString("name", "Untitled") to rule.optString("action", "Log only (no system change)")
            }
        }
        if (matched.isEmpty()) return

        val oldLogs = prefs.getString("logs", "") ?: ""
        val message = "Phone started: " + matched.joinToString(", ") { it.first } + ". Tap rule notifications to run supported actions."
        prefs.edit()
            .putString("rules", rules.toString())
            .putString("logs", (message + "\n" + oldLogs).take(6000))
            .apply()
        matched.forEachIndexed { index, rule -> postRuleNotification(context, rule.first, rule.second, index) }
    }
    private fun postRuleNotification(context: Context, name: String, action: String, index: Int) {
        if (action.equals("Log only (no system change)", ignoreCase = true)) return
        val channelId = "privacy_flip_monitor"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Privacy Flip monitoring", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pending = PendingIntent.getActivity(
            context, (name.hashCode() * 31 + index) and 0x7fffffff,
            Intent(context, RuleActionActivity::class.java).putExtra(RuleActionActivity.EXTRA_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channelId) else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder.setSmallIcon(R.drawable.ic_privacy_flip)
            .setContentTitle("Phone-start rule: " + name)
            .setContentText("Tap to run: " + action)
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(12500 + ((name.hashCode() and 0x7fffffff) % 10000), notification)
    }

}