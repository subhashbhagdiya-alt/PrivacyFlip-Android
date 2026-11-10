package com.privacyflip.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyStateParserTest {
    @Test fun mobileDataRequiresOneUnambiguousState() {
        assertEquals("ENABLED", PrivacyStateParser.parseMobileDataState(0, "mIsDataEnabled=true"))
        assertEquals("DISABLED", PrivacyStateParser.parseMobileDataState(0, "mIsDataEnabled=false"))
        assertEquals("UNKNOWN|No mIsDataEnabled field found", PrivacyStateParser.parseMobileDataState(0, "no telephony fields"))
        assertEquals("UNKNOWN|SIM data states disagree", PrivacyStateParser.parseMobileDataState(0, "mIsDataEnabled=true\nmIsDataEnabled=false"))
        assertEquals("UNKNOWN|Telephony state query failed", PrivacyStateParser.parseMobileDataState(1, "mIsDataEnabled=true"))
    }

    @Test fun numericSettingsRejectUnexpectedValues() {
        assertEquals("DISABLED", PrivacyStateParser.parseNumericSetting("wifi", 0, "0"))
        assertEquals("ENABLED", PrivacyStateParser.parseNumericSetting("bluetooth", 0, "1"))
        assertEquals("UNKNOWN|Unexpected binary setting value", PrivacyStateParser.parseNumericSetting("nfc", 0, "2"))
        assertEquals("UNKNOWN|Android returned an unreadable state", PrivacyStateParser.parseNumericSetting("wifi", 0, "null"))
        assertEquals("DISABLED", PrivacyStateParser.parseNumericSetting("location", 0, "0"))
        assertEquals("ENABLED", PrivacyStateParser.parseNumericSetting("location", 0, "3"))
        assertEquals("UNKNOWN|Unsupported location mode", PrivacyStateParser.parseNumericSetting("location", 0, "9"))
    }
}
