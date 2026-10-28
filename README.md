# Privacy Flip — Native Android App

Privacy Flip is an offline-first native Android prototype built with Kotlin and Jetpack Compose.

## Current prototype (v0.2.0)
- Android 8.0+ support (API 26+)
- Home dashboard with rule counts and Master Pause
- Local automation-rule list with add, enable/disable, run-now logging, and delete
- Activity log stored on device
- Light/dark theme preference
- Advanced Developer Mode diagnostics
- JSON backup export and import/restore using Android's document picker, with a confirmation prompt before replacing current data
- Screen ON/OFF triggers detected while the app UI is active; matching enabled rules update the local run count and activity log
- Phone-start trigger uses Android BOOT_COMPLETED after the app has been launched at least once; matching enabled rules update local counts and logs
- No network permission or account required
- GitHub Actions workflow builds a debug APK and uploads it as an artifact

## Important limitations
This is an early UI and local-rule prototype. **Run now records a demo action; it does not yet change Android system privacy settings.** Screen ON/OFF detection currently works only while the app UI is active; it is not a background automation service. Phone-start detection records a demo event only and does not change system settings. Scheduled triggers, app-open/location triggers, and real system-level privacy controls are not implemented yet. Backup import/restore is available for the local rules and preferences. Android restricts many system settings to privileged apps or user-granted special access, so each real action must be implemented with explicit permission and compatibility checks.

## Build
The GitHub Actions workflow runs Gradle 8.9 with JDK 17 to build the debug APK. After a successful workflow run, download the PrivacyFlip-debug-apk artifact from the Actions run page.

For local builds, install JDK 17 and Gradle 8.9, then run:

~~~sh
gradle assembleDebug
~~~

Package: com.privacyflip.app
