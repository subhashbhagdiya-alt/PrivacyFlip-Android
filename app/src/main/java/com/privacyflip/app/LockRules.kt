package com.privacyflip.app

enum class LockStep { LEAVE_ALONE, ALREADY_DONE, CHANGE }

/**
 * The one rule behind lock/unlock: only undo what Privacy Flip itself changed.
 * A setting the user keeps off (or on) on the phone is never forced the other way.
 */
object LockRules {
    /**
     * Decides what to do when the phone locks.
     * [current] is the real state of the setting (null if Android will not say),
     * [lockTarget] the state wanted while locked, [alreadyRecorded] whether an earlier lock
     * already recorded this setting (its record must not be overwritten).
     */
    fun atLock(current: Boolean?, lockTarget: Boolean, alreadyRecorded: Boolean): LockStep {
        val alreadyThere = current != null && current == lockTarget
        return when {
            alreadyRecorded -> if (alreadyThere) LockStep.ALREADY_DONE else LockStep.CHANGE
            alreadyThere -> LockStep.LEAVE_ALONE
            else -> LockStep.CHANGE
        }
    }

    /**
     * Decides the state to put back when the phone unlocks, or null to leave the setting as it is.
     * [recorded]/[touched] say whether the lock step recorded the setting and really changed it,
     * [original] is the state saved before locking, [unlockTarget] the normal state.
     */
    fun restoreTo(recorded: Boolean, touched: Boolean, original: Boolean?, unlockTarget: Boolean): Boolean? =
        if (!recorded || !touched) null else (original ?: unlockTarget)
}
