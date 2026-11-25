#!/usr/bin/env bash
# Start check on a connected emulator or device: install the APK over what is
# there, start the app, and fail when it crashes or shows no screen of its own.
# It proves the app starts; it does not prove that playback works.
#
#   scripts/emulator_smoke.sh path/to/app.apk [adb-serial]
set -euo pipefail

apk=${1:?usage: emulator_smoke.sh APK [adb-serial]}
adb=${ADB:-adb}
[ -n "${2:-}" ] && adb="$adb -s $2"
package=${PACKAGE:-com.iptv.player}

$adb install -r "$apk" >/dev/null
# The package manager force-stops the package once more just after install
# returns; a start inside that moment is killed and looks like a failure.
sleep 5
$adb logcat -c
$adb shell monkey -p "$package" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 ||
  $adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep "${WAIT_SECONDS:-25}"

if $adb logcat -d -b crash | grep -q "$package"; then
  $adb logcat -d -b crash | tail -40
  echo "FAIL: crash during start" >&2
  exit 1
fi
# Tolerant of the differing dumpsys spellings between Android levels.
front=$($adb shell dumpsys activity activities | grep -E "(mResumedActivity|topResumedActivity|ResumedActivity)" | head -1 || true)
echo "front: $front"
case "$front" in
  *"$package"*) echo "OK: $package is in front, no crash" ;;
  *) echo "FAIL: $package is not in front" >&2; exit 1 ;;
esac
