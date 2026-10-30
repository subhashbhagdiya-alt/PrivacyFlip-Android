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
            else -> return "UNKNOWN|Unsupported state query."
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "UNKNOWN|State query timed out."
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) return "UNKNOWN|State query failed."
            val value = output.toIntOrNull() ?: return "UNKNOWN|Android returned an unreadable state."
            val enabled = if (option == "location") value != 0 else value == 1
            if (enabled) "ENABLED" else "DISABLED"
        } catch (e: Exception) {
            "UNKNOWN|" + (e.message ?: "State query failed.")
        }
    }

    private fun readSensorPrivacyState(option: String): String {
        if (Build.VERSION.SDK_INT < 31) {
            return "UNKNOWN|Camera and microphone sensor privacy requires Android 12 or newer."
        }
        return try {
            val userProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am get-current-user"))
            if (!userProcess.waitFor(4, TimeUnit.SECONDS)) {
                userProcess.destroyForcibly()
                return "UNKNOWN|Could not identify the active Android user."
            }
            val userId = userProcess.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull()
                ?: return "UNKNOWN|Android returned an unreadable current-user ID."

            val dump = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys sensor_privacy"))
            if (!dump.waitFor(6, TimeUnit.SECONDS)) {
                dump.destroyForcibly()
                return "UNKNOWN|Sensor privacy state query timed out."
            }
            val output = dump.inputStream.bufferedReader().use { it.readText() }
            if (dump.exitValue() != 0 || !output.contains("SENSOR PRIVACY MANAGER STATE", ignoreCase = true)) {
                return "UNKNOWN|Sensor privacy dump is unavailable on this Android build."
            }

            val sections = Regex("(?s)users\\s*\\{(.*?)(?=\\n\\s*users\\s*\\{|$)")
                .findAll(output).map { it.groupValues[1] }
            val section = sections.firstOrNull {
                Regex("\\buser_id\\s*:\\s*" + userId + "\\b").containsMatchIn(it)
            } ?: return "UNKNOWN|Current user was not present in the sensor privacy dump."

            val beforeIndividual = section.substringBefore("individual_enabled_sensor")
            val globalState = Regex("\\bis_enabled\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                .find(beforeIndividual)?.groupValues?.get(1)
            if (globalState.equals("true", ignoreCase = true)) return "ENABLED"

            val sensorId = if (option == "camera_privacy") 2 else 1
            val blocks = Regex("(?s)individual_enabled_sensor\\s*\\{([^}]*)\\}")
                .findAll(section).map { it.groupValues[1] }
            val sensorBlock = blocks.firstOrNull { block ->
                Regex("\\bsensor\\s*:\\s*" + sensorId + "\\b").containsMatchIn(block)
            } ?: return "UNKNOWN|No explicit per-sensor state was exposed; refusing to guess."
            val enabled = Regex("\\bis_enabled\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                .find(sensorBlock)?.groupValues?.get(1)
                ?: return "UNKNOWN|Per-sensor state was not explicit."
            if (enabled.equals("true", ignoreCase = true)) "ENABLED" else "DISABLED"
        } catch (e: Exception) {
            "UNKNOWN|" + (e.message ?: "Sensor privacy state query failed.")
        }
    }

    override fun setOption(option: String, enabled: Boolean): String {
        val command = when (option) {
            "mobile_data" -> "svc data " + if (enabled) "enable" else "disable"
            "wifi" -> "svc wifi " + if (enabled) "enable" else "disable"
            "bluetooth" -> "svc bluetooth " + if (enabled) "enable" else "disable"
            "location" -> "cmd location set-location-enabled " + enabled + " --user current"
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
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val completed = process.waitFor(8, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                "ERROR: Android shell command timed out."
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
                if (process.exitValue() == 0) {
                    "OK: " + option + " request accepted by Android shell." + if (output.isNotBlank()) " " + output else ""
                } else {
                    "ERROR: " + (error.ifBlank { output.ifBlank { "Command exited with code " + process.exitValue() + "." } })
                }
            }
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: "Could not execute Android shell command.")
        }
    }
}
