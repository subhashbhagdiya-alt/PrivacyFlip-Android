package com.privacyflip.app

import java.util.concurrent.TimeUnit

/**
 * Runs under the Shizuku-provided shell/root identity, not the regular app UID.
 * It exposes only fixed Mobile Data operations, never arbitrary shell commands.
 */
class MobileDataUserService : IDataAutomationService.Stub() {
    override fun setMobileDataEnabled(enabled: Boolean): String {
        val desired = if (enabled) "enable" else "disable"
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/cmd", "phone", "data", desired)
            )
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "ERROR|timeout|Android telephony command timed out"
            } else {
                val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("").trim()
                val error = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("").trim()
                val detail = (output.ifBlank { error.ifBlank { "no command output" } }).replace("\\n", " ").take(250)
                if (process.exitValue() == 0) {
                    "OK|exit=0|requested mobile data " + desired + "|" + detail
                } else {
                    "ERROR|exit=" + process.exitValue() + "|" + detail
                }
            }
        } catch (t: Throwable) {
            "ERROR|" + t.javaClass.simpleName + "|" + (t.message ?: "command failed").take(220)
        }
    }

    /**
     * Conservative read-back: report a state only when the telephony dump has one
     * unambiguous mIsDataEnabled value. Dual-SIM disagreement is UNKNOWN, never guessed.
     */
    override fun getMobileDataState(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "dumpsys telephony.registry")
            )
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "UNKNOWN|timeout|Telephony state read timed out"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                return "UNKNOWN|exit=" + process.exitValue() + "|" + error.take(180)
            }
            val states = Regex("mIsDataEnabled\\s*=\\s*(true|false)", RegexOption.IGNORE_CASE)
                .findAll(output)
                .map { it.groupValues[1].equals("true", ignoreCase = true) }
                .toSet()
            when (states.size) {
                0 -> "UNKNOWN|missing|No mIsDataEnabled field found in telephony dump"
                1 -> if (states.first()) "ENABLED" else "DISABLED"
                else -> "UNKNOWN|ambiguous|SIM data states disagree; refusing to guess"
            }
        } catch (t: Throwable) {
            "UNKNOWN|" + t.javaClass.simpleName + "|" + (t.message ?: "state read failed").take(180)
        }
    }
}
