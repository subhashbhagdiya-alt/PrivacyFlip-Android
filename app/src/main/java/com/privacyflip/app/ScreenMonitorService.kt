package com.privacyflip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
            .setContentText("Screen events are monitored; rule actions remain demo-only.")
            .setOngoing(true)
            .build()
        startForeground(2401, notification)

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
                val rules = runCatching {
                    val raw = prefs.getString("rules", "[]") ?: "[]"
                    val array = JSONArray(raw)
                    val updated = JSONArray()
                    val messages = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        if (item.optBoolean("enabled", true) && item.optString("trigger") == trigger) {
                            item.put("runs", (item.optInt("runs") + 1).coerceAtMost(1_000_000))
                            messages.add("Trigger detected: $trigger · ${item.optString("name")}. Demo action logged; system settings unchanged.")
                        }
                        updated.put(item)
                    }
                    prefs.edit().putString("rules", updated.toString()).apply()
                    messages
                }.getOrDefault(emptyList())
                if (rules.isNotEmpty()) {
                    val previous = prefs.getString("logs", "") ?: ""
                    prefs.edit().putString("logs", (rules.joinToString("\n") + "\n" + previous).take(6000)).apply()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ask Android to recreate the service if the process is reclaimed, where permitted.
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}