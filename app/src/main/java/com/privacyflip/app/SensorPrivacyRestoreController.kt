package com.privacyflip.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Owns Camera/Microphone software privacy toggles across the lock lifecycle.
 * A toggle is enabled on lock only when it was verified OFF beforehand, and
 * is disabled on unlock only when the controller recorded that exact change.
 */
object SensorPrivacyRestoreController {
    private const val PREFS = "privacy_flip"
    private val guard = Any()
    private val busy = mutableSetOf<String>()
    private val unlockQueued = mutableSetOf<String>()

    private fun key(option: String, suffix: String) = "sensor_privacy_restore_${option}_${suffix}"

    fun onLock(context: Context, option: String, ruleName: String, log: (String) -> Unit) {
        if (option !in supportedOptions) {
            log("${option}: unsupported sensor privacy action; skipped.")
            return
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(guard) {
            if (!busy.add(option)) return
            if (prefs.getBoolean(key(option, "owned"), false)) {
                busy.remove(option)
                log("${option}: existing restore record retained; duplicate lock ignored.")
                return
            }
        }

        ShizukuAutomation.readState(app, option) { state ->
            when (state) {
                "ENABLED" -> {
                    log("${option}: privacy was already ON before lock; leaving it ON.")
                    finish(app, option, log)
                }
                "DISABLED" -> {
                    val saved = prefs.edit()
                        .putBoolean(key(option, "owned"), true)
                        .putString(key(option, "original"), "DISABLED")
                        .putString(key(option, "phase"), "enable_pending")
                        .putString(key(option, "rule"), ruleName)
                        .commit()
                    if (!saved) {
                        log("${option}: could not save original state; no change made.")
                        finish(app, option, log)
                    } else {
                        ShizukuAutomation.apply(app, option, true) { result ->
                            ShizukuAutomation.readState(app, option) { verified ->
                                when (verified) {
                                    "ENABLED" -> {
                                        prefs.edit().putString(key(option, "phase"), "locked").commit()
                                        log("${option}: privacy ON confirmed; original OFF state saved for unlock.")
                                    }
                                    "DISABLED" -> {
                                        clear(prefs, option)
                                        log("${option}: privacy did not turn ON; restore record cleared. " + result.take(120))
                                    }
                                    else -> {
                                        prefs.edit().putString(key(option, "phase"), "enable_unconfirmed").commit()
                                        log("${option}: privacy ON is unconfirmed; restore record retained. " + verified.take(140))
                                    }
                                }
                                finish(app, option, log)
                            }
                        }
                    }
                }
                else -> {
                    log("${option}: lock skipped because sensor privacy state is UNKNOWN. " + state.take(150))
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
            if (!prefs.getBoolean(key(option, "owned"), false) ||
                prefs.getString(key(option, "original"), null) != "DISABLED") {
                busy.remove(option)
                log("${option}: restore skipped; Privacy Flip has no verified privacy-ON change to undo.")
                return
            }
        }

        ShizukuAutomation.readState(app, option) { state ->
            when (state) {
                "DISABLED" -> {
                    clear(prefs, option)
                    log("${option}: privacy is already OFF; restore record cleared without a command.")
                    finish(app, option, log)
                }
                "ENABLED" -> {
                    prefs.edit().putString(key(option, "phase"), "disable_pending").commit()
                    ShizukuAutomation.apply(app, option, false) { result ->
                        ShizukuAutomation.readState(app, option) { verified ->
                            if (verified == "DISABLED") {
                                clear(prefs, option)
                                log("${option}: privacy OFF confirmed; original state restored.")
                            } else {
                                prefs.edit().putString(key(option, "phase"), "restore_unconfirmed").commit()
                                log("${option}: restore unconfirmed; record retained for retry. " +
                                    (if (verified.startsWith("UNKNOWN")) verified else result).take(150))
                            }
                            finish(app, option, log)
                        }
                    }
                }
                else -> {
                    prefs.edit().putString(key(option, "phase"), "restore_unconfirmed").commit()
                    log("${option}: restore postponed because state is UNKNOWN; record retained. " + state.take(150))
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

    private val supportedOptions = setOf("camera_privacy", "microphone_privacy")
}
