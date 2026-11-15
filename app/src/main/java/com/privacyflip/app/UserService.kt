package com.privacyflip.app

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Instantiated by Shizuku inside its own shell-privileged process (not inside the app process).
 * It never runs free text: the command comes from the fixed [FeatureCommands] table.
 */
class UserService : IUserService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun apply(featureKey: String, on: Boolean): String {
        val feature = Feature.values().firstOrNull { it.key == featureKey }
            ?: return "EXIT:-1\nunknown feature"
        return try {
            val process = ProcessBuilder("sh", "-c", FeatureCommands.command(feature, on))
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "EXIT:-1\ntimeout"
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            "EXIT:${process.exitValue()}\n$output"
        } catch (e: Exception) {
            "EXIT:-1\n${e.javaClass.simpleName}"
        }
    }
}
