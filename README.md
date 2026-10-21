# Lembas — a Middle-earth launcher for Android

*One small bite is enough.* A light, fast home screen with four looks that follow the sun, gentle animations, and Material You colours across the whole phone that follow the look.

## Install

**Download:** https://github.com/gbesh/lembas-android/releases/latest/download/lembas-launcher.apk

1. Open the link on your phone, tap the downloaded file and allow installs from your browser if Android asks.
2. Press the home button and choose **Lembas → Always**
   (or Settings → Apps → Default apps → Home app → Lembas).
3. That's it for colours: Lembas sets your phone's wallpaper to the look's scene at each switch, so Android's Material You colours (quick settings, notifications, Settings, the keyboard, Google's apps) follow the look. Turn it off with long-press → **Phone colours follow the look**. Prefer an animated lock screen? Long-press → **Animated lock screen (live wallpaper)**; it colours the phone the same way.
4. Settings → Display → Dark theme → Schedule → **Turn on from sunset to sunrise**, so the phone's light/dark mode flips at the same moment Lembas does.

To go back to your old launcher, change the Home app setting.

## Updates

Lembas updates itself. Once a day (when the battery isn't low) it checks this repo's latest release and, if there is a newer build, downloads and installs it.

- The first time, Android asks you to **allow Lembas to install apps** and to confirm the update. Long-press empty space → **Check for updates now** does both.
- After that, on Android 12 and later, updates install silently. On older Android versions you tap *Update* once per release.
- Turn it off with long-press → **Automatic updates: Off**.

Every push to `main` builds a new release. All releases are signed with the same key (kept in the repo's Actions secrets, never in the code), so each one installs over the last.

## On your watch (Wear OS)

The Lembas watch face, watch launcher and tile have their own repo: **[gbesh/lembas-wear](https://github.com/gbesh/lembas-wear)** (install once from a Windows PC, then it updates itself). They used to be published here; see that repo for moving a watch over.

## The four themes

| Theme | Adaptive shows it | Look |
|---|---|---|
| **Shire Morning** (light) | sunrise → midday | Green hills, a round green door, chimney smoke, drifting clouds; left-aligned clock |
| **Red Book** (light) | midday → sunset | Parchment and ink with red rubrics; a map with an inked campfire and line-drawn smoke |
| **Starlit Night** (dark) | sunset → 11pm, and 4am → sunrise | Night sky with twinkling stars, shooting stars, mountains and today's real moon phase |
| **Elven Silver** (deep night) | 11pm → 4am | Near-black with a silver elven arch, a travelling glint and rising silver motes |

"Midday" is when the sun is highest, not 12:00 (often closer to 1pm during daylight saving). Sunrise, midday and sunset are worked out each day from your time zone's main city, so no location permission is needed. The theme picker shows today's switch times. You can also pick one theme to use all the time.

Every theme shows the time, the Shire-calendar date (e.g. *Highday, 25 Winterfilth*), the time of day in Quenya and the current hobbit mealtime.

## Using it

**Home screen**
- **Swipe up** anywhere → the app drawer, *The Red Book*.
- **Swipe down** → notifications.
- **Tap the clock** → alarms. **Tap the Shire date** → calendar.
- **Long-press empty space or the clock** → settings.
- **Unlocking** plays a quick entrance: the scene settles in and the text rises into place in about half a second.

**App drawer (the same layout in every theme)**
- Search with *Speak, friend, and enter*. Press Enter to open the top match.
- **Lately** shows your four most recent apps.
- **Letter strips on both edges:** drag your thumb down either side to jump through A–Z. The letter under your thumb swells and bulges inward like Niagara, and you feel a tick on each letter.
- Swipe down from the top of the list to close it.

**App shortcuts**
- **Long-press any app** (dock, Lately or the list) for its own shortcuts, such as *New tab* and *New incognito tab* for the browser or *Take a selfie* for the camera, plus *App info*, *Add to dock / Unpin* and *Uninstall*.
- **Swipe up on a dock or Lately icon** to open its first shortcut directly.
- Shortcuts only appear once Lembas is your default Home app (an Android rule).

**Settings** (long-press empty space)
- Theme: Adaptive, Shire Morning, Red Book, Starlit Night or Elven Silver
- Animations on/off
- App icons: themed line art, or the apps' original icons
- Hobbit mealtimes on/off
- Keyboard when opening the Red Book
- Phone colours follow the look on/off
- Make Lembas your home app
- Automatic updates on/off, and Check for updates now
- Animated lock screen (live wallpaper)

## Matching the rest of the phone

Android builds its Material You colours (quick settings, notifications, the Settings app, Gboard, Google's apps) from your wallpaper. So at each theme switch Lembas sets your home and lock screen wallpaper to the theme's scene, and the whole phone recolours to match (Android 12+):

| Theme | System colours |
|---|---|
| Shire Morning | sage green, sunlight yellow |
| Red Book | deep red, parchment |
| Starlit Night | gold on deep blue |
| Elven Silver | silver-blue |

When Adaptive changes theme, the phone takes a second or two to recolour. Lembas repaints the wallpaper at most once per theme per day, so this costs next to nothing.

- **Phone colours follow the look** (on by default) is the setting for this. Turn it off to keep your own wallpaper.
- **Animated lock screen (live wallpaper)** shows the theme's scene animated on your lock screen instead, and colours the phone the same way. While it's in use, Lembas leaves the wallpaper alone.

No app can change the system font, the notification shade layout or icons inside other apps.

## Lembas for Windows

The same four looks for Windows 11 now live in their own public repo: **[gbesh/lembas-windows](https://github.com/gbesh/lembas-windows)** (one-click install, updates itself).

## Themed icons
Lembas draws its own line-art icon for over 150 common apps (phone, messages, WhatsApp, Chrome, Spotify, Gmail, Maps, Australian banks, BOM weather and more), tinted and framed for each theme. Apps it doesn't know use their own Android 13+ themed icon if they have one, otherwise their first letter in the theme's typeface. Switch to *Original* in settings if you prefer the real icons.

## Why it's fast
- No libraries at all: just the Android framework and Kotlin.
- Each theme's artwork is painted **once** per day into a bitmap on a background thread. Drawing the home screen is then one image plus a few dozen small shapes for the animation.
- Animations run at about 30 fps, only while the home screen (or the wallpaper) is actually visible. They stop when an app is open, the drawer is open or the screen is off, and pause in battery saver. A setting turns them off completely.
- Drawing an animation frame creates no new objects, so it never triggers garbage-collection stutter.
- Icons are rendered once in the background and cached; scrolling only draws ready-made bitmaps.
- The clock updates on the system's once-a-minute tick, and only while the home screen is visible.

## Building

**GitHub (no install needed):** every push runs `.github/workflows/build.yml`. It downloads the fonts, builds the release APK signed with the key from the `LEMBAS_KEYSTORE_B64` and `LEMBAS_KEYSTORE_PASSWORD` secrets, and publishes it as a release tagged `v<build>` (the five newest are kept). Check the **Actions** tab; when a build fails, the Kotlin errors appear as annotations on the run.

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
| `LookWallpaper.kt` | Sets the phone wallpaper to the look's scene at each switch, so Material You follows |
| `Lore.kt` | Shire calendar, Quenya times of day, hobbit mealtimes |

## Credits
Fonts (downloaded at build time, SIL Open Font License): Cinzel by Natanael Gama; Cormorant Garamond by Christian Thalmann; IM Fell English by Igino Marini; Fraunces by Undercase Type; Alegreya Sans by Huerta Tipográfica.
Names and the Shire calendar come from J.R.R. Tolkien's legendarium. This is an unofficial fan project, not affiliated with the Tolkien Estate or any film studio.
