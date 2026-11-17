package com.privacyflip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brightness is set through BrightnessControl, so it has no shell command. */
private val shellFeatures = Feature.values().filter { it != Feature.BRIGHTNESS }

class FeatureTest {
    @Test
    fun keysAreUniqueAndStorageKeysAreDerivedFromThem() {
        val keys = Feature.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        Feature.values().forEach {
            assertEquals("pf_${it.key}", it.enabledKey)
            assertEquals("pf_orig_${it.key}", it.originalKey)
            assertEquals("pf_pending_${it.key}", it.pendingKey)
        }
    }

    @Test
    fun unlockTargetIsAlwaysTheOppositeOfLockTarget() {
        Feature.values().forEach { assertEquals(!it.lockTarget, it.unlockTarget) }
    }

    @Test
    fun connectivityTurnsOffWhenLockedAndPrivacyAndDimmingTurnOn() {
        listOf(Feature.WIFI, Feature.BLUETOOTH, Feature.LOCATION, Feature.MOBILE_DATA, Feature.NFC)
            .forEach { assertFalse("${it.title} should be OFF when locked", it.lockTarget) }
        listOf(Feature.CAMERA_PRIVACY, Feature.MIC_PRIVACY, Feature.BRIGHTNESS)
            .forEach { assertTrue("${it.title} should be ON when locked", it.lockTarget) }
    }
}

class FeatureCommandsTest {
    private val allowedStarts = listOf("svc ", "cmd ", "settings ")

    @Test
    fun everyShellFeatureHasADifferentCommandForOnAndOff() {
        shellFeatures.forEach {
            assertNotEquals(
                "${it.title} on/off commands must differ",
                FeatureCommands.command(it, true), FeatureCommands.command(it, false)
            )
        }
    }

    @Test
    fun commandsStayInsideTheAllowList() {
        shellFeatures.forEach { feature ->
            listOf(true, false).forEach { on ->
                val command = FeatureCommands.command(feature, on)
                assertTrue("empty command for ${feature.title}", command.isNotBlank())
                assertTrue("unexpected command: $command", allowedStarts.any { command.startsWith(it) })
                listOf(";", "`", "$(", "\n", "&&", ">", "|\\s").forEach { bad ->
                    assertFalse("unsafe fragment '$bad' in: $command", command.contains(bad))
                }
            }
        }
    }

    @Test
    fun privacyCommandsUseEnableToBlockAndDisableToAllow() {
        assertEquals("cmd sensor_privacy enable 0 camera", FeatureCommands.command(Feature.CAMERA_PRIVACY, true))
        assertEquals("cmd sensor_privacy disable 0 camera", FeatureCommands.command(Feature.CAMERA_PRIVACY, false))
        assertEquals("cmd sensor_privacy enable 0 microphone", FeatureCommands.command(Feature.MIC_PRIVACY, true))
    }

    @Test
    fun radioCommandsMatchTheExpectedServices() {
        assertEquals("svc wifi disable", FeatureCommands.command(Feature.WIFI, false))
        assertEquals("svc wifi enable", FeatureCommands.command(Feature.WIFI, true))
        assertEquals("svc bluetooth disable", FeatureCommands.command(Feature.BLUETOOTH, false))
        assertEquals("svc data enable", FeatureCommands.command(Feature.MOBILE_DATA, true))
        assertEquals("svc nfc disable", FeatureCommands.command(Feature.NFC, false))
    }
}
