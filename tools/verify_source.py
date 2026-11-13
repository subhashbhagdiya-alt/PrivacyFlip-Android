#!/usr/bin/env python3
"""Fast, offline structural checks for Privacy Flip. Not a replacement for Android compilation/device tests."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/privacyflip/app"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
APP_BUILD = ROOT / "app/build.gradle.kts"
WORKFLOW = ROOT / ".github/workflows/android-apk.yml"
ACCESS_XML = ROOT / "app/src/main/res/xml/accessibility_service_config.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
KOTLIN_FILES = ["MainActivity.kt", "Store.kt", "Home.kt", "PresetsScreen.kt", "RulesScreen.kt",
                "DiagnosticsScreen.kt", "SettingsScreen.kt", "HomeAutomationControls.kt",
                "AutomationReceivers.kt", "Actions.kt", "Presets.kt", "Features.kt",
                "LockUnlockEngine.kt", "MonitorService.kt", "PrivacyFlipAccessibilityService.kt"]
errors: list[str] = []


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def balanced(text: str) -> bool:
    """Cheap bracket-balance check that ignores string literals and comments."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            i = text.find("*/", i) + 2 if "*/" in text[i:] else n
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            out.append(c)
            i += 1
    stack, pairs = [], {")": "(", "]": "[", "}": "{"}
    for c in out:
        if c in "([{":
            stack.append(c)
        elif c in pairs:
            if not stack or stack.pop() != pairs[c]:
                return False
    return not stack


needed = [MANIFEST, APP_BUILD, WORKFLOW, ACCESS_XML] + [SRC / f for f in KOTLIN_FILES]
for path in needed:
    require(path.is_file(), f"Required project file is missing: {path.relative_to(ROOT)}")

