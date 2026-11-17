package com.privacyflip.app

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the paired lock/unlock automation.
 *
 * The rule: only undo what Privacy Flip itself changed. On lock, a setting that is already in its
 * locked state on the phone (for example Wi-Fi the user keeps off) is left alone and is not forced
 * back on at unlock. Each step reads the real state, applies the target, verifies it and logs the
 * true result. Nothing is reported as success unless it was verified (or Android cannot report
 * that state, which is logged as unverified).
 */
internal object LockUnlockEngine {
    private const val PREFS = "privacy_flip_local"
    private const val BT_DEFERRED = "pf_bt_deferred"
    private const val BRIGHTNESS_VALUE = "pf_orig_brightness_value"
    private const val BRIGHTNESS_AUTO = "pf_orig_brightness_auto"

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var btCallback: AudioManager.AudioPlaybackCallback? = null
    private var btPoll: ScheduledFuture<*>? = null

    private fun touchedKey(feature: Feature) = "pf_touched_${feature.key}"

    /** Entry point for raw screen events coming from the monitor service or the accessibility service. */
    fun onEvent(context: Context, event: String) {
        if (event == "Screen turns off" || event == "Screen turns on" || event == "Phone unlocked") {
            // Remembered for the system report: proves the app really receives these events.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong("seen_$event", System.currentTimeMillis()).apply()
        }
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

    /** Runs a lock or unlock pass right now (Test buttons). Skips screen-state checks, same steps otherwise. */
    fun runNow(context: Context, locked: Boolean) {
        val app = context.applicationContext
        holdAwake(app, 30_000L)
        scheduler.execute { execute(app, locked, true) }
    }

    @Synchronized
    private fun schedule(context: Context, locked: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("masterPaused", false)) return
        if (Feature.values().none { prefs.getBoolean(it.enabledKey, false) }) return
        val delaySec = prefs.getInt(if (locked) "lockDelaySec" else "unlockDelaySec", 0)
        // The screen is about to be off: without this the CPU can sleep before the pass finishes.
        holdAwake(app, delaySec * 1000L + 30_000L)
        // A newer event replaces an older one that has not started yet (prevents races).
        pending?.cancel(false)
        pending = scheduler.schedule(Runnable { execute(app, locked, false) }, delaySec * 1000L, TimeUnit.MILLISECONDS)
    }

    /** Keeps the CPU awake for [ms]. The timeout releases it, so it can never be left on. */
    @Synchronized
    private fun holdAwake(context: Context, ms: Long) {
        try {
            val lock = wakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivacyFlip:automation")
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            lock.acquire(ms)
        } catch (_: Exception) {
            // Running without the wake lock is still better than not running.
        }
    }

    private fun execute(context: Context, locked: Boolean, force: Boolean) {
        try {
            run(context, locked, force)
        } catch (e: Exception) {
            log(context, "Lock/unlock automation error: ${e.javaClass.simpleName}")
        }
    }

