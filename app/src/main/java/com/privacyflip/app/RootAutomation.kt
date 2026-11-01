package com.privacyflip.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Allowlisted optional root backend. Null means root is unavailable; callers may use Shizuku. */
object RootAutomation {
    private data class CommandResult(val exitCode: Int, val output: String)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun readState(context: Context, option: String, callback: (String?) -> Unit) {
        worker.execute {
            val result = if (!hasRootAccess()) null else readRootState(option)
            main.post { callback(result) }
        }
    }

    fun apply(context: Context, option: String, enabled: Boolean, callback: (String?) -> Unit) {
        worker.execute {
            val result = if (!hasRootAccess()) null else applyRootState(option, enabled)
            main.post { callback(result) }
        }
    }

    private fun hasRootAccess(): Boolean {
        val result = runSu("id", 8) ?: return false
        return result.exitCode == 0 && Regex("""uid=0\b""").containsMatchIn(result.output)
    }

    private fun readRootState(option: String): String = try {
        if (option == "mobile_data") {
            val result = runSu("dumpsys telephony.registry", 8)
                ?: return "UNKNOWN|Root telephony query timed out"
            if (result.exitCode != 0) return "UNKNOWN|Root telephony query failed: " + result.output.take(140)
            val states = Regex("mIsDataEnabled\\s*=\\s*(true|false)", RegexOption.IGNORE_CASE)
                .findAll(result.output).map { it.groupValues[1].equals("true", true) }.toSet()
            return when (states.size) {
                1 -> if (states.first()) "ENABLED" else "DISABLED"
                0 -> "UNKNOWN|No unambiguous mobile-data state found"
                else -> "UNKNOWN|SIM data states disagree; refusing to guess"
            }
        }
        if (option == "camera_privacy" || option == "microphone_privacy") {
            val result = runSu("dumpsys sensor_privacy", 8)
                ?: return "UNKNOWN|Root sensor privacy query timed out"
            if (result.exitCode != 0) return "UNKNOWN|Sensor privacy query failed"
            return parseSensorPrivacy(result.output, option)
        }
        val command = when (option) {
            "wifi" -> "settings get global wifi_on"
            "bluetooth" -> "settings get global bluetooth_on"
            "location" -> "settings get secure location_mode"
            "nfc" -> "settings get global nfc_on"
            else -> return "UNKNOWN|Unsupported root state query"
        }
        val result = runSu(command, 5) ?: return "UNKNOWN|Root state query timed out"
        if (result.exitCode != 0) return "UNKNOWN|Root state query failed: " + result.output.take(120)
        val value = result.output.trim().toIntOrNull() ?: return "UNKNOWN|Android returned an unreadable state"
        val enabled = if (option == "location") value != 0 else value == 1
        if (enabled) "ENABLED" else "DISABLED"
    } catch (t: Throwable) {
        "UNKNOWN|" + (t.message ?: t.javaClass.simpleName)
    }

    private fun applyRootState(option: String, enabled: Boolean): String {
        val command = when (option) {
            "mobile_data" -> "/system/bin/cmd phone data " + if (enabled) "enable" else "disable"
            "wifi" -> "svc wifi " + if (enabled) "enable" else "disable"
            "bluetooth" -> "svc bluetooth " + if (enabled) "enable" else "disable"
            "location" -> "cmd location set-location-enabled " + enabled + " --user current"
            "nfc" -> "svc nfc " + if (enabled) "enable" else "disable"
            "camera_privacy" -> {
                if (android.os.Build.VERSION.SDK_INT < 31) return "ERROR|Camera sensor privacy requires Android 12+"
                "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " $(am get-current-user) camera"
            }
            "microphone_privacy" -> {
                if (android.os.Build.VERSION.SDK_INT < 31) return "ERROR|Microphone sensor privacy requires Android 12+"
                "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " $(am get-current-user) microphone"
            }
            else -> return "ERROR|Unsupported root automation option"
        }
        return try {
            val result = runSu(command, 10) ?: return "ERROR|Root command timed out"
            if (result.exitCode == 0) "OK|Root command accepted" + if (result.output.isBlank()) "" else ": " + result.output.take(160)
            else "ERROR|Root command failed: " + result.output.take(180)
        } catch (t: Throwable) {
            "ERROR|" + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun parseSensorPrivacy(dump: String, option: String): String {
        val sensorName = if (option == "camera_privacy") "CAMERA" else "MICROPHONE"
        val sensorId = if (option == "camera_privacy") 2 else 1
        val userResult = runSu("am get-current-user", 4)
            ?: return "UNKNOWN|Could not identify current Android user"
        val userId = userResult.output.trim().toIntOrNull()
            ?: return "UNKNOWN|Current Android user ID unavailable"
        val userBlock = findNamedBlocks(dump, "users").firstOrNull { block ->
            Regex("""(?m)^\s*user_id\s*:\s*$userId\b""").containsMatchIn(block)
        } ?: return "UNKNOWN|Current user's sensor privacy state was not found"
        val legacyBlocks = findNamedBlocks(userBlock, "individual_enabled_sensor")
        val legacySensor = legacyBlocks.firstOrNull { sensorMatches(it, sensorName, sensorId) }
        if (legacySensor != null) {
            val value = Regex("""(?m)^\s*is_enabled\s*:\s*(true|false)\b""", RegexOption.IGNORE_CASE)
                .find(legacySensor)?.groupValues?.get(1)
                ?: return "UNKNOWN|Sensor privacy state was not explicit"
            return if (value.equals("true", true)) "ENABLED" else "DISABLED"
        }
        if (legacyBlocks.isNotEmpty() || userBlock.contains("individual_enabled_sensor")) return "DISABLED"
        val sensorBlock = findNamedBlocks(userBlock, "sensors")
            .firstOrNull { sensorMatches(it, sensorName, sensorId) } ?: return "DISABLED"
        val toggles = findNamedBlocks(sensorBlock, "toggles").filter {
            Regex("""(?m)^\s*toggle_type\s*:\s*(?:SOFTWARE|1)\b""").containsMatchIn(it)
        }
        val states = toggles.mapNotNull {
            Regex("""(?m)^\s*state_type\s*:\s*([A-Z_]+)\b""").find(it)?.groupValues?.get(1)
        }
        return when {
            states.any { it == "ENABLED" || it == "ENABLED_EXCEPT_ALLOWLISTED_APPS" } -> "ENABLED"
            states.any { it == "DISABLED" } || toggles.isEmpty() -> "DISABLED"
            else -> "UNKNOWN|Could not interpret sensor privacy state"
        }
    }

    private fun sensorMatches(block: String, sensorName: String, sensorId: Int): Boolean {
        val value = Regex("""(?m)^\s*sensor\s*:\s*([^\r\n]+)""").find(block)?.groupValues?.get(1)?.trim()
            ?: return false
        return value.startsWith(sensorName, ignoreCase = true) ||
            Regex("""^$sensorId(?:\b|\s*\()""").containsMatchIn(value)
    }

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
                    '}' -> { depth--; if (depth == 0) { end = index; break } }
                }
            }
            if (end < 0) break
            blocks.add(source.substring(open + 1, end))
            cursor = end + 1
        }
        return blocks
    }

    private fun runSu(command: String, timeoutSeconds: Long): CommandResult? {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (output.length < 64_000) output.append(line).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(500)
            return null
        }
        reader.join(1_000)
        return CommandResult(process.exitValue(), output.toString().trim())
    }
}
