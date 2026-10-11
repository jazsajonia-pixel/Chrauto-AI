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
# End-to-end device-layer self test (gesture taps, stale nodes, typing + Enter).
adb logcat -c
adb shell am broadcast -n $PKG/.debug.SelfTestReceiver
sleep 9
adb exec-out screencap -p > "$OUT/00-selftest.png"
adb logcat -d -s ChrautoSelfTest:I | tee "$OUT/selftest.txt"
grep -q "SELFTEST done" "$OUT/selftest.txt" && ! grep -q "=FAIL" "$OUT/selftest.txt" && echo "SELFTEST OK" || { echo "SELFTEST FAILED"; touch "$OUT/SELFTEST_FAILED"; }
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_HOME; sleep 3
adb exec-out screencap -p > "$OUT/02-home-bubble.png"
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
X=$(( W - 47 * DENS / 160 )); Y=$(( H - 76 * DENS / 160 ))
adb shell input tap $X $Y; sleep 2
adb exec-out screencap -p > "$OUT/03-panel-expanded.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver; sleep 1.2
adb exec-out screencap -p > "$OUT/04-cursor-thinking.png"
sleep 1.9
adb exec-out screencap -p > "$OUT/05-cursor-moving.png"
sleep 0.5
adb exec-out screencap -p > "$OUT/05b-cursor-tap.png"
sleep 5
adb exec-out screencap -p > "$OUT/06-done.png"
adb shell am start -n $PKG/.MainActivity; sleep 3
adb exec-out screencap -p > "$OUT/07-main-after-run.png"
# Chron voice states (scripted, the emulator has no microphone input)
adb shell input keyevent KEYCODE_HOME; sleep 1.5
adb shell am broadcast -n $PKG/.debug.DemoReceiver --es chron listening; sleep 1.5
adb exec-out screencap -p > "$OUT/08-chron-listening.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver --es chron reply; sleep 1.2
adb exec-out screencap -p > "$OUT/09-chron-reply.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver --es chron live; sleep 1.2
adb exec-out screencap -p > "$OUT/09b-chron-live.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver --es chron duplex; sleep 1.2
adb exec-out screencap -p > "$OUT/09c-chron-duplex.png"
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2; adb shell input swipe 540 1500 540 600 300; sleep 1
adb exec-out screencap -p > "$OUT/10-main-chron-card.png"
adb shell am broadcast -n $PKG/.debug.DemoReceiver --es chron off
ls -la "$OUT"
[ -f "$OUT/SELFTEST_FAILED" ] && exit 1 || exit 0
