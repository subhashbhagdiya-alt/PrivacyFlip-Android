package com.privacyflip.app

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Instantiated by Shizuku inside its own shell-privileged process (not inside the app process).
 * It never runs free text: every command is either built from the fixed [FeatureCommands] table
 * or is one of the fixed maintenance tasks below.
 */
class UserService : IUserService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun apply(featureKey: String, on: Boolean): String {
        val feature = Feature.values().firstOrNull { it.key == featureKey }
            ?: return "EXIT:-1\nunknown feature"
        val result = shell(FeatureCommands.command(feature, on))
        return "EXIT:${result.first}\n${result.second}"
    }

    override fun keepAlive(): String {
        val steps = listOf(
            "background app op" to "cmd appops set $PACKAGE RUN_IN_BACKGROUND allow",
            "background-any app op" to "cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow",
            "battery whitelist" to "cmd deviceidle whitelist +$PACKAGE",
            "active standby bucket" to "am set-standby-bucket $PACKAGE active"
        )
        return steps.joinToString("\n") { (label, command) ->
            val result = shell(command)
            if (result.first == 0) "OK $label" else "FAILED $label (${result.second.take(60)})"
        }
    }

    override fun enableAccessibility(): String {
        val component = "$PACKAGE/$PACKAGE.PrivacyFlipAccessibilityService"
        val current = shell("settings get secure enabled_accessibility_services").second.trim()
        val merged = when {
            current.isEmpty() || current == "null" -> component
            current.split(":").contains(component) -> current
            else -> "$current:$component"
        }
        val put = shell("settings put secure enabled_accessibility_services '$merged'")
        val on = shell("settings put secure accessibility_enabled 1")
        return if (put.first == 0 && on.first == 0) "OK accessibility service" else "FAILED accessibility service (${put.second.take(60)})"
    }

    private fun shell(command: String): Pair<Int, String> = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(6, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Pair(-1, "timeout")
        } else {
            Pair(process.exitValue(), process.inputStream.bufferedReader().readText().trim())
        }
    } catch (e: Exception) {
        Pair(-1, e.javaClass.simpleName)
    }

    private companion object {
        const val PACKAGE = "com.privacyflip.app"
    }
}
