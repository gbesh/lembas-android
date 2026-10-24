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
  sleep 5
  snap "qs-$n"
  adb shell cmd statusbar collapse
  echo "$n: $(adb shell settings get secure theme_customization_overlay_packages) night=$(adb shell settings get secure ui_night_mode)" >> "$S/theme.txt"
done
# search: a test contact first, then a few searches in the Red Book (the drawer)
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
rid=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -1 | sed 's/.*_id=//' | tr -d '\r ')
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$rid --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Samwise
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$rid --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:0400111222 --bind data2:i:2
adb shell pm grant com.middleearth.launcher android.permission.READ_CONTACTS
sleep 3
adb shell content query --uri content://com.android.contacts/contacts --projection display_name:has_phone_number > "$S/contacts.txt" 2>&1
echo "raw id: $rid" >> "$S/contacts.txt"
adb shell input keyevent KEYCODE_HOME; sleep 3
for q in ca sam zzqx 12*3+4; do
  adb shell am start -n com.middleearth.launcher/.MainActivity -a com.middleearth.launcher.SEARCH --es query "$q" > /dev/null
  sleep 4
  adb shell input keyevent KEYCODE_BACK   # hide the keyboard so the results show
  sleep 1
  snap "search-$(echo $q | tr -c 'a-z0-9' '_')"
  adb shell input keyevent KEYCODE_HOME; sleep 2
done
# the settings sheet (long-press on empty space), in the last look
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell input swipe 540 1500 540 1500 900
sleep 4
snap "settings"
adb shell input swipe 540 1900 540 700 400
sleep 2
snap "settings-more"
adb shell input keyevent KEYCODE_BACK
adb logcat -d | grep -i -E "lembas|middleearth|AndroidRuntime|ThemeOverlay" | tail -300 > "$S/logcat.txt" || true
exit 0
