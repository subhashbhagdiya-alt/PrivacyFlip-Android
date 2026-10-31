package com.privacyflip.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Restores a feature to the exact state observed before lock.
 * Connectivity/location go OFF while camera/microphone privacy goes ON.
 */
object PairedFeatureRestoreController {
    private const val PREFS = "privacy_flip"
    private val guard = Any()
    private val busy = mutableSetOf<String>()
    private val unlockQueued = mutableSetOf<String>()

    private fun key(option: String, suffix: String) = "paired_restore_" + option + "_" + suffix

    // Backwards-compatible entry point for Wi-Fi, Bluetooth and Location (OFF while locked).
    fun onLock(context: Context, option: String, ruleName: String, log: (String) -> Unit) {
        onLock(context, option, ruleName, false, log)
    }

    fun onLock(context: Context, option: String, ruleName: String, lockedStateEnabled: Boolean, log: (String) -> Unit) {
        if (option !in supportedOptions) {
            log(option + ": safe restore is not available; action skipped.")
            return
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(guard) {
            if (!busy.add(option)) return
            if (prefs.getBoolean(key(option, "owned"), false)) {
                busy.remove(option)
                log(option + ": existing restore record retained; duplicate lock ignored.")
                return
            }
        }

        ShizukuAutomation.readState(app, option) { state ->
            val target = if (lockedStateEnabled) "ENABLED" else "DISABLED"
            when (state) {
                target -> {
                    log(option + ": already in its requested lock state; no restore action created.")
                    finish(app, option, log)
                }
                "ENABLED", "DISABLED" -> {
                    val saved = prefs.edit()
                        .putBoolean(key(option, "owned"), true)
                        .putString(key(option, "original"), state)
                        .putString(key(option, "phase"), "change_pending")
                        .putString(key(option, "rule"), ruleName)
                        .commit()
                    if (!saved) {
                        log(option + ": could not save original state; no change made.")
                        finish(app, option, log)
                    } else {
                        ShizukuAutomation.apply(app, option, lockedStateEnabled) { result ->
                            ShizukuAutomation.readState(app, option) { verified ->
                                when (verified) {
                                    target -> {
                                        prefs.edit().putString(key(option, "phase"), "locked").commit()
                                        log(option + ": lock state " + target + " confirmed; original " + state + " saved for unlock.")
                                    }
                                    state -> {
                                        clear(prefs, option)
                                        log(option + ": remained " + state + "; requested lock change did not take effect. " + result.take(120))
                                    }
                                    else -> {
                                        prefs.edit().putString(key(option, "phase"), "change_unconfirmed").commit()
                                        log(option + ": lock state unconfirmed; restore record retained safely. " + verified.take(140))
                                    }
                                }
                                finish(app, option, log)
                            }
                        }
                    }
                }
                else -> {
                    log(option + ": lock skipped because state is UNKNOWN. " + state.take(150))
                    finish(app, option, log)
                }
            }
        }
    }

    fun onUnlock(context: Context, option: String, log: (String) -> Unit) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(guard) {
            if (!busy.add(option)) {
                unlockQueued.add(option)
                return
            }
            if (!prefs.getBoolean(key(option, "owned"), false)) {
                busy.remove(option)
                log(option + ": restore skipped; Privacy Flip has no verified change to undo.")
                return
            }
        }

        val original = prefs.getString(key(option, "original"), null)
        if (original != "ENABLED" && original != "DISABLED") {
            log(option + ": saved original state is missing; no guessed change made.")
            finish(app, option, log)
            return
        }

        ShizukuAutomation.readState(app, option) { state ->
            when (state) {
                original -> {
                    clear(prefs, option)
                    log(option + ": already back to its original state; restore record cleared.")
                    finish(app, option, log)
                }
                "ENABLED", "DISABLED" -> {
                    prefs.edit().putString(key(option, "phase"), "restore_pending").commit()
                    ShizukuAutomation.apply(app, option, original == "ENABLED") { result ->
                        ShizukuAutomation.readState(app, option) { verified ->
                            if (verified == original) {
                                clear(prefs, option)
                                log(option + ": original " + original + " state restored and confirmed.")
                            } else {
                                prefs.edit().putString(key(option, "phase"), "restore_unconfirmed").commit()
                                log(option + ": restore unconfirmed; record retained for retry. " +
                                    (if (verified.startsWith("UNKNOWN")) verified else result).take(150))
                            }
                            finish(app, option, log)
                        }
                    }
                }
                else -> {
                    prefs.edit().putString(key(option, "phase"), "restore_unconfirmed").commit()
                    log(option + ": restore postponed because state is UNKNOWN; record retained. " + state.take(150))
                    finish(app, option, log)
                }
            }
        }
    }

    private fun finish(context: Context, option: String, log: (String) -> Unit) {
        val retryUnlock = synchronized(guard) {
            busy.remove(option)
            unlockQueued.remove(option)
        }
        if (retryUnlock) onUnlock(context, option, log)
    }

    private fun clear(prefs: SharedPreferences, option: String) {
        prefs.edit()
            .remove(key(option, "owned"))
            .remove(key(option, "original"))
            .remove(key(option, "phase"))
            .remove(key(option, "rule"))
            .commit()
    }

    private val supportedOptions = setOf(
        "wifi", "bluetooth", "location", "camera_privacy", "microphone_privacy"
    )
}
