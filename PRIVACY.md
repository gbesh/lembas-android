# Lembas privacy

Lembas (the phone launcher, Lembas for Windows and the watch app) keeps everything on your own devices. There are no accounts with us, no analytics, no ads and no servers of ours.

## Sync with your Google account (optional)

If you turn on **Sync with Google** in Lembas's settings, you sign in with your Google account and Lembas asks for one permission: **"See, create, and delete its own configuration data in your Google Drive"** (the `drive.appdata` scope). With it, Lembas keeps one small settings file (your chosen look, clock style, weather and animation settings, and your rounded area only if Weather for where you are is on) in a hidden folder of your Drive that only Lembas can see. It can't see, open or change any of your other Drive files, your email or anything else in your account.

The file goes straight between your devices and Google; nobody else, including the people who make Lembas, can read it. Turn sync off and Lembas forgets the sign-in on that device; to delete the file too, remove Lembas under Google Drive → Settings → Manage apps → Delete hidden app data, or revoke Lembas at https://myaccount.google.com/permissions.

## Other services Lembas uses

- **Weather** (optional, on by default): the time zone's main city's coordinates are sent to Open-Meteo (open-meteo.com) every 20 minutes or so. No account, nothing that identifies you.
- **Weather for where you are** (optional, off by default): Lembas asks Android for your *approximate* location (from the network, not GPS), rounds it to about a kilometre, and uses that instead of the main city for the weather. With Sync with Google on, the rounded area is also kept in the settings file in your own Drive, so Lembas for Windows can show the weather for your area too. Turn it off and the area is forgotten on the phone and removed from the settings file at the next sync.
- **Exchange rates** (only when you type a currency conversion in search): today's rates come from frankfurter.app, at most once a day.
- **Updates**: Lembas checks this project's GitHub releases once a day.

Notifications, contacts and calendar access (each optional, each asked for) are only used on the phone to show them on the home screen; they are never sent anywhere.
