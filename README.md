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
| **Shire Morning** (light) | sunrise → late afternoon | Green hills and the river, the round green door, chimney smoke, drifting clouds and a glinting river |
| **Red Book** (light) | late afternoon → sunset | A map of the Shire on parchment, with a flickering campfire, rising embers and a swaying compass |
| **Starlit Night** (dark) | sunset → 11pm, and 4am → sunrise | Mountains over a still lake, a lakeside cabin with a lit window, twinkling stars and today's real moon phase |
| **Elven Silver** (deep night) | 11pm → 4am | Rivendell's silver trees on a knoll, mist, lanterns and rising motes, under Eärendil's star |

The scenes are the same drawings as Lembas for Windows and the watch: a tall version for an upright phone and the wide panorama for tablets and landscape. They change with **the seasons** (blossom in spring, autumn leaves, snow and bare branches in winter; flipped south of the equator), can show **the weather** outside (rain, snow, fog or heavy cloud; settings → Weather in the scene), and with **tilt depth** on, the layers shift a little as you tilt the phone. The weather comes from [Open-Meteo](https://open-meteo.com) (free, no account), asked about your time zone's main city at most once an hour; nothing else is sent.

The scenes live through the day, too:
- **The light of the hour:** the Shire warms through the afternoon, and Starlit Night deepens toward 11pm. For twenty minutes either side of sunrise and sunset, a pink and amber glow rises from the horizon.
- **The wind:** on a windy day the clouds race and the chimney smoke leans.
- **The moon** is tonight's real phase. It rises and sets when it really does, sits low after rising and high near midnight, and the full moon is larger.
- **Shooting stars** cross the sky now and then, and every few seconds on meteor-shower nights (mid-August and mid-December).
- **Visitors:** now and then, for a minute, a fox trots over the Shire, a little boat crosses the starlit lake, or an owl blinks in Rivendell's trees.
- **Birthdays:** bunting appears across the sky on a day with a birthday in your calendar (if you've allowed calendar access).
- **Taps:** tap the scene and it answers, with sparkles in the night sky, embers from the fire, or a puff of cloud in the Shire.
- **Changing look:** the new scene fades in over the old one, like the light changing.

"Late afternoon" is when the sun has dropped to 15° above the horizon and the light turns golden: about 6pm on a Sydney summer's evening, 3:20pm in midwinter, so the fireside look always arrives with the evening, whatever the season. Sunrise, late afternoon and sunset are worked out each day from your time zone's main city, so no location permission is needed. The phone, the Lembas watch face and Lembas for Windows use exactly the same sums and cities, so they all change look at the same minute. The settings sheet shows today's switch times. You can also pick one theme: **until the next change** (then it follows the sun again) or for good.

**The home clock** comes in two styles (long-press → **Clock**):
- **Modern** (the default): the same type as the Lembas watch face, over the look's animated scene. The date and the Shire date (e.g. *Mersday · 1 Blotmath*), the time with the hours heavy in the look's colour and the minutes light, the hobbit mealtime, a **glance line** with your next alarm (and your next calendar event, if you allow it), and the **sun arc**: today's daylight from sunrise to sunset, with the sun on it.
- **Classic**: each look's own illustrated layout, with the time of day in Quenya.

## Using it

**Home screen**
- **Swipe up** anywhere → the app drawer, *The Red Book*.
- **Swipe down** → notifications.
- **Tap the clock** → alarms. **Tap the Shire date** → calendar.
- **Hold the clock** → today at a glance: the date and the Shire date, sunrise and sunset, when the look changes next, your next alarm and event.
- **Long-press empty space** → the settings sheet.
- **Haptics** (on by default; settings → Haptics): a light tap when an app opens, a tick as your swipe passes the point where letting go will open the Red Book (and another if you pull back), a soft snap when it lands, crisp ticks down the letter strips on letters that have apps (and only a faint tap on empty ones, so you can feel your way down the alphabet), and small confirmations when you pin, hide, copy a sum, clear a notification, resize a widget or choose a look. They use Android's own effects, so they feel right on your phone, and they follow the phone's touch-feedback setting.
- **Dock and Lately icons press in** under your finger and spring back when you let go.
- **The Red Book moves on a spring**: it carries the speed of your swipe, so a flick is quick and a slow drag eases home, and the list stretches a little when you scroll past either end. If you've chosen keyboard-when-opening, the keyboard rises with it.
- **Back gesture** (Android 14+): swipe back and the Red Book or the settings sheet shrinks and follows your thumb; let go to close it, or change your mind.
- **Unlocking**, and each change of look, plays a quick entrance: the scene settles in and the text rises into place in about half a second.
- **Double-tap empty space** to lock the phone (turn on in settings; fingerprint and face unlock keep working). It uses a tiny accessibility service that only locks the screen and reads nothing; after the whole-phone setup Lembas switches it on itself.
- **Any screen**: the scenes fill any screen edge to edge (the tall scene on phones, the panorama on tablets and sideways), and the wallpaper repaints for a new phone's screen. **Turned sideways**, the clock stays centred (shrinking if it must) and Live Updates, music and widgets move to a column on the right.
- **Live Updates** under the clock: a call in progress, directions, a ride or delivery on its way, a timer, with its progress bar and time. It uses Android 16's Live Update notifications, and on any Android also ongoing calls, navigation and progress. Tap it to go back to the app. (Turn off in settings; it uses the same notification access as the dots.)
- **Frosted glass** behind the settings sheet and app menus on Android 12+, like Android's own panels (off automatically in battery saver or when the phone's reduce-blur setting is on).
- **Music player** under the clock while something's playing: the song, the artist, play/pause and next. Tap the song to open the app that's playing. (It uses the same notification access as the dots.)
- **Notification dots and previews** (turn on in settings): a small dot in the look's colour on apps with notifications waiting, on the dock, Lately and the list. In the list, the latest message shows in a line under the app's name, and touching and holding the app shows its notifications above its shortcuts: tap one to open it, or × to clear it. Notifications stay on your phone; Lembas sends nothing anywhere.
- **Widgets** under the clock: any app's widget (weather, calendar, notes…), stacked one above another. Add them from settings → **Add a widget**, which lists every widget with its preview. Android asks once whether Lembas may show widgets, and some widgets have their own setup screen. Touch and hold a widget to make it taller or shorter, move it up or down, or remove it.

**Search** (*Speak, friend, and enter*, at the top of the Red Book). As you type it finds:
- **Apps**, by name, by a word in the name, or by initials (*gm* for Google Maps); hidden apps too. It forgives typos: *spotfy* finds Spotify.
- **Icon packs:** install any icon pack from the Play Store (the kind made for Nova, Niagara and similar launchers), then settings → **App icons** and choose it. Apps the pack doesn't cover keep their own icon.

**App shortcuts**, such as *New incognito tab* or *Take a selfie*.
- **Contacts**, with call and message buttons beside each. Lembas asks for contacts access the first time you choose to.
- **A quick sum**: type *12*3+4* or *15% * 80* and the answer shows at the top. Tap it to copy.
- **Little commands:** *timer 10m* or *5 min timer* starts a timer, *alarm 7:30* or *wake me 6am* sets an alarm (in your clock app); *10 km in miles*, *180 cm to ft*, *70f in c* convert units; *100 usd to eur* converts money with the European Central Bank's daily rates (fetched from frankfurter.app at most once a day).
- **Search Google** and **Search the Play Store** for what you typed. When no app matches, the Play Store comes first, so installing something is one tap away.

Tap the search box and your **recent searches** are there before you type (kept on the phone; *Clear recent searches* at the end). Press Enter to open the top result. Touch and hold Lembas's own icon (in any app list, or from another launcher) for a **Search** shortcut that opens straight into search.

**App drawer (the same layout in every theme)**
- **Lately** shows your four most recent apps.
- **Icons** come in four styles (settings → Red Book icons): a **tonal squircle** in a soft tint of the look (the default), a **soft disc** with two-tone glyphs, **no frame** (just a larger glyph, like Niagara), or the **classic** outlines.
- **Rows light up under your finger** the moment you touch them, and the icon swells slightly; the app stays lit while its menu is open.
- **Letter strips on both edges:** drag your thumb down either side to jump through A–Z. The letter under your thumb swells and bulges inward like Niagara, and you feel a tick on each letter.
- Swipe down from the top of the list to close it.

**App shortcuts**
- **Bubble** (Android 11+): in any app's touch-and-hold menu, opens it as a floating bubble, using Android's own bubbles (the same as Messages). The first time, Android asks you to let Lembas show bubbles ("All conversations can bubble"). A few apps insist on opening full screen and leave the bubble.
- **Long-press any app** (dock, Lately, the list or search) for its own shortcuts, such as *New tab* and *New incognito tab* for the browser or *Take a selfie* for the camera, plus *App info*, *Add to dock / Unpin*, **Rename**, **Hide from the list** (search still finds it; Settings → Hidden apps brings it back) and *Uninstall*.
- **Swipe up on a dock or Lately icon** to open its first shortcut directly.
- **Folders in the dock:** long-press a docked app → *Make a folder*, and choose the apps to keep with it. The folder shows them in miniature; tap it to open, long-press to rename, change its apps or undo it.
- Shortcuts only appear once Lembas is your default Home app (an Android rule).

**Settings** (long-press empty space): a sheet in the look's colours, with a preview of each look to tap, then
- **Home screen:** clock (Modern or Classic), animations, tilt depth, quiet hours (11pm–7am: no vibrations and a still scene), weather in the scene, haptics, app icons (line art, original, or any installed icon pack), hobbit mealtimes, next calendar event, notification dots, Live Updates, music player, double-tap to lock, keyboard when opening the Red Book
- **Widgets:** add a widget
- **Apps and search:** Red Book icons (tonal squircle, soft disc, no frame, or the classic outlines), hidden apps, contacts in search
- **The whole phone:** theme the whole phone, animated wallpaper, still wallpaper follows the look, make Lembas your home app
- **Watch:** put Lembas on your watch
- **Sounds:** *Add Lembas sounds* puts a harp, bells and a horn call (the same family as Lembas for Windows) in your phone's own sound lists, for notifications and alarms. Nothing changes until you pick one.
- **Sync:** *Sync with your PC and watch*, through your own Google account (see below).
- **Backup:** back up your settings (look, dock, pinned, hidden and renamed apps, everything here) to a file you keep, and restore them on a new phone
- **Updates:** automatic updates, check for updates now

After an update, a short **What's new** card says what changed (once).

**Hold the scene still:** a quick settings tile (edit your quick settings and add *Hold the scene still*) keeps the home screen and wallpaper still for an hour, for a meeting, a film or to save battery. The live wallpaper is also calmer on the lock screen, at half the frames.

**Lembas for other home apps:** add the *Lembas scene* widget for the look's scene with the time and the Shire date, if you'd rather keep your own launcher.

## Sync with your PC and watch

Settings → **Sync with your PC and watch** signs in with your Google account (Google's own sign-in sheet). Lembas asks for one thing only: to keep its own small settings file in a hidden folder of your Google Drive, which only Lembas can see. It can't see any of your other files, your email or anything else.

The look (or follow the sun), "keep it until", weather in the scene, the clock style and the mealtimes then stay the same on the phone and on Lembas for Windows (tray → *Sync with your phone*). The newest change wins. The watch follows the phone over the watch link, with no sign-in needed. The watch app, its tiles, the Lembas complications and the watch face all follow. Turn sync off in settings at any time. See [PRIVACY.md](PRIVACY.md) for exactly what's stored, and how to delete it.

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
- The launcher itself is just the Android framework and Kotlin. The only libraries are an adb client, Conscrypt and BouncyCastle, used for the one-time setup over Wireless debugging and for installing on the watch, plus Google Play services' sign-in and watch link, used only for sync.
- A new scene is prepared off the main thread, so changing look never stutters. Phones with little memory load the pictures at half size.
- Each scene's still layers are pre-drawn pictures in the app (WebP, about 2 MB for all four looks in four seasons, two shapes each), decoded once at the screen's size. Drawing the home screen is then two or three images plus a few dozen small shapes for the animation.
- Animations run at a gentle rate for each scene (20–30 fps), only while the home screen (or the wallpaper) is actually visible. They stop when an app is open, the drawer is open or the screen is off, and pause in battery saver. A setting turns them off completely.
- Drawing an animation frame creates no new objects, so it never triggers garbage-collection stutter.
- Icons are rendered once in the background and cached, in memory and on disk, so even a cold start shows the drawer at once; scrolling only draws ready-made bitmaps.
- The app is kept small: fonts trimmed to the letters Lembas uses, no 32-bit Intel libraries, no unused crypto tables.
- The clock updates on the system's once-a-minute tick, and only while the home screen is visible.

## Building

**GitHub (no install needed):** every push runs `.github/workflows/build.yml`. It downloads the fonts, builds the release APK signed with the key from the `LEMBAS_KEYSTORE_B64` and `LEMBAS_KEYSTORE_PASSWORD` secrets, and publishes it as a release tagged `v<build>` (the five newest are kept). Check the **Actions** tab; when a build fails, the Kotlin errors appear as annotations on the run. On a test branch (run the workflow by hand), it also runs the launcher on a phone emulator with the whole-phone theme on, and pushes screenshots of each look, quick settings and the settings sheet to the `screens` branch.

**Android Studio:** open this folder and press Run. Without the fonts, the launcher uses the system serif. To add them locally, download Cinzel, Cormorant Garamond, IM Fell English, IM Fell English SC, Fraunces and Alegreya Sans from fonts.google.com into `app/src/main/assets/fonts/`, using the file names listed in the workflow.

## Project map

| File | What it does |
|---|---|
| `MainActivity.kt` | Home screen, drawer, dock, Lately, gestures, unlock entrance, settings |
| `Look.kt` | The four themes: every colour, font and style choice |
| `Sun.kt` | Sunrise / late afternoon / sunset and the Adaptive schedule |
| `Scene.kt` | The scenes: loads the still layers (`assets/scenes`, rendered from lembas-windows' `scenes/index.html`), draws the moving parts and weather between them, tilt depth |
| `Weather.kt` | Today's weather for the scene (Open-Meteo, hourly at most) |
| `Commands.kt` | Search commands (timers, alarms, units, currency) and typo-tolerant matching |
| `Backup.kt`, `Quiet.kt`, `WhatsNew.kt` | Settings backup, quiet hours, the what's-new card |
| `Sync.kt` | Sync through your Google account (Drive's hidden app folder) and the watch link |
| `SceneExtras.kt` | The light of the hour, sunrise and sunset glow, visitors, bunting and taps |
| `DockFolder.kt`, `Sounds.kt`, `SceneWidget.kt`, `PauseTile.kt` | Dock folders, Lembas sounds, the widget for other home apps, the quick settings tile |
| `LembasWallpaper.kt` | The optional live wallpaper and the system colours it hands to Android |
| `AppAdapter.kt`, `LetterRail.kt` | Drawer list and the magnifying letter strips |
| `ShortcutPopup.kt` | Long-press menu with app shortcuts |
| `IconCache.kt`, `Glyphs.kt` | Themed icon rendering and the built-in icon set |
| `DrawerIcons.kt`, `tools/glyph_variants.py` | The Red Book's icon styles; the script makes the bolder (`gb_`) and two-tone (`gd_`) glyphs from the originals (`g_`) |
| `Haptics.kt` | Lembas's haptics, one vocabulary of feelings used everywhere |
| `PressRow.kt` | The highlight under your finger in the Red Book and search |
| `Updater.kt` | Daily update check and silent self-install from GitHub releases |
| `LookWallpaper.kt` | Sets the phone wallpaper to the look's scene at each switch, and runs the switch alarms |
| `SystemTheme.kt` | The system colours and dark theme for each look (with the one-time permission) |
| `ThemeSetup.kt`, `AdbLink.kt` | The one-time setup over Wireless debugging; the adb client |
| `WatchSetup.kt` | Puts Lembas on the watch from the phone |
| `SettingsSheet.kt` | The settings sheet with look previews |
| `SunArcView.kt` | The sun arc under the modern clock |
| `Dots.kt` | Notification dots and previews |
| `Widgets.kt` | Home-screen widgets: picker, adding, resizing and the long-press menu |
| `IconPacks.kt` | Icon packs (the standard appfilter format used by Nova, Niagara and others) |
| `Search.kt` | Search: apps, shortcuts, contacts, sums, commands, Google and the Play Store |
| `Bubbles.kt` | Opening apps as bubbles |
| `LiveCard.kt` | Live Updates on the home screen |
| `Blur.kt` | Frosted glass behind sheets and menus |
| `MusicCard.kt` | The music player on the home screen |
| `LockService.kt` | Double-tap to lock |
| `Lore.kt` | Shire calendar, Quenya times of day, hobbit mealtimes |

## Credits
Fonts (downloaded at build time, SIL Open Font License): Cinzel by Natanael Gama; Cormorant Garamond by Christian Thalmann; IM Fell English by Igino Marini; Fraunces by Undercase Type; Alegreya Sans by Huerta Tipográfica.
Weather: [Open-Meteo](https://open-meteo.com) (CC BY 4.0). Exchange rates: the European Central Bank, via [Frankfurter](https://frankfurter.app).
Names and the Shire calendar come from J.R.R. Tolkien's legendarium. This is an unofficial fan project, not affiliated with the Tolkien Estate or any film studio.
