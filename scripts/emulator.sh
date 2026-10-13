#!/usr/bin/env bash
# Creates (once) and starts the emulator the playground is tested on.
#
#   ./scripts/emulator.sh            # create if missing, boot, wait until ready
#   ./scripts/emulator.sh --wipe     # boot with a clean data partition
#   ./scripts/emulator.sh --headless # no window (CI)
#
# AVD: Kinde_Playground_API36 — Pixel 7, Android 16 (API 36), Google APIs image
# (ships Chrome, so Custom Tabs work for sign-in; no Play Store, so `adb root`
# works for inspecting app data). On Apple Silicon the arm64-v8a image is used,
# on Intel x86_64.
set -euo pipefail

AVD_NAME="${KINDE_AVD:-Kinde_Playground_API36}"
API=36
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME
ABI="arm64-v8a"
[[ "$(uname -m)" == "x86_64" ]] && ABI="x86_64"
IMAGE="system-images;android-${API};google_apis;${ABI}"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"

for tool in "$EMULATOR" "$ADB"; do
  [[ -x "$tool" ]] || { echo "Missing $tool. Install the Android SDK emulator and platform-tools (Android Studio > SDK Manager)."; exit 1; }
done

if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  [[ -x "$AVDMANAGER" ]] || { echo "Missing avdmanager. Install 'Android SDK Command-line Tools' in the SDK Manager."; exit 1; }
  if [[ ! -d "$ANDROID_HOME/system-images/android-${API}/google_apis/${ABI}" ]]; then
    echo "Installing $IMAGE (one-time, ~1.5 GB)…"
    yes | "$SDKMANAGER" --install "$IMAGE" "platforms;android-${API}" "platform-tools" "emulator" >/dev/null
  fi
  echo "Creating AVD $AVD_NAME…"
  echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" -d pixel_7 >/dev/null
  CONFIG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  {
    echo "hw.ramSize=4096"
    echo "disk.dataPartition.size=8G"
    echo "hw.keyboard=yes"
    echo "hw.gpu.enabled=yes"
    echo "hw.gpu.mode=auto"
  } >> "$CONFIG"
fi

if "$ADB" devices | grep -q "^emulator-"; then
  echo "An emulator is already running:"
  "$ADB" devices | grep "^emulator-"
  exit 0
fi

ARGS=(-avd "$AVD_NAME" -netdelay none -netspeed full -no-boot-anim)
for arg in "$@"; do
  case "$arg" in
    --wipe) ARGS+=(-wipe-data) ;;
    --headless) ARGS+=(-no-window -no-audio) ;;
    *) echo "Unknown option $arg"; exit 1 ;;
  esac
done

echo "Starting $AVD_NAME…"
nohup "$EMULATOR" "${ARGS[@]}" >/tmp/kinde-playground-emulator.log 2>&1 &

"$ADB" wait-for-device
printf "Waiting for boot"
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  printf "."
  sleep 2
done
echo " ready."
# Animations make UI tests flaky.
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" devices | grep "^emulator-"