if not errors:
    sources = {f: (SRC / f).read_text(encoding="utf-8") for f in KOTLIN_FILES}
    for name, text in sources.items():
        require(balanced(text), f"Unbalanced brackets in {name}")
    code = "\n".join(sources.values())

    manifest = ET.parse(MANIFEST).getroot()
    permissions = {n.attrib.get(ANDROID_NS + "name", "") for n in manifest.findall("uses-permission")}
    for perm in ("RECEIVE_BOOT_COMPLETED", "ACCESS_NETWORK_STATE", "POST_NOTIFICATIONS", "VIBRATE",
                 "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "ACCESS_NOTIFICATION_POLICY",
                 "WRITE_SETTINGS", "SCHEDULE_EXACT_ALARM", "ACCESS_WIFI_STATE"):
        require(f"android.permission.{perm}" in permissions, f"{perm} permission missing")
    require("android.permission.INTERNET" not in permissions, "Unexpected INTERNET permission: app is offline-first")
    require(manifest.find("application").attrib.get(ANDROID_NS + "allowBackup") == "false", "Android Auto Backup must remain disabled")

    receivers = {r.attrib.get(ANDROID_NS + "name", "") for r in manifest.findall("application/receiver")}
    for receiver in (".PowerEventReceiver", ".StartupEventReceiver", ".ScheduleAlarmReceiver"):
        require(receiver in receivers, f"Manifest receiver missing: {receiver}")
    services = {s.attrib.get(ANDROID_NS + "name", ""): s for s in manifest.findall("application/service")}
    require(".MonitorService" in services, "MonitorService missing from manifest")
    if ".MonitorService" in services:
        require(services[".MonitorService"].attrib.get(ANDROID_NS + "foregroundServiceType") == "specialUse",
                "MonitorService needs foregroundServiceType=specialUse")
    require(".PrivacyFlipAccessibilityService" in services, "Accessibility service missing from manifest")

    for trigger in ("Screen turns off", "Screen turns on", "Phone unlocked", "Device starts", "Time schedule",
                    "Charger connected", "Charger disconnected", "Battery threshold", "Network changes",
                    "Wired headphones plugged", "Wired headphones unplugged", "Airplane mode changes",
                    "Motion / flip", "Manual quick action"):
        require(f'"{trigger}"' in code, f"Expected trigger not wired in source: {trigger}")
    for marker in (
        "class PowerEventReceiver", "class StartupEventReceiver", "class ScheduleAlarmReceiver",
        "object AutomationEventProcessor", "setAndAllowWhileIdle", "setExactAndAllowWhileIdle",
        'if (prefs.getBoolean("masterPaused", false)) return',
        "class MonitorService", "registerDefaultNetworkCallback(networkCallback)",
        "unregisterNetworkCallback(networkCallback)", "SensorManager.SENSOR_DELAY_NORMAL",
        "Intent.ACTION_SCREEN_OFF", "Intent.ACTION_SCREEN_ON", "Intent.ACTION_USER_PRESENT",
        "Intent.ACTION_HEADSET_PLUG", "Intent.ACTION_AIRPLANE_MODE_CHANGED", "Intent.ACTION_BATTERY_CHANGED",
        "FOREGROUND_SERVICE_TYPE_SPECIAL_USE", "object ActionExecutor", "setInterruptionFilter",
        "setTorchMode", "GLOBAL_ACTION_LOCK_SCREEN", "Settings.System.canWrite",
        "AutomationScheduler.scheduleNext", 'CreateDocument("application/json")', "OpenDocument()",
        "supportedCustomTriggers", "supportedCustomActions", "object PresetCatalog",
        "Show notification (permission needed)",
        "object LockUnlockEngine", "LockUnlockEngine.onEvent", "fun LockUnlockCard", "object RootShell",
        "object RootBackend", "object StandardBackend", "SystemState.read", "recovery pending",
    ):
        require(marker in code, f"Source marker missing: {marker}")

    home = sources["Home.kt"]
    home_page = home.split("fun HomePage", 1)[1].split("@Composable", 1)[0] if "fun HomePage" in home else ""
    require("val context = LocalContext.current" in home_page, "HomePage must obtain Context before scheduling alarms")
    require("LockUnlockCard()" in home_page, "HomePage must show the lock/unlock controls")
    require('Text("Run now")' in sources["RulesScreen.kt"], "Manual quick action needs a visible Run now control")
    require('rule.optString("id") != onlyRuleId' in sources["AutomationReceivers.kt"], "Manual execution must be scoped to the selected rule")

    preset_ids = [line.split('"')[1] for line in sources["Presets.kt"].splitlines() if line.strip().startswith("Preset(\"")]
    require(len(preset_ids) == len(set(preset_ids)) and preset_ids, "Preset ids must be unique and non-empty")
    action_ids = {line.split('"')[1] for line in sources["Actions.kt"].splitlines() if line.strip().startswith("ActionDef(\"")}
    for line in sources["Presets.kt"].splitlines():
        if line.strip().startswith("ActionOption(\""):
            aid = line.split('"')[1]
            require(aid in action_ids, f"Preset action id has no executor: {aid}")

    require("ProcessBuilder(\"su\", \"-c\", command)" in sources["Features.kt"], "Root shell must run only the allow-listed command")
    app_build = APP_BUILD.read_text(encoding="utf-8")
    for version in ("compileSdk = 35", "targetSdk = 35", "minSdk = 26", 'versionName = "0.3.0"'):
        require(version in app_build, f"Android build configuration missing: {version}")
    workflow = WORKFLOW.read_text(encoding="utf-8")
    require("gradle-version: '8.9'" in workflow, "CI should use the Gradle version compatible with AGP 8.7.3")
    require("python3 tools/verify_source.py" in workflow, "CI source-integrity step missing")
    require("gradle assembleDebug" in workflow, "CI APK build step missing")

if errors:
    print("Privacy Flip source checks FAILED:")
    for error in errors:
        print(f"  - {error}")
    sys.exit(1)

print("Privacy Flip source checks passed.")
print("Note: these structural checks do not compile Kotlin or replace Android device tests.")
