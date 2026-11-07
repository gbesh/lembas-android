package com.middleearth.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/**
 * Today's weather in the scene, and a line of hobbit forecast. It asks Open-Meteo (free, no
 * account, no key) about the time zone's main city (or, with "Weather for where you are" on,
 * about where you are) every 20 minutes or so, and its air-quality service once an hour. Switch
 * it off in settings and nothing is ever fetched.
 *
 * Codes: 0 clear · 1 cloudy · 2 fog · 3 rain · 4 snow · 5 storm.
 * And from the same answer: a rainbow when the sun comes out after rain, frost on a cold morning,
 * a shimmer on a hot afternoon, a haze of smoke when the air is bad, beacons lit for severe weather.
 */
object Weather {
    private const val KEY_CODE = "weather_code"
    private const val KEY_AT = "weather_at"
    private const val KEY_WIND = "weather_wind"
    private const val KEY_GUST = "weather_gust"
    private const val KEY_TEMP = "weather_temp"
    private const val KEY_DAY = "weather_is_day"
    private const val KEY_RAIN_AT = "weather_rain_at"
    private const val KEY_SEVERE = "weather_severe"
    private const val KEY_FORECAST = "weather_forecast"
    private const val KEY_PM = "weather_pm25"
    private const val KEY_PM_AT = "weather_pm_at"
    private const val HOUR = 20 * 60_000L
    private const val AIR_EVERY = 60 * 60_000L
    private const val STALE = 4 * 60 * 60_000L
    @Volatile private var fetching = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    fun on(ctx: Context) = prefs(ctx).getBoolean(Themes.PREF_WEATHER, true)
    private fun fresh(ctx: Context) = on(ctx) && System.currentTimeMillis() - prefs(ctx).getLong(KEY_AT, 0) < STALE

    /** The last weather we heard, or 0 (clear) if it's off or more than four hours old. Starts a fetch when due. */
    fun code(ctx: Context): Int {
        if (!on(ctx)) return 0
        val p = prefs(ctx)
        val age = System.currentTimeMillis() - p.getLong(KEY_AT, 0)
        if (age > HOUR) refresh(ctx.applicationContext)
        return if (age < STALE) p.getInt(KEY_CODE, 0) else 0
    }

    /** A windy day (over 30 km/h): the clouds race and the smoke leans. */
    fun windy(ctx: Context): Boolean = fresh(ctx) && prefs(ctx).getFloat(KEY_WIND, 0f) > 30f

    /** What the sky is doing beyond rain and cloud, for the scene's touches. */
    class Sky(val rainbow: Boolean, val frost: Boolean, val heat: Boolean, val smoke: Float, val beacons: Boolean, val storm: Boolean)

    fun sky(ctx: Context): Sky {
        if (!fresh(ctx)) return Sky(false, false, false, 0f, false, false)
        val p = prefs(ctx)
        val code = p.getInt(KEY_CODE, 0)
        val temp = p.getFloat(KEY_TEMP, 15f)
        val day = p.getBoolean(KEY_DAY, true)
        val hour = LocalDateTime.now().hour
        val sinceRain = System.currentTimeMillis() - p.getLong(KEY_RAIN_AT, 0)
        val pmFresh = System.currentTimeMillis() - p.getLong(KEY_PM_AT, 0) < 3 * AIR_EVERY
        val pm = if (pmFresh) p.getFloat(KEY_PM, 0f) else 0f
        return Sky(
            rainbow = day && (code == 0 || code == 1) && sinceRain in 0..50 * 60_000L,   // the sun out within 50 minutes of rain
            frost = temp <= 2f && hour in 4..10 && code != 3,
            heat = day && temp >= 32f && hour in 11..18,
            smoke = ((pm - 25f) / 75f).coerceIn(0f, 1f),                                  // a haze from 25 µg/m³, thick by 100
            beacons = p.getBoolean(KEY_SEVERE, false),
            storm = code == 5,
        )
    }

