package com.middleearth.launcher

import android.content.Context
import java.time.LocalTime

/**
 * Quiet hours, 11pm to 7am when switched on: no vibrations, and the scene holds still (on the
 * home screen and the live wallpaper), so a glance at the phone in bed is calm and costs nothing.
 */
object Quiet {
    const val PREF = "quiet_hours"
    const val FROM = 23
    const val TO = 7

    fun enabled(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun now(ctx: Context, t: LocalTime = LocalTime.now()): Boolean = enabled(ctx) && (t.hour >= FROM || t.hour < TO)
}
