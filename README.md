# Lembas — a Middle-earth launcher for Android

*One small bite is enough.* A light, fast home screen with four looks that follow the sun, gentle animations, and Material You colours across the whole phone that follow the look.

## Install

**Download:** https://github.com/gbesh/lembas-android/releases/latest/download/lembas-launcher.apk

1. Open the link on your phone, tap the downloaded file and allow installs from your browser if Android asks.
2. Press the home button and choose **Lembas → Always**
   (or Settings → Apps → Default apps → Home app → Lembas).
3. **Theme the whole phone (once, about a minute, no computer):** long-press the home screen → **Theme the whole phone → Set up once**, and follow it: turn on Developer options, open Wireless debugging → *Pair device with pairing code*, and type the code into the Lembas notification. From then on the system colours and light/dark theme follow the looks (see [Theming the whole phone](#theming-the-whole-phone)).
4. Long-press the home screen → **Animated wallpaper (home and lock screen)** for the moving scenes behind everything.

To go back to your old launcher, change the Home app setting.

## Updates

Lembas updates itself. Once a day (when the battery isn't low) it checks this repo's latest release and, if there is a newer build, downloads and installs it.

- The first time, Android asks you to **allow Lembas to install apps** and to confirm the update. Long-press empty space → **Check for updates now** does both.
- After that, on Android 12 and later, updates install silently. On older Android versions you tap *Update* once per release.
- Turn it off with long-press → **Automatic updates: Off**.

Every push to `main` builds a new release. All releases are signed with the same key (kept in the repo's Actions secrets, never in the code), so each one installs over the last.

## On your watch (Wear OS)

The Lembas watch face, watch launcher and tile have their own repo: **[gbesh/lembas-wear](https://github.com/gbesh/lembas-wear)**. Put them on your watch from the phone: long-press the home screen → **Put Lembas on your watch**, and type the code from the watch's Wireless debugging → *Pair new device* (both on the same Wi-Fi). Lembas installs the watch app and face; after that the watch updates itself. The phone, the watch and Lembas for Windows all change look at the same minute.

## The four themes

| Theme | Adaptive shows it | Look |
|---|---|---|
| **Shire Morning** (light) | sunrise → midday | Green hills, a round green door, chimney smoke, drifting clouds; left-aligned clock |
| **Red Book** (light) | midday → sunset | Parchment and ink with red rubrics; a map with an inked campfire and line-drawn smoke |
| **Starlit Night** (dark) | sunset → 11pm, and 4am → sunrise | Night sky with twinkling stars, shooting stars, mountains and today's real moon phase |
| **Elven Silver** (deep night) | 11pm → 4am | Near-black with a silver elven arch, a travelling glint and rising silver motes |

"Midday" is when the sun is highest, not 12:00 (often closer to 1pm during daylight saving). Sunrise, midday and sunset are worked out each day from your time zone's main city, so no location permission is needed. The phone, the Lembas watch face and Lembas for Windows use exactly the same sums and cities, so they all change look at the same minute. The settings sheet shows today's switch times. You can also pick one theme to use all the time.

**The home clock** comes in two styles (long-press → **Clock**):
- **Modern** (the default): the same type as the Lembas watch face, over the look's animated scene. The date and the Shire date (e.g. *Mersday · 1 Blotmath*), the time with the hours heavy in the look's colour and the minutes light, the hobbit mealtime, a **glance line** with your next alarm (and your next calendar event, if you allow it), and the **sun arc**: today's daylight from sunrise to sunset, with the sun on it.
- **Classic**: each look's own illustrated layout, with the time of day in Quenya.

## Using it

**Home screen**
- **Swipe up** anywhere → the app drawer, *The Red Book*.
- **Swipe down** → notifications.
- **Tap the clock** → alarms. **Tap the Shire date** → calendar.
- **Long-press empty space or the clock** → the settings sheet.
- **Unlocking**, and each change of look, plays a quick entrance: the scene settles in and the text rises into place in about half a second.
- **Notification dots** (turn on in settings): a small dot in the look's colour on apps with notifications waiting, on the dock, Lately and the list. Lembas only counts which apps have notifications; it never reads them.

**App drawer (the same layout in every theme)**
- Search with *Speak, friend, and enter*. Press Enter to open the top match.
- **Lately** shows your four most recent apps.
- **Letter strips on both edges:** drag your thumb down either side to jump through A–Z. The letter under your thumb swells and bulges inward like Niagara, and you feel a tick on each letter.
- Swipe down from the top of the list to close it.

**App shortcuts**
- **Long-press any app** (dock, Lately or the list) for its own shortcuts, such as *New tab* and *New incognito tab* for the browser or *Take a selfie* for the camera, plus *App info*, *Add to dock / Unpin* and *Uninstall*.
- **Swipe up on a dock or Lately icon** to open its first shortcut directly.
- Shortcuts only appear once Lembas is your default Home app (an Android rule).

**Settings** (long-press empty space): a sheet in the look's colours, with a preview of each look to tap, then
- **Home screen:** clock (Modern or Classic), animations, app icons (line art or original), hobbit mealtimes, next calendar event, notification dots, keyboard when opening the Red Book
- **The whole phone:** theme the whole phone, animated wallpaper, still wallpaper follows the look, make Lembas your home app
- **Watch:** put Lembas on your watch
- **Updates:** automatic updates, check for updates now

## Theming the whole phone

After the one-time setup (long-press → **Theme the whole phone**), Lembas sets Android's own colours and dark theme for each look:

| Theme | System colours | System theme |
|---|---|---|
| Shire Morning | green ink and sage | light |
| Red Book | red ink on parchment | light |
| Starlit Night | gold | dark |
| Elven Silver | quiet silver | dark |

Quick settings, notifications, the Settings app, Gboard, Google's apps and every Material You app take these colours, and every app that follows the system light/dark theme flips with the looks. Because Lembas hands Android the colours directly, the **animated wallpaper** can stay on your home and lock screens while the whole phone is themed exactly. A light/dark switch that falls while you're using the phone waits until the screen is off or you're back home, so it never flashes mid-task.

**How the setup works:** Lembas pairs with the phone's own *Wireless debugging*, the way a computer would, and grants itself one permission (*write secure settings*). Nothing else changes and nothing leaves the phone; you can turn Wireless debugging off afterwards and the permission stays. On Android 10 or earlier, or if you'd rather use a computer: `adb shell pm grant com.middleearth.launcher android.permission.WRITE_SECURE_SETTINGS`. Turning **Theme the whole phone** off hands the colours back to your wallpaper.

**Without the setup**, Lembas can still colour the phone through the wallpaper: **Still wallpaper follows the look** paints the look's scene as your wallpaper at each switch, and Android builds its colours from that (or set the animated wallpaper, which hands Android the look's colours too).

No app can change the system font, the notification shade layout or the icons inside other apps.

## Lembas for Windows

The same four looks for Windows 11 live in **[gbesh/lembas-windows](https://github.com/gbesh/lembas-windows)** (one-click install, updates itself, with the animated desktop and the same modern clock). It changes look at the same minute as the phone.

## Themed icons
Lembas draws its own line-art icon for over 150 common apps (phone, messages, WhatsApp, Chrome, Spotify, Gmail, Maps, Australian banks, BOM weather and more), tinted and framed for each theme. Apps it doesn't know use their own Android 13+ themed icon if they have one, otherwise their first letter in the theme's typeface. Switch to *Original* in settings if you prefer the real icons.

## Why it's fast
- The launcher itself is just the Android framework and Kotlin. The only libraries (an adb client, Conscrypt and BouncyCastle) are used for the one-time setup over Wireless debugging and for installing on the watch.
- Each theme's artwork is painted **once** per day into a bitmap on a background thread. Drawing the home screen is then one image plus a few dozen small shapes for the animation.
- Animations run at about 30 fps, only while the home screen (or the wallpaper) is actually visible. They stop when an app is open, the drawer is open or the screen is off, and pause in battery saver. A setting turns them off completely.
- Drawing an animation frame creates no new objects, so it never triggers garbage-collection stutter.
- Icons are rendered once in the background and cached; scrolling only draws ready-made bitmaps.
- The clock updates on the system's once-a-minute tick, and only while the home screen is visible.

## Building

**GitHub (no install needed):** every push runs `.github/workflows/build.yml`. It downloads the fonts, builds the release APK signed with the key from the `LEMBAS_KEYSTORE_B64` and `LEMBAS_KEYSTORE_PASSWORD` secrets, and publishes it as a release tagged `v<build>` (the five newest are kept). Check the **Actions** tab; when a build fails, the Kotlin errors appear as annotations on the run. On a test branch (run the workflow by hand), it also runs the launcher on a phone emulator with the whole-phone theme on, and pushes screenshots of each look, quick settings and the settings sheet to the `screens` branch.

**Android Studio:** open this folder and press Run. Without the fonts, the launcher uses the system serif. To add them locally, download Cinzel, Cormorant Garamond, IM Fell English, IM Fell English SC, Fraunces and Alegreya Sans from fonts.google.com into `app/src/main/assets/fonts/`, using the file names listed in the workflow.

## Project map

| File | What it does |
|---|---|
| `MainActivity.kt` | Home screen, drawer, dock, Lately, gestures, unlock entrance, settings |
| `Look.kt` | The four themes: every colour, font and style choice |
| `Sun.kt` | Sunrise / midday / sunset and the Adaptive schedule |
| `Backdrop.kt` | Paints each theme's still artwork once into a bitmap |
| `Ambient.kt` | The animated layer (stars, campfire, motes, clouds), shared with the wallpaper |
| `LembasWallpaper.kt` | The optional live wallpaper and the system colours it hands to Android |
| `AppAdapter.kt`, `LetterRail.kt` | Drawer list and the magnifying letter strips |
| `ShortcutPopup.kt` | Long-press menu with app shortcuts |
| `IconCache.kt`, `Glyphs.kt` | Themed icon rendering and the built-in icon set |
| `Updater.kt` | Daily update check and silent self-install from GitHub releases |
| `LookWallpaper.kt` | Sets the phone wallpaper to the look's scene at each switch, and runs the switch alarms |
| `SystemTheme.kt` | The system colours and dark theme for each look (with the one-time permission) |
| `ThemeSetup.kt`, `AdbLink.kt` | The one-time setup over Wireless debugging; the adb client |
| `WatchSetup.kt` | Puts Lembas on the watch from the phone |
| `SettingsSheet.kt` | The settings sheet with look previews |
| `SunArcView.kt` | The sun arc under the modern clock |
| `Dots.kt` | Notification dots |
| `Lore.kt` | Shire calendar, Quenya times of day, hobbit mealtimes |

## Credits
Fonts (downloaded at build time, SIL Open Font License): Cinzel by Natanael Gama; Cormorant Garamond by Christian Thalmann; IM Fell English by Igino Marini; Fraunces by Undercase Type; Alegreya Sans by Huerta Tipográfica.
Names and the Shire calendar come from J.R.R. Tolkien's legendarium. This is an unofficial fan project, not affiliated with the Tolkien Estate or any film studio.