    /** Today's weather in a line of hobbit talk ("Rain by elevenses, clear for supper"), or "" if unknown. */
    fun forecast(ctx: Context): String = if (fresh(ctx)) prefs(ctx).getString(KEY_FORECAST, "") ?: "" else ""

    /** WMO weather code → the scene's few kinds. Rain only when rain is falling: a forecast of
     *  light drizzle with nothing in the gauge (a damp, humid evening) is cloud. */
    fun fromWmo(w: Int, rain: Double = 1.0) = when (w) {
        0, 1 -> 0
        2, 3 -> 1
        45, 48 -> 2
        51, 53, 56 -> if (rain >= 0.2) 3 else 1
        in 51..67, in 80..82 -> if (rain > 0.0) 3 else 1
        in 71..77, 85, 86 -> 4
        95, 96, 99 -> 5
        else -> 0
    }

    /** Fetch again at the next look (a new place, say). */
    fun refreshSoon(ctx: Context) { prefs(ctx).edit().putLong(KEY_AT, 0).putLong(KEY_PM_AT, 0).apply(); code(ctx) }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    private fun refresh(ctx: Context) {
        if (fetching) return
        fetching = true
        Thread {
            val p = prefs(ctx)
            try {
                val (lat, lon) = Here.where(ctx) ?: Sun.where()
                val loc = java.util.Locale.US
                val j = get("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(loc, lat, lon) +
                    "&current=weather_code,precipitation,wind_speed_10m,wind_gusts_10m,temperature_2m,is_day" +
                    "&hourly=weather_code,precipitation_probability,precipitation,temperature_2m,wind_gusts_10m&forecast_hours=16&timezone=auto")
                val cur = j.getJSONObject("current")
                val rain = cur.optDouble("precipitation", 1.0)
                val code = fromWmo(cur.getInt("weather_code"), rain)
                val e = p.edit()
                    .putFloat(KEY_WIND, cur.optDouble("wind_speed_10m", 0.0).toFloat())
                    .putFloat(KEY_GUST, cur.optDouble("wind_gusts_10m", 0.0).toFloat())
                    .putFloat(KEY_TEMP, cur.optDouble("temperature_2m", 15.0).toFloat())
                    .putBoolean(KEY_DAY, cur.optInt("is_day", 1) == 1)
                    .putInt(KEY_CODE, code).putLong(KEY_AT, System.currentTimeMillis())
                if (code == 3 || code == 5) e.putLong(KEY_RAIN_AT, System.currentTimeMillis())
                val hourly = j.optJSONObject("hourly")
                e.putBoolean(KEY_SEVERE, severe(cur, hourly))
                e.putString(KEY_FORECAST, hourly?.let { Hobbit.say(it, cur) } ?: "")
                e.apply()
                GoogleSync.tellWatch(ctx)   // the watch face's sky and forecast
                // the air, less often (for the smoke of bushfires and the like)
                if (System.currentTimeMillis() - p.getLong(KEY_PM_AT, 0) > AIR_EVERY) try {
                    val a = get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.2f&longitude=%.2f&current=pm2_5".format(loc, lat, lon))
                    p.edit().putFloat(KEY_PM, a.getJSONObject("current").optDouble("pm2_5", 0.0).toFloat()).putLong(KEY_PM_AT, System.currentTimeMillis()).apply()
                } catch (_: Exception) { }
            } catch (_: Exception) {
                // offline: try again in ten minutes, keep showing the last weather until it's stale
                p.edit().putLong(KEY_AT, System.currentTimeMillis() - HOUR + 10 * 60_000L).apply()
            } finally { fetching = false }
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-weather" }.start()
    }

    /** Severe weather now or in the next few hours: damaging gusts, a hailstorm, a deluge, fierce heat or cold. */
    private fun severe(cur: JSONObject, hourly: JSONObject?): Boolean {
        if (cur.optDouble("wind_gusts_10m", 0.0) >= 90 || cur.optInt("weather_code") in listOf(96, 99)) return true
        if (hourly == null) return false
        val codes = hourly.optJSONArray("weather_code"); val pr = hourly.optJSONArray("precipitation")
        val t = hourly.optJSONArray("temperature_2m"); val g = hourly.optJSONArray("wind_gusts_10m")
        for (i in 0 until minOf(6, codes?.length() ?: 0)) {
            if ((g?.optDouble(i) ?: 0.0) >= 90 || (pr?.optDouble(i) ?: 0.0) >= 15 || codes?.optInt(i) in listOf(96, 99) ||
                (t?.optDouble(i) ?: 20.0) >= 42 || (t?.optDouble(i) ?: 20.0) <= -15) return true
        }
        return false
    }
}

/** The day's weather as a hobbit would put it, by the mealtimes. */
object Hobbit {
    private val MEALS = listOf(7 to "breakfast", 9 to "second breakfast", 11 to "elevenses", 13 to "luncheon",
        16 to "afternoon tea", 18 to "dinner", 20 to "supper", 23 to "bedtime")

