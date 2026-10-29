# Privacy Flip — Offline Android Lock/Unlock Automation

Privacy Flip is a native Kotlin + Jetpack Compose Android app. Rules, logs, and preferences remain local. The app declares no `INTERNET` permission; the optional Shizuku integration uses local Android IPC and the device's system shell.

## v0.3.0 features

- Local rule creation, search, enable/disable, manual run, activity log, and JSON backup/restore.
- Optional foreground screen monitor. User must explicitly start it; Android notifications and background limits still apply.
- Do Not Disturb Lock/Unlock profile with Android Notification Policy Access.
- **Mobile Data via Shizuku (OFF on Lock / ON on Unlock)**: create one rule using the dedicated `Lock / Unlock (Mobile Data)` trigger and the Mobile Data action. The rule matches screen-off and phone-unlocked events. It calls the fixed `cmd phone data disable/enable` system-shell command through a Shizuku UserService; arbitrary commands are not accepted.
- Shizuku status/permission setup in Settings. The user must install and start Shizuku separately, then grant Privacy Flip's Shizuku permission.
- Scrollable rule builder so lower trigger/action options remain accessible.
- Offline-first; no account, server, or app network permission.

## Set up Mobile Data Lock/Unlock

1. Install Shizuku from its official project and start it. On Android 11 or later, Shizuku can be started with Wireless Debugging; after a reboot, it may need to be started again.
2. Open Privacy Flip → Settings → **Shizuku system access** → Connect / Grant. Approve the Shizuku permission prompt.
3. Open Rules → Add. Give the rule a name and choose **Mobile Data via Shizuku (OFF on Lock / ON on Unlock)**. The builder automatically selects **Lock / Unlock (Mobile Data)** as its trigger.
4. Save and enable that rule. In Settings, start **Background screen monitoring** and leave it enabled.
5. Lock the phone: Privacy Flip requests Mobile Data OFF. Unlock the phone: it requests Mobile Data ON. Check the Activity log for the command result.

## Important limitations

- This uses the shell command exposed by Android (`cmd phone data enable/disable`) through Shizuku's shell/root identity. A regular Android app permission alone is insufficient for Mobile Data control.
- Shizuku must be running and authorized when the event occurs. On non-root setups it may need to be restarted after reboot. If it is unavailable, Privacy Flip records a failure and does not claim success.
- `screen turns off` is the public Android broadcast used for lock/screen-off; it is not a guarantee that the device's secure keyguard has locked. Unlock is detected by `ACTION_USER_PRESENT`.
- A zero exit code means the command completed successfully; the app does not independently read the mobile-data switch state on every OEM/dual-SIM configuration. Physical-device verification on the target phone is still required.
- Android/OEM shell restrictions may vary. If `cmd phone data` returns a permission or command error on a particular device, this route will fail there; do not treat the feature as supported on that device until a real test passes.
- Wi-Fi, GPS, Bluetooth, camera/microphone privacy, and mobile data have different Android restrictions. Other action labels that open Settings remain shortcuts, not automatic toggles.
- Starting/stopping the screen-monitor service, notifications, battery optimization and OEM background policies can affect event delivery. Keep monitoring enabled and verify on the actual phone.

## Build

GitHub Actions builds a debug APK using JDK 17 and Gradle 8.9. A successful build proves compile/package only; it does not substitute for the Shizuku/device behavior test.

Package: `com.privacyflip.app`  
Target: Android 8.0+ (API 26+)
