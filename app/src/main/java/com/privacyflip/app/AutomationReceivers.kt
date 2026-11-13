package com.privacyflip.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Handles every trigger event: runs matching custom rules and enabled presets. */
object AutomationEventProcessor {
    private const val PREFS = "privacy_flip_local"
    private const val LOG_KEY = "eventLog"
    private const val CHANNEL_ID = "privacy_flip_events"

    fun handle(context: Context, event: String, onlyRuleId: String? = null) {
        if (onlyRuleId == null) LockUnlockEngine.onEvent(context, event)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("masterPaused", false)) return

        val rules = readRules(prefs)
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            if (!rule.optBoolean("enabled", false)) continue
            if (onlyRuleId != null && rule.optString("id") != onlyRuleId) continue
            if (rule.optString("trigger") != event) continue
            if (event == "Time schedule") {
                val now = Date()
                val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(now)
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                val scheduledTime = rule.optString("scheduledTime", "09:00")
                val lastRunDate = prefs.getString("lastScheduleRun_${rule.optString("id")}", "") ?: ""
                if (scheduledTime > currentTime || lastRunDate == today) continue
                prefs.edit().putString("lastScheduleRun_${rule.optString("id")}", today).apply()
            }

            val name = rule.optString("name", "Custom rule").ifBlank { "Custom rule" }
            val actionId = Actions.idForLabel(rule.optString("action"))
            val result = if (actionId == null) "action not supported"
            else ActionExecutor.run(context, actionId, name, event)
            appendLog(context, "$event — $name: $result")
        }

        if (onlyRuleId != null) return
        for (preset in PresetCatalog.all) {
            if (preset.event != event || !prefs.getBoolean("preset_${preset.id}", false)) continue
            for (action in preset.actions) {
                if (!prefs.getBoolean("action_${preset.id}_${action.id}", false)) continue
                val result = ActionExecutor.run(context, action.id, preset.title, event)
                appendLog(context, "$event — ${preset.title} · ${action.title}: $result")
            }
        }
    }

    /** True when an enabled custom rule or preset listens to [event]. */
    fun isEventInUse(context: Context, event: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rules = readRules(prefs)
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optBoolean("enabled", false) && rule.optString("trigger") == event) return true
        }
        return PresetCatalog.all.any { it.event == event && prefs.getBoolean("preset_${it.id}", false) }
    }

    private fun readRules(prefs: SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString("customRules", "[]")) } catch (_: Exception) { JSONArray() }

    internal fun appendLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(LOG_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val entries = mutableListOf<String>()
        for (i in 0 until old.length()) old.optString(i).takeIf { it.isNotBlank() }?.let(entries::add)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        entries.add("$timestamp · $message")
        val updated = JSONArray()
        entries.takeLast(50).forEach { entry -> updated.put(entry) }
        prefs.edit().putString(LOG_KEY, updated.toString()).apply()
    }

    /** Returns a short result string for the activity log. */
    internal fun showNotification(
        context: Context, title: String, text: String, tapIntent: PendingIntent? = null
    ): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "notification skipped: permission not granted"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "notification service unavailable"
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Privacy Flip automation", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (tapIntent != null) builder.setContentIntent(tapIntent)
        return try {
            manager.notify((title + text).hashCode(), builder.build())
            "notification shown"
        } catch (_: SecurityException) {
            "notification blocked by system permission"
        }
    }
}

class PowerEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> AutomationEventProcessor.handle(context, "Charger connected")
            Intent.ACTION_POWER_DISCONNECTED -> AutomationEventProcessor.handle(context, "Charger disconnected")
        }
    }
}

class StartupEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                AutomationEventProcessor.handle(context, "Device starts")
                AutomationScheduler.scheduleNext(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                AutomationScheduler.scheduleNext(context)
        }
    }
}

/** Schedules the next enabled daily rule. Uses an exact alarm when the user allowed it. */
object AutomationScheduler {
    private const val PREFS = "privacy_flip_local"
    private const val REQUEST_CODE = 7104

    fun scheduleNext(context: Context) {
        // Keep the background monitor in step with the saved settings on every change.
        MonitorService.sync(context)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        if (prefs.getBoolean("masterPaused", false)) return

        val rules = try { JSONArray(prefs.getString("customRules", "[]")) } catch (_: Exception) { JSONArray() }
        val now = Calendar.getInstance()
        val nowTime = SimpleDateFormat("HH:mm", Locale.US).format(now.time)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
        var nextAt: Calendar? = null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!rule.optBoolean("enabled", false) || rule.optString("trigger") != "Time schedule") continue
            val time = rule.optString("scheduledTime", "09:00")
            if (!time.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) continue
            val lastRun = prefs.getString("lastScheduleRun_${rule.optString("id")}", "") ?: ""
            val candidate = Calendar.getInstance().apply {
                val parts = time.split(":")
                set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                set(Calendar.MINUTE, parts[1].toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (time <= nowTime || lastRun == today) add(Calendar.DAY_OF_YEAR, 1)
            }
            if (nextAt == null || candidate.before(nextAt)) nextAt = candidate
        }
        nextAt?.let { calendar ->
            val triggerAt = maxOf(calendar.timeInMillis, System.currentTimeMillis() + 60_000L)
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            try {
                if (canExact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }
}

class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationEventProcessor.handle(context, "Time schedule")
        AutomationScheduler.scheduleNext(context)
    }
}
