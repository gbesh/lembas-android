package com.middleearth.launcher

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise, solar noon and sunset for today, worked out from the time zone's main city.
 * No location permission needed; accurate to a few minutes for most people in that zone.
 */
object Sun {

    class Day(val sunrise: Int, val noon: Int, val sunset: Int) // minutes after local midnight

    // Main city for common time zones (lat, lon). Anything else is estimated from the UTC offset.
    private val CITIES: Map<String, Pair<Double, Double>> = mapOf(
        "Australia/Sydney" to (-33.87 to 151.21), "Australia/Melbourne" to (-37.81 to 144.96),
        "Australia/Brisbane" to (-27.47 to 153.03), "Australia/Adelaide" to (-34.93 to 138.60),
        "Australia/Perth" to (-31.95 to 115.86), "Australia/Hobart" to (-42.88 to 147.33),
        "Australia/Darwin" to (-12.46 to 130.84), "Australia/Canberra" to (-35.28 to 149.13),
        "Australia/Lord_Howe" to (-31.55 to 159.08), "Australia/Broken_Hill" to (-31.95 to 141.45),
        "Pacific/Auckland" to (-36.85 to 174.76), "Pacific/Fiji" to (-18.14 to 178.44),
        "Asia/Tokyo" to (35.68 to 139.69), "Asia/Seoul" to (37.57 to 126.98),
        "Asia/Shanghai" to (31.23 to 121.47), "Asia/Hong_Kong" to (22.32 to 114.17),
        "Asia/Singapore" to (1.35 to 103.82), "Asia/Kolkata" to (19.08 to 72.88),
        "Asia/Dubai" to (25.20 to 55.27), "Asia/Jakarta" to (-6.21 to 106.85),
        "Asia/Manila" to (14.60 to 120.98), "Asia/Bangkok" to (13.76 to 100.50),
        "Europe/London" to (51.51 to -0.13), "Europe/Dublin" to (53.35 to -6.26),
        "Europe/Paris" to (48.86 to 2.35), "Europe/Berlin" to (52.52 to 13.40),
        "Europe/Madrid" to (40.42 to -3.70), "Europe/Rome" to (41.90 to 12.50),
        "Europe/Amsterdam" to (52.37 to 4.90), "Europe/Stockholm" to (59.33 to 18.07),
        "Europe/Athens" to (37.98 to 23.73), "Europe/Moscow" to (55.76 to 37.62),
        "Africa/Johannesburg" to (-26.20 to 28.05), "Africa/Cairo" to (30.04 to 31.24),
        "Africa/Lagos" to (6.52 to 3.38), "Africa/Nairobi" to (-1.29 to 36.82),
        "America/New_York" to (40.71 to -74.01), "America/Chicago" to (41.88 to -87.63),
        "America/Denver" to (39.74 to -104.99), "America/Los_Angeles" to (34.05 to -118.24),
        "America/Phoenix" to (33.45 to -112.07), "America/Toronto" to (43.65 to -79.38),
        "America/Vancouver" to (49.28 to -123.12), "America/Mexico_City" to (19.43 to -99.13),
        "America/Sao_Paulo" to (-23.55 to -46.63), "America/Argentina/Buenos_Aires" to (-34.60 to -58.38),
        "America/Santiago" to (-33.45 to -70.67), "America/Bogota" to (4.71 to -74.07),
        "America/Lima" to (-12.05 to -77.04), "Pacific/Honolulu" to (21.31 to -157.86),
        "America/Anchorage" to (61.22 to -149.90),
    )

    private var cacheKey = ""
    private var cached: Day? = null

    @Synchronized
    fun today(zone: ZoneId = ZoneId.systemDefault(), date: LocalDate = LocalDate.now(zone)): Day {
        val key = "${zone.id}#$date"
        cached?.let { if (key == cacheKey) return it }
        val offsetMin = zone.rules.getOffset(date.atStartOfDay(zone).plusHours(12).toInstant()).totalSeconds / 60.0
        val (lat, lon) = CITIES[zone.id] ?: (0.0 to offsetMin / 4.0)
        val d = compute(date, lat, lon, offsetMin)
        cacheKey = key
        cached = d
        return d
    }

    /** NOAA approximation. */
    private fun compute(date: LocalDate, lat: Double, lon: Double, offsetMin: Double): Day {
        val n = date.dayOfYear
        val g = 2 * PI / 365.0 * (n - 1)
        val eot = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val dec = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val noon = 720 - 4 * lon - eot + offsetMin
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(dec)) - tan(latR) * tan(dec)
        val ha = when {
            cosHa >= 1 -> 0.0          // polar night
            cosHa <= -1 -> 180.0       // midnight sun
            else -> Math.toDegrees(acos(cosHa))
        }
        return Day(
            sunrise = (noon - 4 * ha).toInt().coerceIn(0, 1439),
            noon = noon.toInt().coerceIn(0, 1439),
            sunset = (noon + 4 * ha).toInt().coerceIn(0, 1439),
        )
    }
}

/** Which Look is showing right now, for the launcher and the live wallpaper alike. */
object Themes {
    const val PREFS = "lembas"
    const val PREF_THEME = "theme"
    const val PREF_ANIM = "animations"

    private const val NIGHT_START = 23 * 60
    private const val NIGHT_END = 4 * 60

    /**
     * Adaptive, following the sun:
     *  sunrise → solar noon      Shire Morning (the day getting brighter)
     *  solar noon → sunset       Red Book
     *  sunset → 23:00            Starlit Night
     *  23:00 → 04:00             Elven Silver
     *  04:00 → sunrise           Starlit Night
     */
    fun adaptive(now: ZonedDateTime = ZonedDateTime.now()): Look {
        val day = Sun.today(now.zone, now.toLocalDate())
        val m = now.hour * 60 + now.minute
        return when {
            m >= day.sunrise && m < day.noon -> Look.SHIRE
            m >= day.noon && m < day.sunset -> Look.REDBOOK
            m >= NIGHT_START || m < NIGHT_END -> Look.ELVEN
            else -> Look.STARLIT
        }
    }

    fun choice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_THEME, Look.ADAPTIVE) ?: Look.ADAPTIVE

    fun current(context: Context): Look {
        val c = choice(context)
        return if (c == Look.ADAPTIVE) adaptive() else Look.byId(c)
    }

    fun animationsOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ANIM, true)
}
