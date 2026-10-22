#!/usr/bin/env bash
# Test builds only: installs Lembas on a phone emulator, grants the one-time permission (what the
# in-app setup does over Wireless debugging), and captures each look: home screen, quick settings.
set -u
mkdir -p shots
S="$PWD/shots"
snap() { adb exec-out screencap -p > "$S/$1.png"; }
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 30
adb root || true; sleep 4
adb install -r out/lembas-launcher.apk | tee "$S/install.txt"
adb shell cmd package set-home-activity com.middleearth.launcher/.MainActivity >> "$S/install.txt" 2>&1
adb shell pm grant com.middleearth.launcher android.permission.WRITE_SECURE_SETTINGS >> "$S/install.txt" 2>&1
adb shell settings put global auto_time 0
adb shell settings put global auto_time_zone 0
adb shell cmd alarm set-timezone Australia/Sydney || adb shell setprop persist.sys.timezone Australia/Sydney
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
for t in 0921:shire 1530:redbook 2005:starlit 0130:elven; do
  hm=${t%%:*}; n=${t##*:}
  adb shell "date $(date +%m%d)${hm}$(date +%Y).00" > /dev/null
  adb shell input keyevent KEYCODE_HOME
  adb shell am start -W -n com.middleearth.launcher/.MainActivity > /dev/null
  sleep 8
  snap "home-$n"
  adb shell cmd statusbar expand-settings
  sleep 3
  snap "qs-$n"
  adb shell cmd statusbar collapse
  echo "$n: $(adb shell settings get secure theme_customization_overlay_packages) night=$(adb shell settings get secure ui_night_mode)" >> "$S/theme.txt"
done
adb logcat -d | grep -i -E "lembas|middleearth|AndroidRuntime|ThemeOverlay" | tail -300 > "$S/logcat.txt" || true
exit 0