    /** The first meal at or after an hour. */
    private fun mealAt(h: Int) = MEALS.firstOrNull { it.first >= h }?.second ?: "bedtime"

    private enum class Kind { FINE, CLOUD, FOG, WET, SNOW, STORM }
    private fun kind(code: Int, prob: Int) = when {
        code in 95..99 -> Kind.STORM
        code in 71..77 || code in 85..86 -> Kind.SNOW
        (code in 51..67 || code in 80..82) && prob >= 40 -> Kind.WET
        prob >= 70 -> Kind.WET
        code == 45 || code == 48 -> Kind.FOG
        code == 3 -> Kind.CLOUD
        else -> Kind.FINE
    }

    fun say(hourly: JSONObject, cur: JSONObject): String {
        val times = hourly.optJSONArray("time") ?: return ""
        val codes = hourly.optJSONArray("weather_code") ?: return ""
        val prob = hourly.optJSONArray("precipitation_probability")
        val temps = hourly.optJSONArray("temperature_2m")
        val n = minOf(times.length(), codes.length(), 14)
        if (n < 3) return ""
        val hours = (0 until n).map { times.getString(it).substring(11, 13).toInt() }
        val kinds = (0 until n).map { kind(codes.optInt(it), prob?.optInt(it) ?: 0) }
        val maxT = (0 until n).maxOf { temps?.optDouble(it) ?: 15.0 }
        val minT = (0 until n).minOf { temps?.optDouble(it) ?: 15.0 }
        val nowK = kinds[0]
        val wet = setOf(Kind.WET, Kind.STORM, Kind.SNOW)
        val firstWet = kinds.indexOfFirst { it in wet }
        val night = hours[0] >= 20 || hours[0] < 5
        fun word(k: Kind) = when (k) { Kind.STORM -> "Thunder"; Kind.SNOW -> "Snow"; else -> "Rain" }
        return when {
            nowK in wet -> {
                val dry = kinds.indexOfFirst { it !in wet }
                if (dry < 0) (if (nowK == Kind.SNOW) "Snow all day: a day for the fireside" else "Wet all day: a day for the fireside")
                else "${word(nowK)} until ${mealAt(hours[dry])}, then clearing"
            }
            firstWet > 0 -> {
                val k = kinds[firstWet]
                val after = kinds.drop(firstWet).indexOfFirst { it !in wet }.let { if (it < 0) -1 else it + firstWet }
                "${word(k)} by ${mealAt(hours[firstWet])}" + (if (after > 0) ", clear for ${mealAt(hours[after])}" else "") +
                    (if (k == Kind.STORM) ": stay in" else "")
            }
            nowK == Kind.FOG -> "Mist until ${mealAt(hours[(kinds.indexOfFirst { it != Kind.FOG }).coerceAtLeast(1)])}"
            maxT >= 32 -> "A hot one: find shade by luncheon"
            minT <= 1 && !night -> "Frost early: wrap up warm"
            night -> if (kinds.take(6).all { it == Kind.FINE }) "A clear night for stargazing" else "A quiet, cloudy night"
            kinds.count { it == Kind.FINE } >= n * 2 / 3 -> "A fine day for a walk"
            else -> "Dry and grey: a good day for errands"
        }
    }
}
