package com.middleearth.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Makes the whole phone follow the look: at every switch (sunrise, midday, sunset, 11pm, 4am)
 * Lembas paints the look's scene as the home and lock screen wallpaper. Android builds its
 * Material You palette from the wallpaper, so quick settings, notifications, Settings, the
 * keyboard and Google's apps recolour with it.
 *
 * If the Lembas live wallpaper is set, that already hands Android the look's colours, so this
 * steps aside rather than replacing it. Once Lembas may set the system colours itself
 * (SystemTheme), the same alarms switch the exact palette and the dark theme as well.
 *
 * Battery: the alarms don't wake the phone; a switch that falls while it sleeps happens the next
 * time the screen comes on.
 */
object LookWallpaper {
    const val PREF_ON = "wallpaper_follows"
    private const val PREF_LAST = "wallpaper_last"

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(PREF_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_ON, on).remove(PREF_LAST).apply()
        if (on) syncAsync(ctx) else cancel(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)

    fun liveWallpaperInUse(ctx: Context): Boolean = try {
        WallpaperManager.getInstance(ctx).wallpaperInfo?.component == ComponentName(ctx, LembasWallpaper::class.java)
    } catch (_: Exception) { false }

    /** Paint and set the wallpaper if the look (or, for Starlit Night, the moon) changed; then book the next switch. */
    fun syncAsync(ctx: Context, interactive: Boolean = false, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            // The system colours and dark theme (when Lembas may set them), then the wallpaper
            try { SystemTheme.apply(app, Themes.current(app), interactive) } catch (_: Throwable) { }
            try { sync(app) } catch (_: Throwable) { }
            try { schedule(app) } catch (_: Throwable) { }
            done?.invoke()
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-wallpaper" }.start()
    }

    private fun sync(ctx: Context) {
        if (!enabled(ctx) || liveWallpaperInUse(ctx)) return
        val look = Themes.current(ctx)
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        // the screen the way it's held naturally: upright for a phone, sideways for a tablet
        val turned = display.rotation == android.view.Surface.ROTATION_90 || display.rotation == android.view.Surface.ROTATION_270
        val w = if (turned) dm.heightPixels else dm.widthPixels
        val h = if (turned) dm.widthPixels else dm.heightPixels
        // repaint for a new look, a new day (the moon phase) or a different screen (a new phone,
        // restored from a backup of the old one, must not keep the old phone's picture)
        val key = look.id + "/" + LocalDate.now() + "/" + w + "x" + h
        if (prefs(ctx).getString(PREF_LAST, "") == key) return
        val bmp = Painter(dm.density).paint(look, w, h)
        val wm = WallpaperManager.getInstance(ctx)
        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        bmp.recycle()
        prefs(ctx).edit().putString(PREF_LAST, key).apply()
    }

    /** The next moment the look can change (only when following the sun), plus a daily refresh for the moon. */
    private fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        val paints = enabled(ctx) && !liveWallpaperInUse(ctx)
        if (!paints && !SystemTheme.enabled(ctx)) { am.cancel(pi); return }
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1)  // just after midnight
        if (Themes.choice(ctx) == Look.ADAPTIVE) {
            for (d in 0..1) {
                val date = now.toLocalDate().plusDays(d.toLong())
                val day = Sun.today(now.zone, date)
                for (m in listOf(day.sunrise, day.evening, day.sunset, 23 * 60, 4 * 60)) {
                    val t = date.atStartOfDay(now.zone).plusMinutes(m.toLong()).plusSeconds(20)
                    if (t.isAfter(now) && t.isBefore(next)) next = t
                }
            }
        }
        // RTC (not RTC_WAKEUP): never wakes the phone just for this
        am.setAndAllowWhileIdle(AlarmManager.RTC, next.toInstant().toEpochMilli(), pi)
    }

    private fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0x10b, Intent(ctx, Switch::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Alarm, boot, clock and time-zone changes all land here. */
    class Switch : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED)
                ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().remove(PREF_LAST).apply()
            val result = goAsync()
            syncAsync(ctx, interactive = false) { result.finish() }
        }
    }
}
