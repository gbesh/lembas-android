"""Tap the first on-screen element whose text matches (case-insensitive substring). Exit 1 if none."""
import re, subprocess, sys, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
try:
    root = ET.fromstring(xml)
except ET.ParseError:
    sys.exit(1)
for n in root.iter("node"):
    t = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if want in t.lower():
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
        print("tapped", t.strip(), file=sys.stderr)
        sys.exit(0)
sys.exit(1)