    private fun run(context: Context, locked: Boolean, force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("masterPaused", false)) {
            if (force) log(context, "Test skipped: protection is paused")
            return
        }
        if (force && Feature.values().none { prefs.getBoolean(it.enabledKey, false) }) {
            log(context, "Test skipped: no feature is switched on")
            return
        }
        val phase = if (locked) "LOCKED" else "UNLOCKED"
        if (!force) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (locked && power.isInteractive) {
                // The screen-off broadcast can arrive a moment before the screen is fully off.
                Thread.sleep(400L)
                if (power.isInteractive) {
                    log(context, "Lock skipped: the screen is on again")
                    return
                }
            }
            if (!locked && !power.isInteractive) {
                log(context, "Unlock skipped: the screen is off")
                return
            }
            val anyPending = Feature.values().any { prefs.getBoolean(it.pendingKey, false) }
            if (prefs.getString("lastPhase", "") == phase && !anyPending) return
        }

        val backend = Backends.current(context, true)
        if (force) {
            log(context, "${if (locked) "Test lock" else "Test unlock"}: started [${backend.name}]")
        } else if (locked) {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val keyState = if (keyguard?.isKeyguardLocked == true) "keyguard locked" else "keyguard not locked yet"
            log(context, "Screen off ($keyState): running lock automation [${backend.name}]")
        } else {
            log(context, "Phone unlocked: running unlock automation [${backend.name}]")
        }
        if (!locked) {
            // Unlocking cancels a Bluetooth turn-off that was waiting for the music to stop.
            prefs.edit().remove(BT_DEFERRED).apply()
            stopBluetoothWatch(context)
        }
        // Wi-Fi goes off last and comes back first: Shizuku started over Wireless debugging needs Wi-Fi.
        val ordered = Feature.values().sortedBy { if (it == Feature.WIFI) (if (locked) 1 else -1) else 0 }
        for (feature in ordered) {
            if (!prefs.getBoolean(feature.enabledKey, false)) continue
            if (feature == Feature.BRIGHTNESS) applyBrightness(context, prefs, backend, locked)
            else applyFeature(context, prefs, backend, feature, locked)
        }
        if (!force) prefs.edit().putString("lastPhase", phase).apply()
    }

    private fun applyFeature(
        context: Context, prefs: SharedPreferences, backend: AutomationBackend,
        feature: Feature, locked: Boolean
    ) {
        val name = feature.title
        if (!backend.supports(feature)) {
            log(context, "$name skipped: ${backend.name} mode cannot change it (needs Root or Shizuku)")
            return
        }
        val touchedKey = touchedKey(feature)
        val recorded = prefs.contains(touchedKey)
        if (locked) {
            if (feature == Feature.BLUETOOTH && BluetoothAudio.isActive(context)) {
                prefs.edit().putBoolean(BT_DEFERRED, true).apply()
                log(context, "$name kept ON: sound is playing over Bluetooth. It turns off when playback stops.")
                startBluetoothWatch(context)
                return
            }
            val current = SystemState.read(context, feature)
            // Save the original once and never overwrite it while a recovery is still pending.
            if (current != null && !prefs.contains(feature.originalKey)) {
                prefs.edit().putBoolean(feature.originalKey, current).apply()
            }
            when (LockRules.atLock(current, feature.lockTarget, recorded)) {
                LockStep.LEAVE_ALONE -> {
                    prefs.edit().putBoolean(touchedKey, false).apply()
                    log(context, "$name was already ${stateWord(feature, feature.lockTarget)} on the phone: left alone")
                }
                LockStep.ALREADY_DONE -> {
                    log(context, "$name already ${stateWord(feature, feature.lockTarget)}")
                    finish(prefs, feature, true)
                }
                LockStep.CHANGE -> {
                    if (!recorded) prefs.edit().putBoolean(touchedKey, true).apply()
                    changeState(context, prefs, backend, feature, feature.lockTarget, true, current)
                }
            }
        } else {
            val touched = prefs.getBoolean(touchedKey, false)
            val original: Boolean? =
                if (prefs.contains(feature.originalKey)) prefs.getBoolean(feature.originalKey, feature.unlockTarget) else null
            val target = LockRules.restoreTo(recorded, touched, original, feature.unlockTarget)
            if (target == null) {
                log(context, "$name left as it is: Privacy Flip did not change it")
                if (recorded) finish(prefs, feature, false)
                return
            }
            changeState(context, prefs, backend, feature, target, false, SystemState.read(context, feature))
        }
    }

    private fun changeState(
        context: Context, prefs: SharedPreferences, backend: AutomationBackend,
        feature: Feature, target: Boolean, locked: Boolean, current: Boolean?
    ) {
        val name = feature.title
        if (current != null && current == target) {
            log(context, "$name already ${stateWord(feature, target)}")
            finish(prefs, feature, locked)
            return
        }
        val error = backend.apply(feature, target)
        if (error != null) {
            prefs.edit().putBoolean(feature.pendingKey, true).apply()
            log(context, "$name FAILED: $error (recovery pending)")
            if (!locked) offerShortcut(context, feature)
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
            if (!locked) offerShortcut(context, feature)
        }
    }

    /** If Wi-Fi or Bluetooth could not be turned back on, leave a one-tap notification for it. */
    private fun offerShortcut(context: Context, feature: Feature) {
        val action = when (feature) {
            Feature.WIFI -> "wifi_panel"
            Feature.BLUETOOTH -> "bt_panel"
            else -> return
        }
        log(context, "${feature.title}: ${ActionExecutor.run(context, action, "Turn ${feature.title} back on", "unlock")}")
    }

    private fun applyBrightness(context: Context, prefs: SharedPreferences, backend: AutomationBackend, locked: Boolean) {
        val name = Feature.BRIGHTNESS.title
        val level = BrightnessControl.current(context)
        val auto = BrightnessControl.isAuto(context)
        if (level == null || auto == null) {
            log(context, "$name skipped: Android did not report the brightness")
            return
        }
        if (locked) {
            if (!prefs.contains(BRIGHTNESS_VALUE)) {
                prefs.edit().putInt(BRIGHTNESS_VALUE, level).putBoolean(BRIGHTNESS_AUTO, auto).apply()
                log(context, "$name original saved: $level${if (auto) " (auto)" else ""}")
            }
            if (!auto && level <= BrightnessControl.MIN) {
                log(context, "$name already at the lowest level")
                finishBrightness(prefs, true)
                return
            }
            val error = BrightnessControl.set(context, backend, BrightnessControl.MIN, false)
            if (error != null) {
                prefs.edit().putBoolean(Feature.BRIGHTNESS.pendingKey, true).apply()
                log(context, "$name FAILED: $error (recovery pending)")
                return
            }
            val ok = waitUntil {
                BrightnessControl.current(context) == BrightnessControl.MIN && BrightnessControl.isAuto(context) == false
            }
            if (ok) {
                log(context, "$name verified at the lowest level")
                finishBrightness(prefs, true)
            } else {
                prefs.edit().putBoolean(Feature.BRIGHTNESS.pendingKey, true).apply()
                log(context, "$name NOT verified after the change (recovery pending)")
            }
        } else {
            if (!prefs.contains(BRIGHTNESS_VALUE)) {
                log(context, "$name left as it is: Privacy Flip did not change it")
                return
            }
            val value = prefs.getInt(BRIGHTNESS_VALUE, level)
            val wasAuto = prefs.getBoolean(BRIGHTNESS_AUTO, false)
            val error = BrightnessControl.set(context, backend, value, wasAuto)
            if (error != null) {
                prefs.edit().putBoolean(Feature.BRIGHTNESS.pendingKey, true).apply()
                log(context, "$name FAILED: $error (recovery pending)")
                return
            }
            val ok = waitUntil {
                if (wasAuto) BrightnessControl.isAuto(context) == true
                else BrightnessControl.current(context) == value && BrightnessControl.isAuto(context) == false
            }
            if (ok) {
                log(context, "$name verified back to normal ($value${if (wasAuto) ", auto" else ""})")
                finishBrightness(prefs, false)
            } else {
                prefs.edit().putBoolean(Feature.BRIGHTNESS.pendingKey, true).apply()
                log(context, "$name NOT verified after restoring (recovery pending)")
            }
        }
    }

    private fun finishBrightness(prefs: SharedPreferences, locked: Boolean) {
        val editor = prefs.edit().remove(Feature.BRIGHTNESS.pendingKey)
        if (!locked) editor.remove(BRIGHTNESS_VALUE).remove(BRIGHTNESS_AUTO)
        editor.apply()
    }

    private fun finish(prefs: SharedPreferences, feature: Feature, locked: Boolean) {
        val editor = prefs.edit().remove(feature.pendingKey)
        if (!locked) editor.remove(feature.originalKey).remove(touchedKey(feature))
        editor.apply()
    }

    private fun waitUntil(check: () -> Boolean): Boolean {
        repeat(6) {
            if (check()) return true
            Thread.sleep(500L)
        }
        return check()
    }

    private fun waitFor(context: Context, feature: Feature, target: Boolean): Boolean {
        repeat(8) {
            if (SystemState.read(context, feature) == target) return true
            Thread.sleep(500L)
        }
        return SystemState.read(context, feature) == target
    }

    // ---- Bluetooth stays on while sound plays, and turns off once playback stops ----

    @Synchronized
    private fun startBluetoothWatch(context: Context) {
        stopBluetoothWatch(context)
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                recheckBluetooth(app)
            }
        }
        try {
            audio.registerAudioPlaybackCallback(callback, null)
            btCallback = callback
        } catch (_: Exception) {
            // The slow check below still covers it.
        }
        btPoll = scheduler.scheduleWithFixedDelay(Runnable { recheckBluetooth(app) }, 120L, 120L, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun stopBluetoothWatch(context: Context) {
        btPoll?.cancel(false)
        btPoll = null
        val callback = btCallback ?: return
        try {
            (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.unregisterAudioPlaybackCallback(callback)
        } catch (_: Exception) {
            // Nothing to undo.
        }
        btCallback = null
    }

    private fun recheckBluetooth(context: Context) {
        scheduler.execute {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(BT_DEFERRED, false)) {
                    stopBluetoothWatch(context)
                    return@execute
                }
                if (prefs.getBoolean("masterPaused", false) || prefs.getString("lastPhase", "") != "LOCKED") return@execute
                if (BluetoothAudio.isActive(context)) return@execute
                holdAwake(context, 30_000L)
                val backend = Backends.current(context, true)
                applyFeature(context, prefs, backend, Feature.BLUETOOTH, true)
                if (!BluetoothAudio.isActive(context)) {
                    prefs.edit().remove(BT_DEFERRED).apply()
                    stopBluetoothWatch(context)
                }
            } catch (_: Exception) {
                // The next callback or check tries again.
            }
        }
    }

    private fun stateWord(feature: Feature, on: Boolean): String = when {
        feature == Feature.BRIGHTNESS -> if (on) "dimmed" else "normal"
        feature.lockTarget -> if (on) "protected" else "unprotected"
        else -> if (on) "ON" else "OFF"
    }

    private fun log(context: Context, message: String) = AutomationEventProcessor.appendLog(context, message)
}
