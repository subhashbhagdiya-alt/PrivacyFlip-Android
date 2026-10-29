package com.privacyflip.app

import java.util.concurrent.TimeUnit

/**
 * Runs under the Shizuku-provided shell/root identity, not the regular app UID.
 * It exposes only a fixed Mobile Data action, never arbitrary shell commands.
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
                val detail = (output.ifBlank { error.ifBlank { "no command output" } }).replace("\n", " ").take(250)
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
}
