package com.middleearth.launcher

import java.util.Locale

/**
 * The built-in "icon pack": picks a line-art glyph for an app, first by its package name,
 * then by words in its label. Returns 0 when nothing fits; the icon renderer then uses the
 * app's own monochrome (themed) icon if it has one, or its initial letter.
 */
object Glyphs {

    private val byPackage: Map<String, Int> = HashMap<String, Int>().apply {
        fun put(res: Int, vararg pkgs: String) = pkgs.forEach { this[it] = res }

        put(R.drawable.g_phone, "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.app.telephonyui")
        put(R.drawable.g_message, "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging")
        put(R.drawable.g_chat, "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "com.facebook.orca", "com.discord", "com.Slack", "com.viber.voip", "jp.naver.line.android", "com.tencent.mm", "com.snapchat.android", "com.google.android.apps.dynamite", "com.microsoft.teams")
        put(R.drawable.g_send, "org.telegram.messenger", "org.telegram.messenger.web")
        put(R.drawable.g_compass, "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser")
        put(R.drawable.g_camera, "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2", "org.codeaurora.snapcam", "com.oneplus.camera", "com.motorola.camera3")
        put(R.drawable.g_music, "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music", "deezer.android.app", "com.soundcloud.android", "com.amazon.mp3", "com.aspiro.tidal", "com.shazam.android")
        put(R.drawable.g_calendar, "com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar")
        put(R.drawable.g_clock, "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock")
        put(R.drawable.g_contacts, "com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts")
        put(R.drawable.g_folder, "com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui", "com.sec.android.app.myfiles")
        put(R.drawable.g_mail, "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider", "com.yahoo.mobile.client.android.mail", "ch.protonmail.android")
        put(R.drawable.g_map, "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "net.osmand.plus")
        put(R.drawable.g_notes, "com.google.android.keep", "com.samsung.android.app.notes", "com.evernote", "notion.id", "md.obsidian", "com.microsoft.office.onenote")
        put(R.drawable.g_photos, "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d", "com.instagram.android")
        put(R.drawable.g_settings, "com.android.settings")
        put(R.drawable.g_weather, "com.google.android.apps.weather", "com.sec.android.daemonapp", "com.accuweather.android", "com.weather.Weather", "au.gov.bom.metview")
        put(R.drawable.g_book, "com.amazon.kindle", "com.google.android.apps.books", "com.audible.application")
        put(R.drawable.g_store, "com.android.vending", "com.sec.android.app.samsungapps")
        put(R.drawable.g_video, "com.google.android.youtube", "com.zhiliaoapp.musically", "com.google.android.apps.youtube.kids", "tv.twitch.android.app")
        put(R.drawable.g_tv, "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient", "com.plexapp.android", "com.google.android.videos")
        put(R.drawable.g_calculator, "com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2")
        put(R.drawable.g_games, "com.google.android.play.games", "com.valvesoftware.android.steam.community")
        put(R.drawable.g_people, "com.facebook.katana", "com.linkedin.android", "com.reddit.frontpage", "com.twitter.android", "com.instagram.barcelona", "org.joinmastodon.android", "xyz.blueskyweb.app")
        put(R.drawable.g_bank, "com.commbank.netbank", "org.westpac.bank", "com.anz.android.gomoney", "au.com.nab.mobile", "com.paypal.android.p2pmobile", "com.revolut.revolut")
        put(R.drawable.g_heart, "com.google.android.apps.fitness", "com.sec.android.app.shealth")
        put(R.drawable.g_fitness, "com.strava", "com.fitbit.FitbitMobile")
        put(R.drawable.g_mic, "com.google.android.apps.recorder", "com.sec.android.app.voicenote", "com.google.android.apps.podcasts", "au.com.shiftyjelly.pocketcasts")
        put(R.drawable.g_cart, "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.alibaba.aliexpresshd")
        put(R.drawable.g_car, "com.ubercab", "me.lyft.android")
        put(R.drawable.g_food, "com.ubercab.eats", "com.dd.doordash", "com.deliveroo.orderapp")
        put(R.drawable.g_wallet, "com.google.android.apps.walletnfcrel", "com.samsung.android.spay")
        put(R.drawable.g_news, "com.google.android.apps.magazines", "flipboard.app")
        put(R.drawable.g_cloud, "com.google.android.apps.docs", "com.dropbox.android", "com.microsoft.skydrive")
        put(R.drawable.g_doc, "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides", "com.microsoft.office.word", "com.microsoft.office.officehubrow", "com.adobe.reader")
        put(R.drawable.g_translate, "com.google.android.apps.translate", "com.duolingo")
        put(R.drawable.g_lock, "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.google.android.apps.authenticator2", "com.azure.authenticator")
        put(R.drawable.g_home, "com.google.android.apps.chromecast.app", "com.samsung.android.oneconnect")
        put(R.drawable.g_search, "com.google.android.googlequicksearchbox")
        put(R.drawable.g_star, "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard")
        put(R.drawable.g_shield, "com.nordvpn.android", "com.cloudflare.onedotonedotonedotone")
    }

    // Checked in order; first match wins.
    private val byWord: List<Pair<String, Int>> = listOf(
        "camera" to R.drawable.g_camera,
        "dialer" to R.drawable.g_phone,
        "phone" to R.drawable.g_phone,
        "message" to R.drawable.g_message,
        "sms" to R.drawable.g_message,
        "chat" to R.drawable.g_chat,
        "browser" to R.drawable.g_compass,
        "internet" to R.drawable.g_compass,
        "music" to R.drawable.g_music,
        "radio" to R.drawable.g_music,
        "calendar" to R.drawable.g_calendar,
        "clock" to R.drawable.g_clock,
        "alarm" to R.drawable.g_clock,
        "contact" to R.drawable.g_contacts,
        "file" to R.drawable.g_folder,
        "mail" to R.drawable.g_mail,
        "map" to R.drawable.g_map,
        "note" to R.drawable.g_notes,
        "gallery" to R.drawable.g_photos,
        "photo" to R.drawable.g_photos,
        "setting" to R.drawable.g_settings,
        "weather" to R.drawable.g_weather,
        "book" to R.drawable.g_book,
        "store" to R.drawable.g_store,
        "shop" to R.drawable.g_cart,
        "video" to R.drawable.g_video,
        "calc" to R.drawable.g_calculator,
        "game" to R.drawable.g_games,
        "bank" to R.drawable.g_bank,
        "wallet" to R.drawable.g_wallet,
        "pay" to R.drawable.g_wallet,
        "health" to R.drawable.g_heart,
        "fit" to R.drawable.g_fitness,
        "record" to R.drawable.g_mic,
        "podcast" to R.drawable.g_mic,
        "voice" to R.drawable.g_mic,
        "news" to R.drawable.g_news,
        "drive" to R.drawable.g_cloud,
        "cloud" to R.drawable.g_cloud,
        "docs" to R.drawable.g_doc,
        "pdf" to R.drawable.g_doc,
        "translat" to R.drawable.g_translate,
        "authenticat" to R.drawable.g_lock,
        "password" to R.drawable.g_lock,
        "vpn" to R.drawable.g_shield,
        "secur" to R.drawable.g_shield,
        "food" to R.drawable.g_food,
        "eats" to R.drawable.g_food,
        "home" to R.drawable.g_home,
        "search" to R.drawable.g_search,
    )

    fun forApp(packageName: String, label: String): Int {
        byPackage[packageName]?.let { return it }
        val l = label.lowercase(Locale.ROOT)
        for ((word, res) in byWord) if (l.contains(word)) return res
        return 0
    }
}
