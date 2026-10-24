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
# a widget on the home screen (the emulator's own Clock widget), added the way the picker does,
# and a notification to preview
adb shell cmd notification post -S bigtext -t "Gandalf" lembas-test "A wizard is never late, nor is he early." > /dev/null 2>&1 || true
# the settings sheet (long-press on empty space), in the last look
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell input swipe 540 1500 540 1500 900
sleep 4
snap "settings"
adb shell input swipe 540 1900 540 700 400
sleep 2
snap "settings-more"
adb shell input keyevent KEYCODE_BACK
# widgets: add the emulator's clock widget through Lembas's own picker (animations off so the
# screen can be read)
adb shell am force-stop com.middleearth.launcher
adb shell "sed -i 's#</map>#<boolean name=\"animations\" value=\"false\" /></map>#' /data/data/com.middleearth.launcher/shared_prefs/lembas.xml"
adb shell settings put global animator_duration_scale 0
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell input swipe 540 1500 540 1500 900; sleep 3
for i in 1 2 3 4 5 6; do python3 tools/tap.py "add a widget" && break; adb shell input swipe 540 1900 540 900 300; sleep 1; done
sleep 3
snap "widget-picker"
python3 tools/tap.py "clock" || python3 tools/tap.py "analog"
sleep 3
snap "widget-bind"
python3 tools/tap.py "always" 2>> "$S/widgets-taps.txt"; python3 tools/tap.py "=create" 2>> "$S/widgets-taps.txt" || python3 tools/tap.py "=allow" 2>> "$S/widgets-taps.txt"
sleep 4
# the clock widget opens its own setup screen: finish it (backing out would cancel the widget)
snap "widget-config"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml | grep -o -E '(text|content-desc)="[^"]+"' > "$S/widget-config.txt" || true
for w in "=scallop" "=numeral" "=done" "=save" "=ok" "=add" "=add widget" "=apply" "=confirm" "=navigate up"; do python3 tools/tap.py "$w" 2>> "$S/widgets-taps.txt" && break; done
sleep 3
snap "widget-home"
adb shell cat /data/data/com.middleearth.launcher/shared_prefs/lembas.xml | grep -i widget > "$S/widgets.txt" 2>&1
adb logcat -d | grep -i -E "Lembas *:|widget" | tail -40 >> "$S/widgets.txt"
adb logcat -d | grep -i -E "lembas|middleearth|AndroidRuntime|ThemeOverlay" | tail -300 > "$S/logcat.txt" || true
# the Red Book's icon styles, in a light look and a dark one, and a row being pressed
P=/data/data/com.middleearth.launcher/shared_prefs/lembas.xml
for t in 0921:shire 0130:elven; do
  hm=${t%%:*}; n=${t##*:}
  adb shell "date $(date +%m%d)${hm}$(date +%Y).00" > /dev/null
  for style in tonal disc bare classic; do
    adb shell am force-stop com.middleearth.launcher
    adb shell "sed -i -e 's#<string name=\"drawer_icons\">[a-z]*</string>##' -e 's#<boolean name=\"keyboard\" value=\"[a-z]*\" />##' -e 's#</map>#<string name=\"drawer_icons\">$style</string><boolean name=\"keyboard\" value=\"false\" /></map>#' $P"
    adb shell input keyevent KEYCODE_HOME; sleep 4
    adb shell am start -n com.middleearth.launcher/.MainActivity -a com.middleearth.launcher.SEARCH > /dev/null
    sleep 4
    # hide the keyboard if it came up (BACK with no keyboard would close the Red Book)
    adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent KEYCODE_BACK
    sleep 2
    snap "drawer-$n-$style"
    if [ $style = tonal ]; then
      adb shell input swipe 540 1250 540 1250 1600 &
      sleep 0.25; snap "drawer-$n-press"
      sleep 1.4; snap "drawer-$n-held"
      wait; adb shell input keyevent KEYCODE_BACK; sleep 1
    fi
    adb shell input keyevent KEYCODE_HOME; sleep 2
  done
done
# the Red Book on a swipe (the spring), then closed with Back (the back gesture's handler)
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell setprop log.tag.LembasTouch DEBUG
# a real finger: down, a few moves, up (one adb call, so the moves aren't spread out by adb itself)
adb shell "input motionevent DOWN 160 1650; for y in 1500 1250 1000 750 500 300 150; do input motionevent MOVE 160 \$y; done; input motionevent UP 160 100"
sleep 2
snap "swipe-open"
adb logcat -d -s LembasTouch:D Lembas:I > "$S/touch.txt" || true
adb shell input keyevent KEYCODE_BACK; sleep 2
snap "back-closed"
adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION" -A12 > "$S/crash.txt" || true
exit 0
