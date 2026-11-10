package com.privacyflip.app

/**
 * Reads only the per-sensor SOFTWARE privacy toggle changed by
 * "cmd sensor_privacy". Missing or unfamiliar dump structures stay UNKNOWN.
 */
internal object SensorPrivacyStateParser {
    fun parse(dump: String, userId: Int, sensorName: String, sensorId: Int): String {
        if (dump.contains("Permission Denial", ignoreCase = true) ||
            dump.contains("Can't find service", ignoreCase = true) ||
            !dump.contains("SENSOR PRIVACY MANAGER STATE", ignoreCase = true)
        ) {
            return "UNKNOWN|Sensor privacy dump is unavailable on this Android build."
        }

        val userBlock = findNamedBlocks(dump, "users").firstOrNull { block ->
            Regex("""(?m)^\s*user_id\s*:\s*$userId\b""").containsMatchIn(block)
        } ?: return "UNKNOWN|Current user's sensor privacy state was not found."

        // Legacy Android lists enabled sensors explicitly. Absence from a
        // present list means OFF for this sensor; an absent list is not proof.
        val legacyBlocks = findNamedBlocks(userBlock, "individual_enabled_sensor")
        val legacySensor = legacyBlocks.firstOrNull { sensorMatches(it, sensorName, sensorId) }
        if (legacySensor != null) {
            val enabled = Regex(
                """(?m)^\s*is_enabled\s*:\s*(true|false)\b""",
                RegexOption.IGNORE_CASE
            ).find(legacySensor)?.groupValues?.get(1)
                ?: return "UNKNOWN|Per-sensor state was not explicit."
            return if (enabled.equals("true", ignoreCase = true)) "ENABLED" else "DISABLED"
        }
        if (legacyBlocks.isNotEmpty() || userBlock.contains("individual_enabled_sensor")) {
            return "DISABLED"
        }

        // Newer Android releases group sensor state under sensors -> toggles.
        val sensorBlock = findNamedBlocks(userBlock, "sensors")
            .firstOrNull { sensorMatches(it, sensorName, sensorId) }
            ?: return "UNKNOWN|Current sensor entry was not found."
        val softwareToggles = findNamedBlocks(sensorBlock, "toggles").filter { toggle ->
            Regex("""(?m)^\s*toggle_type\s*:\s*(?:SOFTWARE|1)\b""")
                .containsMatchIn(toggle)
        }
        if (softwareToggles.isEmpty()) {
            return "UNKNOWN|No explicit software sensor toggle was found."
        }

        val states = softwareToggles.mapNotNull { toggle ->
            Regex("""(?m)^\s*state_type\s*:\s*([A-Z_]+)\b""")
                .find(toggle)?.groupValues?.get(1)
        }.toSet()
        return when {
            states.size != 1 -> "UNKNOWN|Software sensor toggle state is missing or conflicting."
            states.single() == "ENABLED" ||
                states.single() == "ENABLED_EXCEPT_ALLOWLISTED_APPS" -> "ENABLED"
            states.single() == "DISABLED" -> "DISABLED"
            else -> "UNKNOWN|Unrecognized software sensor privacy state."
        }
    }

    private fun sensorMatches(block: String, sensorName: String, sensorId: Int): Boolean {
        val value = Regex("""(?m)^\s*sensor\s*:\s*([^\r\n]+)""")
            .find(block)?.groupValues?.get(1)?.trim() ?: return false
        return value.startsWith(sensorName, ignoreCase = true) ||
            Regex("""^$sensorId(?:\b|\s*\()""").containsMatchIn(value)
    }

    /** Extract balanced text-proto blocks, including nested child blocks. */
    private fun findNamedBlocks(source: String, field: String): List<String> {
        val pattern = Regex("""(?m)^\s*${Regex.escape(field)}\s*\{""")
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (cursor < source.length) {
            val match = pattern.find(source, cursor) ?: break
            val open = source.indexOf('{', match.range.first)
            var depth = 0
            var end = -1
            for (index in open until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            blocks.add(source.substring(open + 1, end))
            cursor = end + 1
        }
        return blocks
    }
}
