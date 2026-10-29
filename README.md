# Privacy Flip — Offline Android Lock/Unlock Automation

Privacy Flip is a native Kotlin + Jetpack Compose Android app. Rules, logs, and preferences remain local. The app manifest does not declare `INTERNET`; optional Shizuku calls use local Android IPC and the device's shell identity.

## v0.3.0 features

- Local rule creation, search, enable/disable, manual run, activity log, and JSON backup/restore.
- Optional foreground screen monitor started by the user; Android notifications, battery rules and OEM limits still apply.
- Do Not Disturb Lock/Unlock handling with Android Notification Policy Access.
- Optional Shizuku bridge that exposes a fixed allowlist of lock/unlock system-control commands. No arbitrary shell input is accepted.
- Scrollable rule builder and Shizuku status/permission card.

## Set up automatic lock/unlock controls

1. Install Shizuku using its official project and start it. On Android 11+, use Wireless Debugging; after a reboot, Shizuku may need to be started again.
2. Open Privacy Flip → Settings → **Shizuku system access** → **Connect / Grant**. Approve the Shizuku permission prompt.
3. Open Rules → Add. Choose one of the `Auto:` actions. The rule editor selects **Phone locks / screen turns OFF** as the paired trigger.
4. Save and enable the rule. In Settings, start **Background screen monitoring** and leave it enabled.
5. Lock the phone and unlock it. Radio-like controls request OFF on screen-off and ON on unlock. Camera/Microphone privacy controls request privacy ON while locked and OFF when unlocked.
6. Check Activity for the shell result. On command failure the app records the failure and posts a notification; a success exit code means the shell command returned successfully, not that the OEM switch state was independently verified.

### Current `Auto:` actions

- **Mobile data:** `svc data disable/enable` via the system shell. AOSP exposes this command, but OEM/carrier builds can still restrict or change behavior.
- **Wi-Fi:** `svc wifi disable/enable`; behavior may vary by Android version/device policy.
- **Bluetooth:** fixed `svc bluetooth disable/enable` command; only works on system builds that expose this command.
- **Location:** `cmd location set-location-enabled false/true --user current`.
- **Camera privacy / Microphone privacy:** Android 12+ `cmd sensor_privacy enable/disable USER_ID camera|microphone`.

## Important limitations

- This is not an internet-dependent feature. The app doesn't need cellular data to detect local screen events or issue a local Shizuku request; turning Mobile Data off will not directly disable local app logic.
- Normal Android app permissions do not grant direct Mobile Data control. Shizuku must be running and Privacy Flip's Shizuku permission must be granted. On non-root setups, restart Shizuku after reboot when required.
- Android `ACTION_SCREEN_OFF` indicates the screen turned off, not a guaranteed secure-keyguard lock. `ACTION_USER_PRESENT` indicates the user became present/unlocked.
- Android shell command availability can vary by OEM, Android release, device policy, carrier configuration and multi-SIM setup. Keep a feature enabled only after confirming it works on your device. No physical-device test has yet verified the current build on the user's iQOO Z3.
- Actions not labeled `Auto:` are settings shortcuts, not automatic ON/OFF toggles.
- GitHub Actions verifies compilation and APK packaging only; it does not prove mobile-data switching or background trigger delivery on a physical phone.

## Build

GitHub Actions builds a debug APK with JDK 17 and Gradle 8.9. Download the `PrivacyFlip-debug-apk` artifact from a successful Actions run.

Package: `com.privacyflip.app`  
Target: Android 8.0+ (API 26+)
