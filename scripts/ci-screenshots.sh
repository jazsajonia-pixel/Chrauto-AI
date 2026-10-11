#!/usr/bin/env bash
# Installs the debug APK on the CI emulator and captures UI screenshots for review.
set -x
OUT=screenshots; mkdir -p "$OUT"
PKG=com.chrauto.ai
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell am start -n $PKG/.MainActivity; sleep 6
adb exec-out screencap -p > "$OUT/01-main-setup.png"
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.accessibility.AccessibilityAgentService
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell input keyevent KEYCODE_HOME; sleep 3
adb exec-out screencap -p > "$OUT/02-home-bubble.png"
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
X=$(( W - 47 * DENS / 160 )); Y=$(( H - 76 * DENS / 160 ))
adb shell input tap $X $Y; sleep 2
adb exec-out screencap -p > "$OUT/03-panel-expanded.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver; sleep 1.5
adb exec-out screencap -p > "$OUT/04-cursor-thinking.png"
sleep 1.6
adb exec-out screencap -p > "$OUT/05-cursor-moving.png"
sleep 5
adb exec-out screencap -p > "$OUT/06-done.png"
adb shell am start -n $PKG/.MainActivity; sleep 3
adb exec-out screencap -p > "$OUT/07-main-after-run.png"
ls -la "$OUT"
