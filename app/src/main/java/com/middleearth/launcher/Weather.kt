package com.middleearth.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Today's weather in the scene: rain, snow, fog or heavy cloud drawn over the Shire, the lake
 * and Rivendell (never over the map by the fire). It asks Open-Meteo (free, no account, no key)
 * about the time zone's main city at most once an hour; no location permission. Switch it off
 * in settings and nothing is ever fetched.
 *
 * Codes: 0 clear · 1 cloudy · 2 fog · 3 rain · 4 snow · 5 storm.
 */
object Weather {
    private const val KEY_CODE = "weather_code"
    private const val KEY_AT = "weather_at"
    private const val HOUR = 55 * 60_000L
    private const val STALE = 4 * 60 * 60_000L
    @Volatile private var fetching = false

    fun on(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(Themes.PREF_WEATHER, true)

    /** The last weather we heard, or 0 (clear) if it's off or more than four hours old. Starts a fetch when due. */
    fun code(ctx: Context): Int {
        if (!on(ctx)) return 0
        val p = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0)
        val age = System.currentTimeMillis() - at
        if (age > HOUR) refresh(ctx.applicationContext)
        return if (age < STALE) p.getInt(KEY_CODE, 0) else 0
    }

    /** WMO weather code → the scene's few kinds. */
    fun fromWmo(w: Int) = when (w) {
        0, 1 -> 0
        2, 3 -> 1
        45, 48 -> 2
        in 51..67, in 80..82 -> 3
        in 71..77, 85, 86 -> 4
        95, 96, 99 -> 5
        else -> 0
    }

    private fun refresh(ctx: Context) {
        if (fetching) return
        fetching = true
        Thread {
            try {
                val (lat, lon) = Sun.where()
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=weather_code".format(java.util.Locale.US, lat, lon))
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val wmo = JSONObject(body).getJSONObject("current").getInt("weather_code")
                ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_CODE, fromWmo(wmo)).putLong(KEY_AT, System.currentTimeMillis()).apply()
            } catch (_: Exception) {
                // offline: try again next hour, keep showing the last weather until it's stale
                ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_AT, System.currentTimeMillis() - HOUR + 10 * 60_000L).apply()
            } finally { fetching = false }
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-weather" }.start()
    }
}
