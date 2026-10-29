package com.privacyflip.app

import android.os.Build
import com.privacyflip.app.IPrivacyFlipShell
import java.util.concurrent.TimeUnit

/**
 * This binder is instantiated by Shizuku as the shell (ADB) or root user.
 * It accepts only a small allowlist of fixed actions; arbitrary shell commands
 * are never accepted from the app process.
 */
class FlipShellUserService : IPrivacyFlipShell.Stub() {
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