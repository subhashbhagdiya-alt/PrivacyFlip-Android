package com.privacyflip.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

internal data class ActionDef(val id: String, val label: String)

internal object Actions {
    val all = listOf(
        ActionDef("log", "Write local activity log"),
        ActionDef("notify", "Show notification (permission needed)"),
        ActionDef("status", "Show in-app status"),
        ActionDef("vibrate", "Vibrate phone briefly"),
        ActionDef("ringer_normal", "Sound mode: Normal"),
        ActionDef("ringer_vibrate", "Sound mode: Vibrate"),
        ActionDef("ringer_silent", "Sound mode: Silent (needs Do Not Disturb access)"),
        ActionDef("dnd_on", "Do Not Disturb ON, alarms only (needs access)"),
        ActionDef("dnd_off", "Do Not Disturb OFF (needs access)"),
        ActionDef("torch_on", "Flashlight ON"),
        ActionDef("torch_off", "Flashlight OFF"),
        ActionDef("brightness_low", "Brightness LOW (needs Modify system settings)"),
        ActionDef("brightness_high", "Brightness HIGH (needs Modify system settings)"),
        ActionDef("lock", "Lock screen (needs Accessibility service)"),
        ActionDef("wifi_panel", "Wi-Fi: notification with one-tap panel"),
        ActionDef("bt_panel", "Bluetooth: notification with one-tap settings")
    )

    fun idForLabel(label: String): String? = all.firstOrNull { it.label == label }?.id
}

/** Runs one action using only normal Android APIs: no root, no Shizuku, no ADB. */
internal object ActionExecutor {
    /** Returns a short human-readable result that is written to the activity log. */
    fun run(context: Context, actionId: String, title: String, event: String): String = try {
        when (actionId) {
            "log", "status" -> "logged"
            "notify" -> AutomationEventProcessor.showNotification(context, title, "Triggered by: $event")
            "vibrate" -> vibrate(context)
            "ringer_normal" -> setRinger(context, AudioManager.RINGER_MODE_NORMAL, "normal")
            "ringer_vibrate" -> setRinger(context, AudioManager.RINGER_MODE_VIBRATE, "vibrate")
            "ringer_silent" -> setRinger(context, AudioManager.RINGER_MODE_SILENT, "silent")
            "dnd_on" -> setDnd(context, true)
            "dnd_off" -> setDnd(context, false)
            "torch_on" -> setTorch(context, true)
            "torch_off" -> setTorch(context, false)
            "brightness_low" -> setBrightness(context, 20)
            "brightness_high" -> setBrightness(context, 200)
            "lock" -> lockScreen()
            "wifi_panel" -> panelNotification(
                context, title, "Tap to open Wi-Fi controls",
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                else Intent(Settings.ACTION_WIFI_SETTINGS)
            )
            "bt_panel" -> panelNotification(
                context, title, "Tap to open Bluetooth settings", Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            )
            else -> "unknown action"
        }
    } catch (_: SecurityException) {
        "blocked by an Android permission"
    } catch (e: Exception) {
        "failed (${e.javaClass.simpleName})"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context): String {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return "no vibrator available"
        vibrator.vibrate(VibrationEffect.createOneShot(300L, VibrationEffect.DEFAULT_AMPLITUDE))
        return "vibrated"
    }

    private fun setRinger(context: Context, mode: Int, label: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "audio service unavailable"
        if (mode == AudioManager.RINGER_MODE_SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null || !nm.isNotificationPolicyAccessGranted) return "needs Do Not Disturb access (Settings)"
        }
        audio.ringerMode = mode
        return "sound mode set to $label"
    }

    private fun setDnd(context: Context, on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "notification service unavailable"
        if (!nm.isNotificationPolicyAccessGranted) return "needs Do Not Disturb access (Settings)"
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "Do Not Disturb on (alarms only)" else "Do Not Disturb off"
    }

    private fun setTorch(context: Context, on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "camera service unavailable"
        val id = cm.cameraIdList.firstOrNull { cameraId ->
            val c = cm.getCameraCharacteristics(cameraId)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return "no flashlight found"
        cm.setTorchMode(id, on)
        return if (on) "flashlight on" else "flashlight off"
    }

    private fun setBrightness(context: Context, value: Int): String {
        if (!Settings.System.canWrite(context)) return "needs Modify system settings access (Settings)"
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return "brightness set to ${value * 100 / 255}%"
    }

    private fun lockScreen(): String {
        val service = PrivacyFlipAccessibilityService.instance
            ?: return "needs Accessibility service enabled (Settings)"
        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) "screen locked"
        else "lock request rejected by Android"
    }

    /** Android blocks silent Wi-Fi/Bluetooth switching, so give a one-tap shortcut instead. */
    private fun panelNotification(context: Context, title: String, text: String, intent: Intent): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, text.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return AutomationEventProcessor.showNotification(context, title, text, pending)
    }
}
