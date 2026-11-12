#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
  echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an installed Android SDK." >&2
  echo "Required packages: platforms;android-35 and build-tools;35.0.0." >&2
  exit 2
fi

if [[ -n "${GRADLE_CMD:-}" ]]; then
  GRADLE=("$GRADLE_CMD")
elif [[ -x "$ROOT/gradlew" ]]; then
  GRADLE=("$ROOT/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "ERROR: Gradle not found. Install Gradle 8.9+ or provide GRADLE_CMD." >&2
  exit 2
fi

python3 tools/verify_source.py
"${GRADLE[@]}" --no-daemon assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
  echo "ERROR: Gradle finished without creating $APK" >&2
  exit 3
fi
printf '\nBuild succeeded: %s\n' "$APK"
