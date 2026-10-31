# Privacy Flip — Offline Android Lock/Unlock Automation

Privacy Flip is a native Kotlin + Jetpack Compose Android app. Rules, logs, and preferences are stored locally. It does not need an account, server, or app network permission. The optional Shizuku bridge uses local Android IPC and a restricted list of Android shell commands.

## v0.3.0 features

- Create, search, enable/disable, and manually run local automation rules; view activity logs and export/import JSON backups.
- Optional foreground screen monitor for screen-off and phone-unlocked events.
- Do Not Disturb lock/unlock profile using Android Notification Policy Access.
- Optional Shizuku-backed automatic actions:
  - **Auto: Mobile data OFF on lock / ON on unlock**
  - **Auto: Wi-Fi OFF on lock / ON on unlock**
  - **Auto: Bluetooth OFF on lock / ON on unlock**
  - **Auto: Location OFF on lock / ON on unlock**
  - **Auto: Camera privacy ON on lock / OFF on unlock**
  - **Auto: Microphone privacy ON on lock / OFF on unlock**
- **App Details** page in Settings: version, package name, Android compatibility, privacy model, supported actions and limitations.
- Optional **Device Admin** permission for the explicit screen-lock action only. It does not grant Mobile Data or full system control.
- Normal app features are available without Shizuku; advanced direct lock/unlock system automation still uses optional Shizuku where Android restricts normal apps.
- Shizuku status and permission setup in Settings. The user must install and start Shizuku separately and grant Privacy Flip permission.
- Regular settings shortcuts remain available for actions that Android does not allow this app to automate directly.
- Additional Android shortcuts: Display, Sound, Battery, VPN, Accessibility, Security, Storage, NFC, Cast, and Usage Access settings.

## App details and permission model

Open Privacy Flip → Settings → **App Details** to view the installed version, package ID, Android API details, local-storage/network model, and which features require optional Shizuku access. Use **Android system app details** for the system-provided Android App Info page.

Device Admin is optional and requests the force-lock policy only. It is only used by the explicit screen-lock action; it is not a replacement for system-level access and does not toggle Mobile Data.

## Set up automatic lock/unlock actions

1. Install and start Shizuku. On Android 11 or later, use Wireless Debugging to start it. After reboot, non-root Shizuku may need to be started again.
2. Open Privacy Flip → Settings → **Shizuku system access** → **Connect / Grant**. Approve the Shizuku permission request.
3. Open Rules → Add. Enter a name and choose an **Auto:** action. The rule picker sets the lock/screen-off trigger for these paired actions.
4. Save and enable the rule.
5. Open Settings and start **Background screen monitoring**. Keep it enabled.
6. Lock the phone and then unlock it. Check Activity log for command results. The automatic action itself does not need a notification tap.

## How the paired controls behave

- Mobile data, Wi-Fi, Bluetooth and Location are requested **OFF on screen-off/lock** and **ON on phone unlock**.
- Camera and microphone privacy controls request privacy **ON on screen-off/lock** and **OFF on phone unlock**.
- The Do Not Disturb profile is handled by Android notification policy API if its special access is granted.
- These are explicit allowlisted commands; the app does not accept arbitrary shell commands from a rule.

## Limitations and safety notes

- Shizuku must be running and authorized when the event occurs. The automation requires this additional user-approved service; an ordinary app permission does not grant all of these system controls.
- Android’s screen-off broadcast is not a guarantee that the secure keyguard has locked. Unlock is detected via ACTION_USER_PRESENT.
- Command completion is logged, but a successful shell exit code does not independently verify every UI switch’s actual state on every OEM build or dual-SIM configuration.
- Android/OEM shell restrictions differ. A command may fail on a specific device; the app logs that failure and shows a diagnostic notification. Treat each auto action as unverified on a phone until physically tested there.
- Camera/microphone sensor privacy shell commands require Android 12 or later. Other commands can vary by Android version and manufacturer.
- Android battery management, stopping Shizuku, stopping the foreground monitor, and notification/background policies can affect event delivery. Keep the monitor running and verify the result on the target device.

## Build

GitHub Actions uses JDK 17 and Gradle 8.9 to assemble a debug APK. CI success means compile and package succeeded; it does not replace the real-device automation test.

Package: com.privacyflip.app  
Target: Android 8.0+ (API 26+)