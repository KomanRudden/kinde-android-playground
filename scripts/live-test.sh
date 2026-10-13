#!/usr/bin/env bash
# Live checks on the emulator with the session you signed in with in the app.
#
#   1. ./gradlew :app:installDebug, open the app, Sign-in lab > login()
#   2. ./scripts/live-test.sh
#
# connectedAndroidTest uninstalls the app afterwards, which would delete the
# session, so this installs both APKs and runs the instrumentation directly.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

"$ADB" devices | grep -q "device$" || { echo "No emulator running. Start one with ./scripts/emulator.sh."; exit 1; }

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
# -r keeps app data, so the signed-in session survives.
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

"$ADB" logcat -c
OUT="$("$ADB" shell am instrument -w -r \
  -e class com.kinde.playground.LiveChecksTest \
  com.kinde.playground.test/androidx.test.runner.AndroidJUnitRunner)"

# Print each check (the test logs one line per check), then the verdict.
"$ADB" logcat -d -v raw -s LiveChecks:I | grep -v "^-----" | sed 's/ | .*//'
if echo "$OUT" | grep -q "AssumptionViolatedException"; then
  echo "$OUT" | grep -o "AssumptionViolatedException: [^	]*" | head -1
  exit 0
fi
echo "$OUT" | grep -E "^OK \(|^FAILURES|Tests run" | tail -2
echo "$OUT" | grep -q "^OK ("
