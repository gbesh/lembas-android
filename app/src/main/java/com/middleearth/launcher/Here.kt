package com.middleearth.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * "Weather for where you are" (off unless you turn it on): the phone's approximate location, from
 * the network (no GPS), rounded to about a kilometre. It's used for the weather in the scene and,
 * when sync is on, handed to Lembas for Windows so the PC's weather is for your suburb too. Off,
 * Lembas uses the main city of your time zone and never asks for a location.
 */
object Here {
    const val PREF_ON = "weather_here"
    const val REQUEST = 0x4e4e
    private const val PREF_LAT = "here_lat"
    private const val PREF_LON = "here_lon"
    private const val PREF_AT = "here_at"
    private const val REFRESH = 3 * 60 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun on(ctx: Context) = prefs(ctx).getBoolean(PREF_ON, false) && allowed(ctx)

    private fun round(v: Double) = (v * 100).roundToInt() / 100.0

    /** Where you are (rounded), or null when it's off or not known yet. Starts a fresh fix now and then. */
    fun where(ctx: Context): Pair<Double, Double>? {
        if (!on(ctx)) return null
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong(PREF_AT, 0) > REFRESH) refresh(ctx.applicationContext)
        if (!p.contains(PREF_LAT)) return null
        return p.getFloat(PREF_LAT, 0f).toDouble() to p.getFloat(PREF_LON, 0f).toDouble()
    }

    @Suppress("MissingPermission")
    fun refresh(ctx: Context) {
        if (!on(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val save = { l: Location? ->
            if (l != null) prefs(ctx).edit().putFloat(PREF_LAT, round(l.latitude).toFloat()).putFloat(PREF_LON, round(l.longitude).toFloat())
                .putLong(PREF_AT, System.currentTimeMillis()).apply()
        }
        try {
            val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            save(last)
            if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { save(it) }
        } catch (_: Exception) { }
    }

    fun forget(ctx: Context) { prefs(ctx).edit().putBoolean(PREF_ON, false).remove(PREF_LAT).remove(PREF_LON).remove(PREF_AT).apply() }
}
