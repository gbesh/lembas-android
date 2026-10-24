#!/usr/bin/env bash
# Test builds only: Lembas on a bigger screen (passed in as $1, for the file names): the home
# screen and the still wallpaper on the lock screen, in a day look and a night look.
set -u
mkdir -p shots
S="$PWD/shots"
N="$1"
snap() { adb exec-out screencap -p > "$S/$N-$1.png"; }
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 30
adb root || true; sleep 4
adb install -r out/lembas-launcher.apk > /dev/null
adb shell cmd package set-home-activity com.middleearth.launcher/.MainActivity > /dev/null 2>&1
adb shell settings put global auto_time 0
adb shell cmd alarm set-timezone Australia/Sydney || true
adb shell wm size > "$S/$N-size.txt"; adb shell wm density >> "$S/$N-size.txt"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
for t in 0921:shire 0130:elven; do
  hm=${t%%:*}; n=${t##*:}
  adb shell "date $(date +%m%d)${hm}$(date +%Y).00" > /dev/null
  adb shell input keyevent KEYCODE_HOME
  adb shell am start -W -n com.middleearth.launcher/.MainActivity > /dev/null
  sleep 12
  snap "home-$n"
  adb shell input keyevent KEYCODE_SLEEP; sleep 2; adb shell input keyevent KEYCODE_WAKEUP; sleep 3
  snap "lock-$n"
  adb shell wm dismiss-keyguard || true; sleep 2
done
# a foldable: unfold and look again
if adb shell cmd device_state print-states 2>/dev/null | grep -q -i open; then
  adb shell cmd device_state state 2 >/dev/null 2>&1 || true; sleep 8
  adb shell wm size >> "$S/$N-size.txt"
  adb shell input keyevent KEYCODE_HOME; sleep 8
  snap "unfolded-home"
  adb shell input keyevent KEYCODE_SLEEP; sleep 2; adb shell input keyevent KEYCODE_WAKEUP; sleep 3
  snap "unfolded-lock"
fi
exit 0
