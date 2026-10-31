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
            val result = runShell(command, 5)
            if (result.first == -1) return "UNKNOWN|State query timed out."
            val output = result.second
            if (result.first != 0) return "UNKNOWN|State query failed."
            val value = output.toIntOrNull() ?: return "UNKNOWN|Android returned an unreadable state."
            val enabled = if (option == "location") value != 0 else value == 1
            if (enabled) "ENABLED" else "DISABLED"
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
            val dump = dumpResult.second
            if (dump.contains("Permission Denial", ignoreCase = true) ||
                dump.contains("Can't find service", ignoreCase = true) ||
                !dump.contains("SENSOR PRIVACY MANAGER STATE", ignoreCase = true)) {
                return "UNKNOWN|Sensor privacy dump is unavailable on this Android build."
            }

            val userBlock = findNamedBlocks(dump, "users").firstOrNull { block ->
                Regex("""(?m)^\s*user_id\s*:\s*$userId\b""").containsMatchIn(block)
            } ?: return "UNKNOWN|Current user's sensor privacy state was not found."

            // Android 12/13 expose individual_enabled_sensor entries. No entry
            // means the per-sensor software toggle is OFF by default.
            val legacyBlocks = findNamedBlocks(userBlock, "individual_enabled_sensor")
            val legacySensor = legacyBlocks.firstOrNull { sensorMatches(it, sensorName, sensorId) }
            if (legacySensor != null) {
                val enabled = Regex("""(?m)^\s*is_enabled\s*:\s*(true|false)\b""", RegexOption.IGNORE_CASE)
                    .find(legacySensor)?.groupValues?.get(1)
                    ?: return "UNKNOWN|Per-sensor state was not explicit."
                return if (enabled.equals("true", ignoreCase = true)) "ENABLED" else "DISABLED"
            }
            if (legacyBlocks.isNotEmpty() || userBlock.contains("individual_enabled_sensor")) {
                return "DISABLED"
            }

            // Newer Android releases group states under sensors -> toggles.
            val sensorBlock = findNamedBlocks(userBlock, "sensors")
                .firstOrNull { sensorMatches(it, sensorName, sensorId) }
                ?: return "DISABLED"
            val softwareToggles = findNamedBlocks(sensorBlock, "toggles").filter { toggle ->
                Regex("""(?m)^\s*toggle_type\s*:\s*(?:SOFTWARE|1)\b""").containsMatchIn(toggle)
            }
            if (softwareToggles.isEmpty()) return "DISABLED"

            val states = softwareToggles.mapNotNull { toggle ->
                Regex("""(?m)^\s*state_type\s*:\s*([A-Z_]+)\b""")
                    .find(toggle)?.groupValues?.get(1)
            }
            when {
                states.any { it == "ENABLED" || it == "ENABLED_EXCEPT_ALLOWLISTED_APPS" } -> "ENABLED"
                states.any { it == "DISABLED" } -> "DISABLED"
                else -> "UNKNOWN|Could not interpret the software sensor privacy state."
            }
        } catch (e: Exception) {
            "UNKNOWN|" + (e.message ?: "Sensor privacy state query failed.")
        }
    }

    private fun sensorMatches(block: String, sensorName: String, sensorId: Int): Boolean {
        val value = Regex("""(?m)^\s*sensor\s*:\s*([^\r\n]+)""")
            .find(block)?.groupValues?.get(1)?.trim() ?: return false
        return value.startsWith(sensorName, ignoreCase = true) ||
            Regex("""^$sensorId(?:\b|\s*\()""").containsMatchIn(value)
    }

    /** Extract balanced text-proto blocks, including nested child blocks. */
    private fun findNamedBlocks(source: String, field: String): List<String> {
        val pattern = Regex("(?m)^\\s*" + Regex.escape(field) + "\\s*\\{")
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (cursor < source.length) {
            val match = pattern.find(source, cursor) ?: break
            val open = source.indexOf('{', match.range.first)
            var depth = 0
            var end = -1
            for (index in open until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            blocks.add(source.substring(open + 1, end))
            cursor = end + 1
        }
        return blocks
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
