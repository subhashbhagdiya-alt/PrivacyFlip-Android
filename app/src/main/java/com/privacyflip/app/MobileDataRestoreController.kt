package com.privacyflip.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Owns the Mobile Data lock/unlock lifecycle.
 *
 * The controller only disables data when a verified pre-lock state is ENABLED.
 * It restores only a disable operation that this controller recorded. UNKNOWN
 * states never become an implicit ON/OFF decision.
 */
object MobileDataRestoreController {
    private const val PREFS = "privacy_flip"
    private const val KEY_OWNED = "mobile_data_restore_owned"
    private const val KEY_ORIGINAL = "mobile_data_restore_original"
    private const val KEY_PHASE = "mobile_data_restore_phase"
    private const val KEY_RULE = "mobile_data_restore_rule"

    private val guard = Any()
    private var busy = false
    private var unlockQueued = false

    fun onLock(context: Context, ruleName: String, log: (String) -> Unit) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(guard) {
            if (busy) return
            if (prefs.getBoolean(KEY_OWNED, false)) {
                log("Mobile Data: existing restore record retained; duplicate lock event ignored.")
                return
            }
            busy = true
        }

        MobileDataShizuku.getMobileDataState(context) { state ->
            when (state) {
                "ENABLED" -> {
                    // Persist ownership before changing the system setting.
                    val saved = prefs.edit()
                        .putBoolean(KEY_OWNED, true)
                        .putString(KEY_ORIGINAL, "ENABLED")
                        .putString(KEY_PHASE, "disable_pending")
                        .putString(KEY_RULE, ruleName)
                        .commit()
                    if (!saved) {
                        log("Mobile Data: could not safely save the original state; no change made.")
                        finish(context, log)
                    } else {
                        MobileDataShizuku.setMobileDataEnabled(context, false) { result ->
                            verifyAfterDisable(context, prefs, result, log)
                        }
                    }
                }
                "DISABLED" -> {
                    log("Mobile Data was already OFF before lock; leaving it OFF and not creating a restore action.")
                    finish(context, log)
                }
                else -> {
                    log("Mobile Data lock skipped: current state is UNKNOWN. " + state.take(180))
                    finish(context, log)
                }
            }
        }
    }

    private fun verifyAfterDisable(
        context: Context,
        prefs: SharedPreferences,
        commandResult: String,
        log: (String) -> Unit
    ) {
        MobileDataShizuku.getMobileDataState(context) { state ->
            when (state) {
                "DISABLED" -> {
                    prefs.edit().putString(KEY_PHASE, "locked").commit()
                    log("Mobile Data OFF confirmed. Original ON state saved for unlock restore.")
                }
                "ENABLED" -> {
                    clearRecord(prefs)
                    log("Mobile Data remains ON; disable did not take effect. Restore record cleared. " + commandResult.take(140))
                }
                else -> {
                    prefs.edit().putString(KEY_PHASE, "disable_unconfirmed").commit()
                    log("Mobile Data disable is unconfirmed; original state retained safely. " + state.take(160))
                }
            }
            finish(context, log)
        }
    }

    fun onUnlock(context: Context, log: (String) -> Unit) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(guard) {
            if (busy) {
                unlockQueued = true
                return
            }
            if (!prefs.getBoolean(KEY_OWNED, false)) {
                log("Mobile Data restore skipped: Privacy Flip did not disable it.")
                return
            }
            busy = true
        }

        MobileDataShizuku.getMobileDataState(context) { state ->
            when (state) {
                "ENABLED" -> {
                    clearRecord(prefs)
                    log("Mobile Data is already ON; restore record cleared without another command.")
                    finish(context, log)
                }
                "DISABLED" -> {
                    prefs.edit().putString(KEY_PHASE, "restore_pending").commit()
                    MobileDataShizuku.setMobileDataEnabled(context, true) { result ->
                        MobileDataShizuku.getMobileDataState(context) { verified ->
                            if (verified == "ENABLED") {
                                clearRecord(prefs)
                                log("Mobile Data ON confirmed; saved original state restored.")
                            } else {
                                prefs.edit().putString(KEY_PHASE, "restore_unconfirmed").commit()
                                log("Mobile Data restore not confirmed; restore record kept for retry. " +
                                    (if (verified == "DISABLED") result else verified).take(160))
                            }
                            finish(context, log)
                        }
                    }
                }
                else -> {
                    prefs.edit().putString(KEY_PHASE, "restore_unconfirmed").commit()
                    log("Mobile Data restore postponed: state UNKNOWN; saved restore record retained. " + state.take(160))
                    finish(context, log)
                }
            }
        }
    }

    private fun clearRecord(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_OWNED)
            .remove(KEY_ORIGINAL)
            .remove(KEY_PHASE)
            .remove(KEY_RULE)
            .commit()
    }

    private fun finish(context: Context, log: (String) -> Unit) {
        val runUnlock = synchronized(guard) {
            busy = false
            val queued = unlockQueued
            unlockQueued = false
            queued
        }
        if (runUnlock) onUnlock(context, log)
    }
}
