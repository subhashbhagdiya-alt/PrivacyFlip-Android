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
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps screen, unlock, battery, network, headset, airplane-mode and
 * face-down triggers alive when the app is closed. Android requires the ongoing notification.
 */
class MonitorService : Service() {
    companion object {
        private const val PREFS = "privacy_flip_local"
        private const val CHANNEL_ID = "privacy_flip_monitor"
        private const val NOTIFICATION_ID = 4101

        /** Starts or stops the monitor according to the saved "monitorEnabled" setting. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("monitorEnabled", true)
            try {
                if (enabled) app.startForegroundService(Intent(app, MonitorService::class.java))
                else app.stopService(Intent(app, MonitorService::class.java))
            } catch (_: Exception) {
                // Android may refuse a foreground-service start from the background; the next
                // app launch or boot will try again.
            }
        }
    }

    private var receiversRegistered = false
    private var networkRegistered = false
    private var sensorRegistered = false
    private var connectivity: ConnectivityManager? = null
    private var lastDefaultNetwork: Network? = null
    private var wasBelowThreshold = false
    private var faceDown = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> AutomationEventProcessor.handle(context, "Screen turns off")
                Intent.ACTION_SCREEN_ON -> AutomationEventProcessor.handle(context, "Screen turns on")
                Intent.ACTION_USER_PRESENT -> AutomationEventProcessor.handle(context, "Phone unlocked")
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> AutomationEventProcessor.handle(context, "Airplane mode changes")
                Intent.ACTION_HEADSET_PLUG -> {
                    if (isInitialStickyBroadcast) return
                    when (intent.getIntExtra("state", -1)) {
                        1 -> AutomationEventProcessor.handle(context, "Wired headphones plugged")
                        0 -> AutomationEventProcessor.handle(context, "Wired headphones unplugged")
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(context, intent, isInitialStickyBroadcast)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (lastDefaultNetwork != network) {
                lastDefaultNetwork = network
                AutomationEventProcessor.handle(applicationContext, "Network changes")
            }
        }

        override fun onLost(network: Network) {
            // During a handoff onAvailable(new) can arrive before onLost(old): ignore the old one.
            if (lastDefaultNetwork == network) {
                lastDefaultNetwork = connectivity?.activeNetwork?.takeIf { it != network }
                AutomationEventProcessor.handle(applicationContext, "Network changes")
            }
        }
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            val z = event.values[2]
            if (z < -7.5f && !faceDown) {
                faceDown = true
                AutomationEventProcessor.handle(applicationContext, "Motion / flip")
            } else if (z > -4.0f) {
                faceDown = false
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!receiversRegistered) registerAll()
        syncSensor()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterAll()
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean {
        return try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Privacy Flip monitor", NotificationManager.IMPORTANCE_LOW)
            )
            val openApp = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Privacy Flip is watching your triggers")
                .setContentText("Rules run offline on this device. Tap to open.")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun registerAll() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
        receiversRegistered = true

        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        lastDefaultNetwork = connectivity?.activeNetwork
        try {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = connectivity != null
        } catch (_: Exception) {
            // Network observation is optional and must not stop the other triggers.
        }
    }

    private fun unregisterAll() {
        if (receiversRegistered) {
            try { unregisterReceiver(systemReceiver) } catch (_: Exception) { }
            receiversRegistered = false
        }
        if (networkRegistered) {
            try { connectivity?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
            networkRegistered = false
        }
        setSensor(false)
    }

    private fun onBattery(context: Context, intent: Intent, initial: Boolean) {
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", -1)
        if (level < 0 || scale <= 0) return
        val percent = (level * 100) / scale
        val threshold = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("batteryThreshold", 20)
        val below = percent <= threshold
        if (!initial && below && !wasBelowThreshold) {
            AutomationEventProcessor.handle(context, "Battery threshold")
        }
        wasBelowThreshold = below
    }

    /** The accelerometer drains battery, so it only runs while a flip rule or preset is enabled. */
    private fun syncSensor() {
        val paused = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("masterPaused", false)
        setSensor(!paused && AutomationEventProcessor.isEventInUse(this, "Motion / flip"))
    }

    private fun setSensor(wanted: Boolean) {
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (wanted && !sensorRegistered) {
            val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensorRegistered = try {
                manager.registerListener(motionListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: RuntimeException) {
                false
            }
        } else if (!wanted && sensorRegistered) {
            try { manager.unregisterListener(motionListener) } catch (_: Exception) { }
            sensorRegistered = false
            faceDown = false
        }
    }
}
