# Privacy Flip — Native Android App

Privacy Flip is an offline-first native Android app prototype built with Kotlin and Jetpack Compose.

## Current features (v0.2.0)

- Android 8.0+ support (API 26+)
- Home dashboard with rule counts, enabled/disabled rule health summary, and Master Pause
- Local automation rules: create, search, enable/disable, run manually, and delete
- Supported triggers: Manual, Screen turns ON, Screen turns OFF, Phone unlocked, and Phone starts
- Supported actions: open Wi-Fi settings, Location/GPS settings, Bluetooth settings, Internet connectivity panel, this app's permissions, Android Privacy settings, and notification settings
- Do Not Disturb toggle using Android's Notification Policy Access special permission; if access is missing, the app opens the system access screen and the user must grant it before retrying
- Optional user-started foreground screen monitor with persistent notification; matching screen/unlock rules create actionable notifications
- Phone-start rules are evaluated after Android delivers BOOT_COMPLETED; supported actions can be started from their notification
- Local activity log with copy and clear confirmation
- Light/dark theme preference and Advanced Developer Mode diagnostics
- JSON backup export and import/restore using Android's document picker, with validation and confirmation
- Offline-first: no account and no network permission required
- GitHub Actions builds a debug APK artifact

## Android permissions and limits

Privacy Flip does not silently grant itself privileges. The user must grant Do Not Disturb access in Android Settings before that action can change the interruption filter. Android may show its own confirmation or settings UI for other actions. Most settings shortcuts only open the relevant Android settings page; the user completes the change there.

Screen ON/OFF and Phone unlocked triggers require the user to start the foreground monitoring service. Android 13+ notification permission is requested when needed. Android battery policies or vendor-specific background restrictions may stop the service. Phone-start rules require the app to have been launched at least once and Android to deliver BOOT_COMPLETED. Notifications are only shown if Android permits them.

This app does **not** directly toggle Wi-Fi, mobile data, GPS, camera/microphone access for other apps, or other restricted system controls. Scheduled, app-open, and location triggers are not included in this build. Test the app on a physical device before relying on any automation; GitHub Actions success confirms compilation and APK packaging, not device behavior.

## Build

The GitHub Actions workflow uses JDK 17 and Gradle 8.9 to build the debug APK. Download the `PrivacyFlip-debug-apk` artifact from a successful Actions run.

For local builds, install JDK 17 and Gradle 8.9, then run:

```sh
gradle assembleDebug
```

Package: `com.privacyflip.app`
