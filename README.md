# Privacy Flip: offline Android automation (no root, no Shizuku, no ADB)

Native Kotlin + Jetpack Compose app. Pick a trigger, pick an action, and it runs on your phone with no account and no internet permission. Everything uses normal Android APIs.

## What works

**Triggers** (run in the background through a small foreground service)
Screen off/on, phone unlocked, charger connected/disconnected, battery below your threshold, network change, wired headphones plugged/unplugged, airplane mode change, face-down flip, device boot, daily time schedule, and a manual Run now button.

**Actions**

| Action | Needs |
|---|---|
| Activity log, notification | Notification permission (Android 13+) |
| Vibrate, sound mode Normal/Vibrate, flashlight | Nothing |
| Silent mode, Do Not Disturb on/off | Do Not Disturb access |
| Brightness low/high | Modify system settings |
| Lock screen | Accessibility service (reads nothing on screen) |
| Wi-Fi / Bluetooth | One-tap shortcut notification (see below) |

The Settings tab shows each permission with a button to grant it, and Diagnostics shows what is and is not ready. 11 ready-made presets cover the common cases, and the Rules tab builds custom IF-THEN rules.

## What cannot work without root or Shizuku

Android 10+ no longer lets normal apps switch Wi-Fi on or off, and Android 13+ blocks Bluetooth switching. Privacy Flip sends a notification that opens the Wi-Fi panel or Bluetooth settings in one tap instead. Silent Wi-Fi/Bluetooth, airplane mode toggling and force-stopping other apps are not possible without elevated access.

## Install

Open the latest successful run under **Actions**, download the `privacy-flip-debug-apk` artifact, unzip it and install `app-debug.apk`. Then open Settings in the app and grant only the permissions you need. For reliable background triggers, set battery usage to Unrestricted.

## Build

JDK 17, Gradle 8.9 and Android SDK 35. Run `./tools/build_debug_apk.sh`, or open the folder in Android Studio.
`python3 tools/verify_source.py` runs quick structural checks (not a compile). CI runs both on every push and pull request.

## Status

Version 0.2.0. Needs real-device testing: trigger delivery after the phone kills background apps, exact-alarm timing, and OEM-specific accessibility and battery settings. Treat an action as working only after you have seen it log success in Diagnostics.
