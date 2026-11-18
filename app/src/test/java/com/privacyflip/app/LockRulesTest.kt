package com.privacyflip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockRulesTest {
    @Test
    fun aSettingKeptOffOnThePhoneIsLeftAloneWhenLocking() {
        // Wi-Fi: wanted OFF while locked, but it is already OFF on the phone.
        assertEquals(LockStep.LEAVE_ALONE, LockRules.atLock(current = false, lockTarget = false, alreadyRecorded = false))
    }

    @Test
    fun aSettingThatIsOnGetsTurnedOffWhenLocking() {
        assertEquals(LockStep.CHANGE, LockRules.atLock(current = true, lockTarget = false, alreadyRecorded = false))
    }

    @Test
    fun anUnknownStateIsStillChanged() {
        // Camera and microphone privacy cannot be read back.
        assertEquals(LockStep.CHANGE, LockRules.atLock(current = null, lockTarget = true, alreadyRecorded = false))
    }

    @Test
    fun aSecondLockNeverOverwritesTheEarlierRecord() {
        // After the first lock turned Wi-Fi off, the phone says OFF: that must not count as "user kept it off".
        assertEquals(LockStep.ALREADY_DONE, LockRules.atLock(current = false, lockTarget = false, alreadyRecorded = true))
        assertEquals(LockStep.CHANGE, LockRules.atLock(current = true, lockTarget = false, alreadyRecorded = true))
    }

    @Test
    fun unlockOnlyRestoresWhatTheLockStepChanged() {
        assertEquals(true, LockRules.restoreTo(recorded = true, touched = true, original = true, unlockTarget = true))
        assertNull(LockRules.restoreTo(recorded = true, touched = false, original = false, unlockTarget = true))
        assertNull(LockRules.restoreTo(recorded = false, touched = false, original = null, unlockTarget = true))
    }

    @Test
    fun unlockFallsBackToTheNormalStateWhenNothingWasSaved() {
        assertEquals(false, LockRules.restoreTo(recorded = true, touched = true, original = null, unlockTarget = false))
    }
}
