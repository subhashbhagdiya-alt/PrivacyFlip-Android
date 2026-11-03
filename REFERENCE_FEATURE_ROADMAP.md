# Privacy Flip — Reference Feature Review & Implementation Roadmap

This project is independently developed. The public project dorumrr/privacyFlip is used only as a product/feature reference. Do not copy its source code, assets, name/branding, or license-specific implementation.

Reference reviewed: https://github.com/dorumrr/privacyFlip
Reference documentation reviewed: README.md and WORKFLOW.md (public repository, reviewed 2026-10-30).

## Product direction
Evolve this app from a collection of lock/unlock rules and settings shortcuts into a focused privacy-protection controller:
- One clear Protection On/Off state.
- Per-feature controls for Wi-Fi, Mobile Data, Bluetooth, Location, NFC, Camera, Microphone, Airplane Mode and Battery Saver, with capability-aware availability.
- Lock and unlock delays, independently configurable.
- Restore only states changed by Privacy Flip; never blindly turn on a feature that the user had already turned off.
- Optional app exemptions and safe-use checks (active calls, hotspot, connected audio/media, payment/NFC use).
- Optional keep-off-while-locked retry with strict retry limit and clear user control.
- Multiple privilege backends: Shizuku first, Root optional, Dhizuku only for supported features and OS versions. Never claim unsupported controls work.
- Boot/restart recovery, battery optimization guidance, permission health and diagnostics.
- Quick Settings tile and home-screen widget after core engine is reliable.

## Important engineering rules
1. Treat screen-off and authenticated unlock as distinct events. Do not equate screen-on with unlock.
2. Use a single state machine for lock/unlock, delays, exemptions, retry limits and restoration.
3. Persist the original state and whether Privacy Flip changed each feature. Restore only app-changed states.
4. Verify actual state after every command; a zero shell exit code alone is not proof.
5. Fail safely: on missing privilege, unsupported OS/OEM command, or uncertain state, log and notify rather than guessing.
6. Keep camera/microphone sensor privacy behavior separate from radio/network controls; respect Android version constraints and active calls.
7. No silent privilege escalation. Each backend requires explicit user setup and transparent capability reporting.
8. Do not implement Dhizuku/Root until each backend has isolated tests and an accurate support matrix.

## Phased implementation
### Phase 1 — Architecture and reliability
- Replace action-string-only execution with typed privacy feature IDs and target states.
- Introduce privilege backend interface and capability detection.
- Add state snapshot, changed-by-app tracking, verified result model and safe restoration.
- Add unit tests for state transitions, delays, retries, exemptions and restoration.

### Phase 2 — User-facing privacy controls
- Protection master switch and per-feature ON-lock / restore-unlock preferences.
- Lock delay and unlock delay controls.
- Clear setup screen with privilege status and feature-by-feature support.
- Keep existing local storage and export/import compatibility.

### Phase 3 — Smart safety and exemptions
- App exemptions using Usage Access, only with explicit opt-in.
- Hotspot, active call, connected audio/media and NFC/payment safeguards where reliable.
- Keep-off-while-locked with bounded retries and audit logs.

### Phase 4 — Platform integrations
- Shizuku backend hardening and device/version command verification.
- Root backend as optional separate implementation.
- Dhizuku backend only for supported controls and Android versions.
- Quick Settings tile, widget, boot recovery and battery optimization guidance.

### Phase 5 — Release quality
- Automated unit/instrumentation tests, CI APK build, permission review, privacy review and device test matrix.
- Never call the app complete based on CI compilation alone; verify on real devices.

## Current baseline
The existing Compose app has local rules, screen monitor service, Shizuku-backed paired actions, DND, Device Admin screen lock, setup wizard, permission checker, app details, settings shortcuts and JSON backup. The current screen monitor's paired action implementation does not yet provide full original-state restoration, delay controls, exemptions, or a unified protection state machine. These are priority gaps.
