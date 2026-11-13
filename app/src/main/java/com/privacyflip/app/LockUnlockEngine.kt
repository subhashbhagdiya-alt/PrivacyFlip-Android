package com.privacyflip.app

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the paired lock/unlock automation. Each step reads the real state, saves the original,
 * applies the target, verifies it and logs the true result. Nothing is reported as success
 * unless it was verified (or Android cannot report that state, which is logged as unverified).
 */
internal object LockUnlockEngine {
    private const val PREFS = "privacy_flip_local"
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    /** Entry point for raw screen events coming from the monitor service. */
    fun onEvent(context: Context, event: String) {
        when (event) {
            "Screen turns off" -> schedule(context, true)
            "Phone unlocked" -> schedule(context, false)
            "Screen turns on" -> {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                // A phone with no lock screen never sends "unlocked": screen on is the unlock.
                if (keyguard != null && !keyguard.isKeyguardLocked) schedule(context, false)
            }
        }
    }

    @Synchronized
    private fun schedule(context: Context, locked: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("masterPaused", false)) return
        if (Feature.values().none { prefs.getBoolean(it.enabledKey, false) }) return
        val delaySec = prefs.getInt(if (locked) "lockDelaySec" else "unlockDelaySec", 0)
        // A newer event replaces an older one that has not started yet (prevents races).
        pending?.cancel(false)
        pending = scheduler.schedule(Runnable { execute(app, locked) }, delaySec * 1000L, TimeUnit.MILLISECONDS)
    }

    private fun execute(context: Context, locked: Boolean) {
        try {
            run(context, locked)
        } catch (e: Exception) {
            log(context, "Lock/unlock automation error: ${e.javaClass.simpleName}")
        }
    }

    private fun run(context: Context, locked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("masterPaused", false)) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (locked && power.isInteractive) {
            log(context, "Lock skipped: the screen is on again")
            return
        }
        if (!locked && !power.isInteractive) {
            log(context, "Unlock skipped: the screen is off")
            return
        }
        val phase = if (locked) "LOCKED" else "UNLOCKED"
        val anyPending = Feature.values().any { prefs.getBoolean(it.pendingKey, false) }
        if (prefs.getString("lastPhase", "") == phase && !anyPending) return

        val backend = Backends.current(context, true)
        val restore = prefs.getBoolean("restoreOriginal", false)
        if (locked) {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val keyState = if (keyguard?.isKeyguardLocked == true) "keyguard locked" else "keyguard not locked yet"
            log(context, "Screen off ($keyState): running lock automation [${backend.name}]")
        } else {
            log(context, "Phone unlocked: running unlock automation [${backend.name}]")
        }
        for (feature in Feature.values()) {
            if (!prefs.getBoolean(feature.enabledKey, false)) continue
            applyFeature(context, prefs, backend, feature, locked, restore)
        }
        prefs.edit().putString("lastPhase", phase).apply()
    }

    private fun applyFeature(
        context: Context, prefs: SharedPreferences, backend: AutomationBackend,
        feature: Feature, locked: Boolean, restore: Boolean
    ) {
        val name = feature.title
        if (!backend.supports(feature)) {
            log(context, "$name skipped: ${backend.name} mode cannot change it (needs Root or Shizuku)")
            return
        }
        val current = SystemState.read(context, feature)
        // Save the original once and never overwrite it while a recovery is still pending.
        if (locked && !prefs.contains(feature.originalKey) && current != null) {
            prefs.edit().putBoolean(feature.originalKey, current).apply()
            log(context, "$name original state saved: ${stateWord(feature, current)}")
        }
        val saved: Boolean? =
            if (prefs.contains(feature.originalKey)) prefs.getBoolean(feature.originalKey, feature.unlockTarget) else null
        val target = when {
            locked -> feature.lockTarget
            restore && saved != null -> saved
            else -> feature.unlockTarget
        }
        if (current != null && current == target) {
            log(context, "$name already ${stateWord(feature, target)}")
            finish(prefs, feature, locked)
            return
        }
        val error = backend.apply(feature, target)
        if (error != null) {
            prefs.edit().putBoolean(feature.pendingKey, true).apply()
            log(context, "$name FAILED: $error (recovery pending)")
            return
        }
        if (SystemState.read(context, feature) == null) {
            log(context, "$name applied: ${stateWord(feature, target)} (unverified: Android cannot report this state)")
            finish(prefs, feature, locked)
            return
        }
        if (waitFor(context, feature, target)) {
            log(context, "$name verified ${stateWord(feature, target)}")
            finish(prefs, feature, locked)
        } else {
            prefs.edit().putBoolean(feature.pendingKey, true).apply()
            log(context, "$name NOT verified after the command (recovery pending)")
        }
    }

    private fun finish(prefs: SharedPreferences, feature: Feature, locked: Boolean) {
        val editor = prefs.edit().remove(feature.pendingKey)
        if (!locked) editor.remove(feature.originalKey)
        editor.apply()
    }

    private fun waitFor(context: Context, feature: Feature, target: Boolean): Boolean {
        repeat(8) {
            if (SystemState.read(context, feature) == target) return true
            Thread.sleep(500L)
        }
        return SystemState.read(context, feature) == target
    }

    private fun stateWord(feature: Feature, on: Boolean): String =
        if (feature.lockTarget) (if (on) "protected" else "unprotected") else (if (on) "ON" else "OFF")

    private fun log(context: Context, message: String) = AutomationEventProcessor.appendLog(context, message)
}
