package com.privacyflip.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorPrivacyStateParserTest {
    @Test fun parsesExplicitModernSoftwareStates() {
        val enabled = """
            SENSOR PRIVACY MANAGER STATE
            users {
              user_id: 0
              sensors {
                sensor: CAMERA (2)
                toggles {
                  toggle_type: SOFTWARE
                  state_type: ENABLED
                }
              }
            }
        """.trimIndent()
        val disabled = enabled.replace("state_type: ENABLED", "state_type: DISABLED")
        assertEquals("ENABLED", SensorPrivacyStateParser.parse(enabled, 0, "CAMERA", 2))
        assertEquals("DISABLED", SensorPrivacyStateParser.parse(disabled, 0, "CAMERA", 2))
    }

    @Test fun legacyListTreatsAbsentSensorAsDisabledOnlyWhenListIsExplicit() {
        val dump = """
            SENSOR PRIVACY MANAGER STATE
            users {
              user_id: 0
              individual_enabled_sensor {
                sensor: MICROPHONE (1)
                is_enabled: true
              }
            }
        """.trimIndent()
        assertEquals("ENABLED", SensorPrivacyStateParser.parse(dump, 0, "MICROPHONE", 1))
        assertEquals("DISABLED", SensorPrivacyStateParser.parse(dump, 0, "CAMERA", 2))
    }

    @Test fun missingOrUnrecognizedStateIsUnknownNotDisabled() {
        val missingSensor = """
            SENSOR PRIVACY MANAGER STATE
            users {
              user_id: 0
              sensors {
                sensor: MICROPHONE (1)
                toggles {
                  toggle_type: SOFTWARE
                  state_type: DISABLED
                }
              }
            }
        """.trimIndent()
        val noToggle = """
            SENSOR PRIVACY MANAGER STATE
            users {
              user_id: 0
              sensors {
                sensor: CAMERA (2)
              }
            }
        """.trimIndent()
        assertEquals("UNKNOWN|Current sensor entry was not found.", SensorPrivacyStateParser.parse(missingSensor, 0, "CAMERA", 2))
        assertEquals("UNKNOWN|No explicit software sensor toggle was found.", SensorPrivacyStateParser.parse(noToggle, 0, "CAMERA", 2))
        assertEquals("UNKNOWN|Sensor privacy dump is unavailable on this Android build.", SensorPrivacyStateParser.parse("permission denial", 0, "CAMERA", 2))
    }
}
