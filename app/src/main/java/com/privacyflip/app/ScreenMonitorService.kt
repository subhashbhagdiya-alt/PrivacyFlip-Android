package com.privacyflip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

class ScreenMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "privacy_flip_monitor"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Privacy Flip monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_privacy_flip)
            .setContentTitle("Privacy Flip is monitoring")
            .setContentText("Screen triggers are active. Matching rules post action notifications.")
            .setContentIntent(PendingIntent.getActivity(
                this,
                2402,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .setOngoing(true)
            .build()
        startForeground(2401, notification)
        getSharedPreferences("privacy_flip", Context.MODE_PRIVATE).edit()
            .putBoolean("monitor_service_running", true).apply()

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val trigger = when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> "Screen turns ON"
                    Intent.ACTION_SCREEN_OFF -> "Screen turns OFF"
                    Intent.ACTION_USER_PRESENT -> "Phone unlocked"
                    else -> return
                }
                val prefs = getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)
                if (prefs.getBoolean("paused", false)) return

                runCatching {
                    val raw = prefs.getString("rules", "[]") ?: "[]"
                    val array = JSONArray(raw)
                    val updated = JSONArray()
                    val messages = mutableListOf<String>()
                    val matchedRules = mutableListOf<Pair<String, String>>()
                    var notificationIndex = 0

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val savedTrigger = item.optString("trigger")
                        val name = item.optString("name", "Privacy rule")
                        val action = item.optString("action", "Log only (no system change)")
                        // New multi-feature rules store paired actions separated by |||.
                        // Older single-action rules remain fully compatible.
                        val actions = action.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
                        val pairedOptions = actions.mapNotNull { pairedOption(it) }.distinct()
                        val pairedAction = pairedOptions.isNotEmpty() || actions.any { it.equals("Toggle Do Not Disturb", ignoreCase = true) }
                        val lockUnlockTrigger = trigger == "Screen turns OFF" || trigger == "Phone unlocked"
                        val savedLockUnlockTrigger = savedTrigger == "Screen turns OFF" ||
                            savedTrigger == "Phone locks / screen turns OFF" ||
                            savedTrigger == "Phone unlocked"
                        val triggerMatches = if (pairedAction) {
                            lockUnlockTrigger && savedLockUnlockTrigger
                        } else {
                            savedTrigger == trigger ||
                                (trigger == "Screen turns OFF" && savedTrigger == "Phone locks / screen turns OFF")
                        }

                        if (item.optBoolean("enabled", true) && triggerMatches) {
                            item.put("runs", (item.optInt("runs") + 1).coerceAtMost(1_000_000))

                            if (pairedOptions.isNotEmpty()) {
                                pairedOptions.forEach { option ->
                                    if (option == "mobile_data") {
                                        // Mobile data uses the verified restore ledger.
                                        val logAction: (String) -> Unit = { message -> appendLog(prefs, "$trigger · $name: $message") }
                                        if (trigger == "Screen turns OFF") {
                                            MobileDataRestoreController.onLock(this@ScreenMonitorService, name, logAction)
                                        } else {
                                            MobileDataRestoreController.onUnlock(this@ScreenMonitorService, logAction)
                                        }
                                    } else if (option == "wifi" || option == "bluetooth" || option == "location") {
                                        val logAction: (String) -> Unit = { message ->
                                            appendLog(prefs, "$trigger · $name: $message")
                                        }
                                        if (trigger == "Screen turns OFF") {
                                            PairedFeatureRestoreController.onLock(this@ScreenMonitorService, option, name, logAction)
                                        } else {
                                            PairedFeatureRestoreController.onUnlock(this@ScreenMonitorService, option, logAction)
                                        }
                                    } else {
                                        // Sensor privacy state readback is not implemented yet, so avoid
                                        // blindly toggling it and potentially overriding the user's choice.
                                        val message = "$option automation skipped: safe state verification is not available yet."
                                        appendLog(prefs, "$trigger · $name: $message")
                                        postAutomationFailureNotification(name, message)
                                    }
                                }
                                messages.add("$trigger · $name: requested ${pairedOptions.size} automatic feature(s).")
                            } else if (actions.any { it.equals("Toggle Do Not Disturb", ignoreCase = true) } && lockUnlockTrigger) {
                                val targetAction = if (trigger == "Screen turns OFF") "Enable Do Not Disturb" else "Disable Do Not Disturb"
                                if (!applyDoNotDisturb(targetAction)) {
                                    matchedRules.add(name to targetAction)
                                    messages.add("$trigger · $name: grant Do Not Disturb access in Android settings.")
                                } else {
                                    messages.add("$trigger · $name: Do Not Disturb " +
                                        if (targetAction == "Enable Do Not Disturb") "enabled." else "disabled.")
                                }
                            } else {
                                matchedRules.add(name to action)
                                messages.add("Trigger detected: $trigger · $name. Tap the notification to open $action; this shortcut does not change that setting automatically.")
                            }
                        }
                        updated.put(item)
                    }

                    prefs.edit().putString("rules", updated.toString()).apply()
                    matchedRules.forEach { match ->
                        postRuleActionNotification(match.first, match.second, notificationIndex++)
                    }
                    if (messages.isNotEmpty()) {
                        val previous = prefs.getString("logs", "") ?: ""
                        prefs.edit().putString("logs", (messages.joinToString("\n") + "\n" + previous).take(6000)).apply()
                    }
                }.onFailure { error ->
                    appendLog(prefs, "Screen rule error: " + (error.message ?: "unknown error"))
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun recordMobileDataResult(ruleName: String, enabled: Boolean, result: String) {
        val success = result.startsWith("OK|")
        val requested = if (enabled) "ON" else "OFF"
        val detail = result.substringAfter("|", result).take(220)
        val message = if (success)
            "Mobile Data " + requested + " command completed (shell exit 0); actual device state was not independently queried. Rule: " + ruleName + "."
        else "Mobile Data " + requested + " FAILED. Rule: " + ruleName + ". " + detail
        val prefs = getSharedPreferences("privacy_flip", Context.MODE_PRIVATE)
        val previous = prefs.getString("logs", "") ?: ""
        prefs.edit().putString("logs", (message + "\n" + previous).take(6000)).apply()
        if (!success) {
            runCatching {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pending = PendingIntent.getActivity(
                    this, 3301, intent, PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Notification.Builder(this, "privacy_flip_monitor") else Notification.Builder(this)
                builder.setSmallIcon(R.drawable.ic_privacy_flip)
                    .setContentTitle("Privacy Flip: Mobile Data action failed")
                    .setContentText(detail.take(120))
                    .setContentIntent(pending).setAutoCancel(true)
                getSystemService(NotificationManager::class.java).notify(3301, builder.build())
            }
        }
    }

    private fun pairedOption(action: String): String? = when (action.trim()) {
        "Auto: Mobile data OFF on lock / ON on unlock" -> "mobile_data"
        "Auto: Wi-Fi OFF on lock / ON on unlock" -> "wifi"
        "Auto: Bluetooth OFF on lock / ON on unlock" -> "bluetooth"
        "Auto: Location OFF on lock / ON on unlock" -> "location"
        "Auto: Camera privacy ON on lock / OFF on unlock" -> "camera_privacy"
        "Auto: Microphone privacy ON on lock / OFF on unlock" -> "microphone_privacy"
        else -> null
    }

    private fun appendLog(prefs: android.content.SharedPreferences, message: String) {
        val previous = prefs.getString("logs", "") ?: ""
        prefs.edit().putString("logs", (message + "\n" + previous).take(6000)).apply()
    }

    private fun postAutomationFailureNotification(ruleName: String, reason: String) {
        val pending = PendingIntent.getActivity(
            this,
            (ruleName.hashCode() and 0x7fffffff),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "privacy_flip_monitor")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_privacy_flip)
            .setContentTitle("Automatic action needs setup: $ruleName")
            .setContentText(reason.take(180))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(15000 + ((ruleName.hashCode() and 0x7fffffff) % 10000), notification)
    }

    private fun applyDoNotDisturb(action: String): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted) return false
        return runCatching {
            manager.setInterruptionFilter(
                if (action.equals("Enable Do Not Disturb", ignoreCase = true))
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }.isSuccess
    }

    private fun postRuleActionNotification(ruleName: String, action: String, index: Int) {
        if (action.equals("Log only (no system change)", ignoreCase = true)) return
        val pending = PendingIntent.getActivity(
            this,
            (ruleName.hashCode() * 31 + index) and 0x7fffffff,
            Intent(this, RuleActionActivity::class.java).putExtra(RuleActionActivity.EXTRA_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "privacy_flip_monitor")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_privacy_flip)
            .setContentTitle("Privacy rule triggered: $ruleName")
            .setContentText("Tap to open: $action")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(2500 + ((ruleName.hashCode() and 0x7fffffff) % 10000), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ask Android to recreate the service if the process is reclaimed, where permitted.
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        getSharedPreferences("privacy_flip", Context.MODE_PRIVATE).edit()
            .putBoolean("monitor_service_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}