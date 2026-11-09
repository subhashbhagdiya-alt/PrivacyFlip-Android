package com.privacyflip.app

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * This binder is instantiated by Shizuku as the shell (ADB) or root user.
 * It accepts only a small allowlist of fixed actions; arbitrary shell commands
 * are never accepted from the app process.
 */
class FlipShellUserService : IPrivacyFlipShell.Stub() {
    override fun getOptionState(option: String): String {
        if (option == "camera_privacy" || option == "microphone_privacy") {
            return readSensorPrivacyState(option)
        }
        val command = when (option) {
            "wifi" -> "settings get global wifi_on"
            "bluetooth" -> "settings get global bluetooth_on"
            "location" -> "settings get secure location_mode"
            "nfc" -> "settings get global nfc_on"
            else -> return "UNKNOWN|Unsupported state query."
        }
        return try {
            val result = runShell(command, 5)
            if (result.first == -1) return "UNKNOWN|State query timed out."
            val output = result.second
            if (result.first != 0) return "UNKNOWN|State query failed."
            PrivacyStateParser.parseNumericSetting(option, result.first, output)
        } catch (e: Exception) {
            "UNKNOWN|" + (e.message ?: "State query failed.")
        }
    }

    /**
     * Reads only the per-sensor software privacy toggle that the matching
     * cmd sensor_privacy enable/disable action changes. Global and hardware
     * privacy switches are deliberately not treated as our owned toggle.
     */
    private fun readSensorPrivacyState(option: String): String {
        if (Build.VERSION.SDK_INT < 31) {
            return "UNKNOWN|Camera and microphone sensor privacy requires Android 12 or newer."
        }
        val sensorName = if (option == "camera_privacy") "CAMERA" else "MICROPHONE"
        val sensorId = if (option == "camera_privacy") 2 else 1
        return try {
            val userResult = runShell("am get-current-user", 4)
            if (userResult.first != 0) return "UNKNOWN|Could not identify the active Android user."
            val userId = userResult.second.trim().toIntOrNull()
                ?: return "UNKNOWN|Android returned an unreadable current-user ID."
            val dumpResult = runShell("dumpsys sensor_privacy", 7)
            if (dumpResult.first != 0) return "UNKNOWN|Could not read Android sensor privacy state."
            SensorPrivacyStateParser.parse(dumpResult.second, userId, sensorName, sensorId)
        } catch (e: Exception) {
            "UNKNOWN|" + (e.message ?: "Sensor privacy state query failed.")
        }
    }

    private fun runShell(command: String, timeoutSeconds: Long): Pair<Int, String> {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$command 2>&1"))
        // Drain output while the process is running. Waiting first can deadlock
        // when dumpsys fills the pipe buffer (especially on sensor_privacy).
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 64_000) output.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(500)
            return -1 to "Command timed out."
        }
        reader.join(1_000)
        return process.exitValue() to output.toString().trim()
    }

    override fun setOption(option: String, enabled: Boolean): String {
        val command = when (option) {
            "mobile_data" -> "svc data " + if (enabled) "enable" else "disable"
            "wifi" -> "svc wifi " + if (enabled) "enable" else "disable"
            "bluetooth" -> "svc bluetooth " + if (enabled) "enable" else "disable"
            "location" -> "cmd location set-location-enabled " + enabled + " --user current"
            "nfc" -> "svc nfc " + (if (enabled) "enable" else "disable")
            "camera_privacy" -> {
                if (Build.VERSION.SDK_INT < 31) return "ERROR: Camera sensor privacy needs Android 12 or newer."
                "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " $(am get-current-user) camera"
            }
            "microphone_privacy" -> {
                if (Build.VERSION.SDK_INT < 31) return "ERROR: Microphone sensor privacy needs Android 12 or newer."
                "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " $(am get-current-user) microphone"
            }
            else -> return "ERROR: Unsupported automation option."
        }

        return try {
            val result = runShell(command, 8)
            if (result.first == -1) {
                "ERROR: Android shell command timed out."
            } else if (result.first == 0) {
                "OK: " + option + " request accepted by Android shell." +
                    if (result.second.isNotBlank()) " " + result.second else ""
            } else {
                "ERROR: " + result.second.ifBlank { "Command exited with code " + result.first + "." }
            }
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: "Could not execute Android shell command.")
        }
    }
}
