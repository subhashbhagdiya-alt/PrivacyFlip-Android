package com.privacyflip.app

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the paired lock/unlock automation. Each step reads the real state, saves the original,
 * applies the target, verifies it and logs the true result. Nothing is reported as success
 * unless it was verified (or Android cannot report that state, which is logged as unverified).
 * The last result of every feature is also stored so the Home screen can show it.
 */
internal object LockUnlockEngine {
    private const val PREFS = "privacy_flip_local"
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private var lastScheduledLocked: Boolean? = null
    private var lastScheduledAt = 0L

    /** Entry point for raw screen events coming from the monitor service. */
    fun onEvent(context: Context, event: String) {
        when (event) {
            "Screen turns off" -> {
                note(context, "screen turned off")
                schedule(context, true)
            }
            "Phone unlocked" -> {
                note(context, "phone unlocked")
                schedule(context, false)
            }
            "Screen turns on" -> {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                // A phone with no lock screen never sends "unlocked": screen on is the unlock.
                if (keyguard != null && !keyguard.isKeyguardLocked) schedule(context, false)
            }
        }
    }

    /** Runs the automation right now, ignoring the screen state. Used by the Test buttons. */
    fun testNow(context: Context, locked: Boolean) {
        val app = context.applicationContext
        scheduler.execute(Runnable { execute(app, locked, true) })
    }

    @Synchronized
    private fun schedule(context: Context, locked: Boolean) {
        val app = context.applicationContext
        val prefs = prefs(app)
        if (prefs.getBoolean("masterPaused", false)) return
        if (Feature.values().none { prefs.getBoolean(it.enabledKey, false) }) return
        // Ignore the same event repeated within two seconds.
        val now = SystemClock.elapsedRealtime()
        if (lastScheduledLocked == locked && now - lastScheduledAt < 2000L) return
        lastScheduledLocked = locked
        lastScheduledAt = now
        val delaySec = prefs.getInt(if (locked) "lockDelaySec" else "unlockDelaySec", 0)
        // A newer event replaces an older one that has not started yet (prevents races).
        pending?.cancel(false)
        pending = scheduler.schedule(Runnable { execute(app, locked, false) }, delaySec * 1000L, TimeUnit.MILLISECONDS)
    }

    private fun execute(context: Context, locked: Boolean, test: Boolean) {
        try {
            perform(context, locked, test)
        } catch (e: Exception) {
            log(context, "Lock/unlock automation error: ${e.javaClass.simpleName}")
        }
    }

    private fun perform(context: Context, locked: Boolean, test: Boolean) {
        val prefs = prefs(context)
        if (!test && prefs.getBoolean("masterPaused", false)) return
        val backend = Backends.current(context, true)
        val restore = prefs.getBoolean("restoreOriginal", false)
        val label = if (test) "Test" else if (locked) "Locked" else "Unlocked"
        log(context, "$label: running ${if (locked) "lock" else "unlock"} automation [${backend.name}]")
        var count = 0
        for (feature in Feature.values()) {
            if (!prefs.getBoolean(feature.enabledKey, false)) continue
            count++
            applyFeature(context, prefs, backend, feature, locked, restore)
        }
        if (count == 0) log(context, "$label: no feature is switched on")
    }

    private fun applyFeature(
        context: Context, prefs: SharedPreferences, backend: AutomationBackend,
        feature: Feature, locked: Boolean, restore: Boolean
    ) {
        if (!backend.supports(feature)) {
            report(context, prefs, feature, "skipped: ${backend.name} cannot change it (connect Shizuku or Root)")
            return
        }
        val current = SystemState.read(context, feature)
        // Save the original once and never overwrite it while a recovery is still pending.
        if (locked && !prefs.contains(feature.originalKey) && current != null) {
            prefs.edit().putBoolean(feature.originalKey, current).apply()
            log(context, "${feature.title}: original state saved (${stateWord(feature, current)})")
        }
        val saved: Boolean? =
            if (prefs.contains(feature.originalKey)) prefs.getBoolean(feature.originalKey, feature.unlockTarget) else null
        val target = when {
            locked -> feature.lockTarget
            restore && saved != null -> saved
            else -> feature.unlockTarget
        }
        if (current != null && current == target) {
            report(context, prefs, feature, "already ${stateWord(feature, target)}")
            finish(prefs, feature, locked)
            return
        }
        val error = backend.apply(feature, target)
        if (error != null) {
            prefs.edit().putBoolean(feature.pendingKey, true).apply()
            report(context, prefs, feature, "FAILED: $error (recovery pending)")
            return
        }
        if (SystemState.read(context, feature) == null) {
            report(context, prefs, feature, "applied ${stateWord(feature, target)} (unverified: Android cannot report this state)")
            finish(prefs, feature, locked)
            return
        }
        if (waitFor(context, feature, target)) {
            report(context, prefs, feature, "verified ${stateWord(feature, target)}")
            finish(prefs, feature, locked)
        } else {
            prefs.edit().putBoolean(feature.pendingKey, true).apply()
            report(context, prefs, feature, "NOT verified after the command (recovery pending)")
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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /** Logs one result and keeps it as the feature's latest result for the Home screen. */
    private fun report(context: Context, prefs: SharedPreferences, feature: Feature, message: String) {
        log(context, "${feature.title}: $message")
        prefs.edit().putString("pf_result_${feature.key}", "$message · ${time()}").apply()
    }

    private fun note(context: Context, what: String) {
        prefs(context).edit().putString("lastScreenEvent", "$what at ${time()}").apply()
    }

    private fun log(context: Context, message: String) = AutomationEventProcessor.appendLog(context, message)
}
