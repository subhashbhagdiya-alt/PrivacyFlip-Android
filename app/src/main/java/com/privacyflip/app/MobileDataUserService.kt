package com.privacyflip.app

import java.util.concurrent.TimeUnit

/**
 * Runs under the Shizuku-provided shell/root identity, not the regular app UID.
 * It exposes only fixed Mobile Data operations, never arbitrary shell commands.
 */
class MobileDataUserService : IDataAutomationService.Stub() {
    private data class CommandResult(val exitCode: Int, val output: String)

    /**
     * Drain command output while it runs. Waiting before reading a large dumpsys
     * response can fill the process pipe and deadlock the command until timeout.
     */
    private fun runCommand(command: Array<String>, timeoutSeconds: Long): CommandResult? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
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
            return null
        }
        reader.join(1_000)
        return CommandResult(process.exitValue(), output.toString().trim())
    }

    override fun setMobileDataEnabled(enabled: Boolean): String {
        val desired = if (enabled) "enable" else "disable"
        return try {
            val result = runCommand(
                arrayOf("/system/bin/cmd", "phone", "data", desired),
                8
            ) ?: return "ERROR|timeout|Android telephony command timed out"

            val detail = result.output.ifBlank { "no command output" }.take(250)
            if (result.exitCode == 0) {
                "OK|exit=0|requested mobile data " + desired + "|" + detail
            } else {
                "ERROR|exit=" + result.exitCode + "|" + detail
            }
        } catch (t: Throwable) {
            "ERROR|" + t.javaClass.simpleName + "|" +
                (t.message ?: "command failed").take(220)
        }
    }

    /**
     * Conservative read-back: report a state only when the telephony dump has one
     * unambiguous mIsDataEnabled value. Dual-SIM disagreement is UNKNOWN, never guessed.
     */
    override fun getMobileDataState(): String {
        return try {
            val result = runCommand(
                arrayOf("/system/bin/sh", "-c", "dumpsys telephony.registry"),
                8
            ) ?: return "UNKNOWN|timeout|Telephony state read timed out"

            if (result.exitCode != 0) {
                return "UNKNOWN|exit=" + result.exitCode + "|" + result.output.take(180)
            }
            val states = Regex("mIsDataEnabled\\s*=\\s*(true|false)", RegexOption.IGNORE_CASE)
                .findAll(result.output)
                .map { it.groupValues[1].equals("true", ignoreCase = true) }
                .toSet()
            when (states.size) {
                0 -> "UNKNOWN|missing|No mIsDataEnabled field found in telephony dump"
                1 -> if (states.first()) "ENABLED" else "DISABLED"
                else -> "UNKNOWN|ambiguous|SIM data states disagree; refusing to guess"
            }
        } catch (t: Throwable) {
            "UNKNOWN|" + t.javaClass.simpleName + "|" +
                (t.message ?: "state read failed").take(180)
        }
    }
}
