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

    fun hasRootAccess(): Boolean = runCatching {
        val result = runSu("id", 8) ?: return@runCatching false
        result.exitCode == 0 && Regex("""uid=0\b""").containsMatchIn(result.output)
    }.getOrDefault(false)

    private fun readRootState(option: String): String {
        return try {
        if (option == "mobile_data") {
            val result = runSu("dumpsys telephony.registry", 8)
                ?: return "UNKNOWN|Root telephony query timed out"
            return PrivacyStateParser.parseMobileDataState(result.exitCode, result.output)
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
        return PrivacyStateParser.parseNumericSetting(option, result.exitCode, result.output)
        } catch (t: Throwable) {
            "UNKNOWN|" + (t.message ?: t.javaClass.simpleName)
        }
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
        if (android.os.Build.VERSION.SDK_INT < 31) {
            return "UNKNOWN|Sensor privacy requires Android 12 or newer"
        }
        val userResult = runSu("am get-current-user", 4)
            ?: return "UNKNOWN|Could not identify current Android user"
        val userId = userResult.output.trim().toIntOrNull()
            ?: return "UNKNOWN|Current Android user ID unavailable"
        val sensorName = if (option == "camera_privacy") "CAMERA" else "MICROPHONE"
        val sensorId = if (option == "camera_privacy") 2 else 1
        return SensorPrivacyStateParser.parse(dump, userId, sensorName, sensorId)
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
