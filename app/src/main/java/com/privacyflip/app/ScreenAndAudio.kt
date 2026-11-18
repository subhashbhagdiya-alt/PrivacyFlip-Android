package com.privacyflip.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/** Reads and changes the screen brightness. Writing directly needs "Modify system settings"; otherwise a backend does it. */
internal object BrightnessControl {
    /** Lowest level that still keeps the screen readable on every phone. */
    const val MIN = 1

    fun current(context: Context): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) {
        null
    }

    fun isAuto(context: Context): Boolean? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Exception) {
        null
    }

    fun canWriteDirectly(context: Context): Boolean = try {
        Settings.System.canWrite(context)
    } catch (_: Exception) {
        false
    }

    /** Returns null on success, otherwise a short reason. */
    fun set(context: Context, backend: AutomationBackend, value: Int, auto: Boolean): String? {
        if (canWriteDirectly(context)) {
            return try {
                val resolver = context.contentResolver
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto) 1 else 0)
                if (!auto) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
                null
            } catch (e: Exception) {
                "write failed (${e.javaClass.simpleName})"
            }
        }
        return backend.setBrightness(value, auto)
    }
}

/** Tells whether sound is playing out of a Bluetooth device (music, video or a call). */
internal object BluetoothAudio {
    @Suppress("DEPRECATION")
    fun isActive(context: Context): Boolean = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val viaDevices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        val routed = audio.isBluetoothA2dpOn || audio.isBluetoothScoOn || viaDevices
        val playing = audio.isMusicActive ||
            audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
        routed && playing
    } catch (_: Exception) {
        false
    }
}
