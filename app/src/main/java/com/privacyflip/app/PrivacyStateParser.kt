package com.privacyflip.app

/**
 * Conservative parsers for system state output.
 * Malformed, unsupported, or ambiguous values remain UNKNOWN.
 */
internal object PrivacyStateParser {
    fun parseMobileDataState(exitCode: Int, output: String): String {
        if (exitCode != 0) return "UNKNOWN|Telephony state query failed"
        val states = Regex("""mIsDataEnabled\s*=\s*(true|false)""", RegexOption.IGNORE_CASE)
            .findAll(output)
            .map { it.groupValues[1].equals("true", ignoreCase = true) }
            .toSet()
        return when (states.size) {
            0 -> "UNKNOWN|No mIsDataEnabled field found"
            1 -> if (states.first()) "ENABLED" else "DISABLED"
            else -> "UNKNOWN|SIM data states disagree"
        }
    }

    /** Parses settings get output without treating arbitrary values as OFF. */
    fun parseNumericSetting(option: String, exitCode: Int, output: String): String {
        if (exitCode != 0) return "UNKNOWN|System setting query failed"
        val value = output.trim().toIntOrNull()
            ?: return "UNKNOWN|Android returned an unreadable state"
        return when (option) {
            "location" -> when (value) {
                0 -> "DISABLED"
                in 1..3 -> "ENABLED"
                else -> "UNKNOWN|Unsupported location mode"
            }
            "wifi", "bluetooth", "nfc" -> when (value) {
                0 -> "DISABLED"
                1 -> "ENABLED"
                else -> "UNKNOWN|Unexpected binary setting value"
            }
            else -> "UNKNOWN|Unsupported setting"
        }
    }
}
