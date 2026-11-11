package com.privacyflip.app

internal data class ActionOption(val id: String, val title: String, val note: String)

internal data class Preset(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val trigger: String,
    val event: String,
    val actions: List<ActionOption>
)

internal object TriggerCatalog {
    val all = listOf(
        "Screen turns off", "Screen turns on", "Phone unlocked", "Device starts", "Time schedule",
        "Charger connected", "Charger disconnected", "Battery threshold", "Network changes",
        "Wired headphones plugged", "Wired headphones unplugged", "Airplane mode changes",
        "Motion / flip", "Manual quick action"
    )
}

internal object PresetCatalog {
    val categories = listOf(
        "Screen & Privacy", "Battery & Charging", "Connectivity & Devices", "Motion & Device Events"
    )

    private fun logOpt() = ActionOption("log", "Write activity log", "Saved in Diagnostics")
    private fun notifyOpt() = ActionOption("notify", "Show notification", "Needs notification permission")

    val all: List<Preset> = listOf(
        Preset("screen_lock", categories[0], "Screen lock privacy",
            "When the screen turns off, calm the phone and prompt radios off.",
            "Screen turns off", "Screen turns off", listOf(
                ActionOption("ringer_vibrate", "Sound mode: Vibrate", "Works without special access"),
                ActionOption("wifi_panel", "Wi-Fi: one-tap panel notification", "Android blocks silent Wi-Fi switching"),
                ActionOption("bt_panel", "Bluetooth: one-tap settings notification", "Android blocks silent Bluetooth switching"),
                logOpt()
            )),
        Preset("screen_unlock", categories[0], "Unlock restore",
            "When you unlock, bring sound back to normal.",
            "Phone unlocked", "Phone unlocked", listOf(
                ActionOption("ringer_normal", "Sound mode: Normal", "Works without special access"),
                ActionOption("dnd_off", "Do Not Disturb OFF", "Needs Do Not Disturb access"),
                notifyOpt(), logOpt()
            )),
        Preset("low_battery", categories[1], "Low battery saver",
            "When battery drops to your threshold, dim and quiet the phone.",
            "Battery threshold", "Battery threshold", listOf(
                ActionOption("brightness_low", "Brightness LOW", "Needs Modify system settings access"),
                ActionOption("ringer_vibrate", "Sound mode: Vibrate", "Works without special access"),
                notifyOpt(), logOpt()
            )),
        Preset("charger_on", categories[1], "Charger connected",
            "When power is plugged in.",
            "Charger connected", "Charger connected", listOf(
                ActionOption("brightness_high", "Brightness HIGH", "Needs Modify system settings access"),
                notifyOpt(), logOpt()
            )),
        Preset("charger_off", categories[1], "Charger disconnected",
            "When power is unplugged.",
            "Charger disconnected", "Charger disconnected", listOf(notifyOpt(), logOpt())),
        Preset("network", categories[2], "Network changed",
            "When the default network connects, drops or switches.",
            "Network changes", "Network changes", listOf(notifyOpt(), logOpt())),
        Preset("headset_in", categories[2], "Wired headphones plugged in",
            "When wired headphones are connected.",
            "Wired headphones plugged", "Wired headphones plugged", listOf(
                ActionOption("vibrate", "Vibrate phone briefly", "No permission needed"),
                notifyOpt(), logOpt()
            )),
        Preset("headset_out", categories[2], "Wired headphones unplugged",
            "Avoid blasting the speaker when headphones come out.",
            "Wired headphones unplugged", "Wired headphones unplugged", listOf(
                ActionOption("ringer_vibrate", "Sound mode: Vibrate", "Works without special access"),
                notifyOpt(), logOpt()
            )),
        Preset("airplane", categories[2], "Airplane mode changed",
            "When airplane mode is switched.",
            "Airplane mode changes", "Airplane mode changes", listOf(notifyOpt(), logOpt())),
        Preset("face_down", categories[3], "Flip face-down to silence",
            "Place the phone face-down to quiet it.",
            "Motion / flip", "Motion / flip", listOf(
                ActionOption("dnd_on", "Do Not Disturb ON (alarms only)", "Needs Do Not Disturb access"),
                ActionOption("ringer_vibrate", "Sound mode: Vibrate", "Works without special access"),
                ActionOption("lock", "Lock screen", "Needs Accessibility service enabled"),
                notifyOpt(), logOpt()
            )),
        Preset("boot", categories[3], "After device restart",
            "When the phone finishes booting.",
            "Device starts", "Device starts", listOf(notifyOpt(), logOpt()))
    )
}
