#!/usr/bin/env bash
# Full regression for the Kinde Android SDK.
#
#   ./scripts/regression.sh           SDK unit suite, playground JVM suite (SDK contract
#                                     tests against a fake Kinde), debug + minified builds,
#                                     instrumented tests on the running emulator
#   ./scripts/regression.sh --quick   JVM suites only (no emulator, no minified build)
#   ./scripts/regression.sh --live    also run the host live tests against your Kinde business
#
# Writes reports/regression.html and reports/regression.json, and copies the JSON
# into the app on the emulator so the Regression page can show it.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
SDK="${KINDE_SDK_PATH:-$ROOT/../kinde-sdk-android}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

QUICK=0; LIVE=false
for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=1 ;;
    --live) LIVE=true ;;
    *) echo "Unknown option $arg"; exit 2 ;;
  esac
done

STATUS=0
step() { printf "\n\033[1m== %s\033[0m\n" "$1"; }
run() { "$@" || STATUS=1; }

[[ -f local.properties ]] || echo "sdk.dir=$ANDROID_HOME" > local.properties
[[ -f "$SDK/local.properties" ]] || echo "sdk.dir=$ANDROID_HOME" > "$SDK/local.properties"

step "1/4 SDK unit tests (kinde-sdk-android, Robolectric)"
rm -rf "$SDK/sdk/build/test-results/testDebugUnitTest"
run "$SDK/gradlew" -p "$SDK" :sdk:testDebugUnitTest --console=plain -q

step "2/4 Playground JVM suite: SDK contract tests against a fake Kinde over HTTPS"
rm -rf app/build/test-results/testDebugUnitTest
run ./gradlew :app:testDebugUnitTest -Plive=$LIVE --console=plain -q

if [[ $QUICK -eq 0 ]]; then
  step "3/4 Builds: debug and minified (R8 with only the SDK's consumer rules)"
  run ./gradlew :app:assembleDebug :app:assembleMinified --console=plain -q

  step "4/4 Instrumented tests on the emulator"
  rm -rf app/build/outputs/androidTest-results
  if "$ADB" devices | grep -q "device$"; then
    # Keep the app installed afterwards: uninstalling would delete a signed-in
    # session, and LiveChecksTest runs only when there is one.
    run ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain -q
  else
    echo "No emulator running: start one with ./scripts/emulator.sh. Skipping."
  fi
fi

step "Report"
SUITES=(
  "SDK unit tests|kinde-sdk-android's own Robolectric suite, run against the checkout on disk.|$SDK/sdk/build/test-results/testDebugUnitTest"
  "SDK contract suite (JVM)|The real SDK against a fake Kinde over HTTPS: every public API, plus @KnownIssue tests for open SDK defects. LiveKindeTest runs with --live.|$ROOT/app/build/test-results/testDebugUnitTest"
)
[[ $QUICK -eq 0 ]] && SUITES+=("Instrumented (emulator)|Pages, storage, activities, self-checks and the security audit on Android.|$ROOT/app/build/outputs/androidTest-results")
python3 scripts/junit_report.py reports "${SUITES[@]}" || STATUS=1
echo "HTML report: $ROOT/reports/regression.html"

if "$ADB" devices | grep -q "device$" && "$ADB" shell pm path com.kinde.playground >/dev/null 2>&1; then
  "$ADB" shell "run-as com.kinde.playground sh -c 'mkdir -p files && cat > files/regression.json'" < reports/regression.json \
    && echo "Copied to the app: open Regression > Host suites > Reload results."
else
  echo "Install the app (./gradlew :app:installDebug) to see these results on the Regression page."
fi

exit $STATUS
