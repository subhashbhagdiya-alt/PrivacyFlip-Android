package com.privacyflip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

/**
 * Records enabled "Phone starts" rules after Android sends BOOT_COMPLETED.
 * This prototype records the trigger only; it does not change system settings.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)
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
                rule.put("runs", rule.optInt("runs", 0) + 1)
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
