package com.privacyflip.app

import android.content.Context
import android.content.SharedPreferences

/**
 * State-aware lock/unlock controller for Wi-Fi, Bluetooth and Location.
 * It only turns a feature back ON when it verified that it was ON before
 * lock and that Privacy Flip successfully turned it OFF.
 */
object PairedFeatureRestoreController {
    private const val PREFS = "privacy_flip"
    private val guard = Any()
    private val busy = mutableSetOf<String>()
    private val unlockQueued = mutableSetOf<String>()

    private fun key(option: String, suffix: String) = "paired_restore_${option}_${suffix}"

    fun onLock(context: Context, option: String, ruleName: String, log: (String) -> Unit) {
        if (option !in supportedOptions) {
            log("${option}: safe restore is not available; action skipped.")
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
                    val saved = prefs.edit()
                        .putBoolean(key(option, "owned"), true)
                        .putString(key(option, "original"), "ENABLED")
                        .putString(key(option, "phase"), "disable_pending")
                        .putString(key(option, "rule"), ruleName)
                        .commit()
                    if (!saved) {
                        log("${option}: could not save original state; no change made.")
                        finish(app, option, log)
                    } else {
                        ShizukuAutomation.apply(app, option, false) { result ->
                            ShizukuAutomation.readState(app, option) { verified ->
                                when (verified) {
                                    "DISABLED" -> {
                                        prefs.edit().putString(key(option, "phase"), "locked").commit()
                                        log("${option}: OFF confirmed; original ON state saved for unlock.")
                                    }
                                    "ENABLED" -> {
                                        clear(prefs, option)
                                        log("${option}: remained ON; disable did not take effect. " + result.take(120))
                                    }
                                    else -> {
                                        prefs.edit().putString(key(option, "phase"), "disable_unconfirmed").commit()
                                        log("${option}: OFF state unconfirmed; restore record retained. " + verified.take(140))
                                    }
                                }
                                finish(app, option, log)
                            }
                        }
                    }
                }
                "DISABLED" -> {
                    log("${option}: already OFF before lock; no unlock action will be created.")
                    finish(app, option, log)
                }
                else -> {
                    log("${option}: lock skipped because state is UNKNOWN. " + state.take(150))
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
                prefs.getString(key(option, "original"), null) != "ENABLED") {
                busy.remove(option)
                log("${option}: restore skipped; Privacy Flip has no verified OFF change to undo.")
                return
            }
        }

        ShizukuAutomation.readState(app, option) { state ->
            when (state) {
                "ENABLED" -> {
                    clear(prefs, option)
                    log("${option}: already ON; restore record cleared without changing it.")
                    finish(app, option, log)
                }
                "DISABLED" -> {
                    prefs.edit().putString(key(option, "phase"), "restore_pending").commit()
                    ShizukuAutomation.apply(app, option, true) { result ->
                        ShizukuAutomation.readState(app, option) { verified ->
                            if (verified == "ENABLED") {
                                clear(prefs, option)
                                log("${option}: ON confirmed; original state restored.")
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

    private val supportedOptions = setOf("wifi", "bluetooth", "location")
}
