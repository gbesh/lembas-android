package com.middleearth.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sam's garden: a bed of flowers and vegetables by Bag End's path that flourishes on days you use
 * the phone less than usual and grows weedy after long scrolling days. It compares your recent
 * screen time with your own usual (the middle of the last two weeks), never with anyone else's,
 * and never shows a number. Needs usage access, which you turn on yourself; worked out a few times
 * a day on the phone, and kept on the phone.
 */
object Garden {
    const val WEEDY = 0
    const val FAIR = 1
    const val BLOOMING = 2
    private const val PREF_STATE = "garden_state"
    private const val PREF_AT = "garden_at"

    fun permitted(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    fun openSettings(ctx: Context) {
        try { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    /** The garden today, or -1 when it isn't planted (no usage access). Cheap: worked out at most every two hours, in the background. */
    fun state(ctx: Context): Int {
        if (!permitted(ctx)) return -1
        val p = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(PREF_AT, 0L)
        if (System.currentTimeMillis() - at > 2 * 3_600_000L) {
            p.edit().putLong(PREF_AT, System.currentTimeMillis()).apply()
            val app = ctx.applicationContext
            Thread { try { work(app) } catch (_: Throwable) { } }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
        return p.getInt(PREF_STATE, FAIR)
    }

    private fun work(ctx: Context) {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val skip = setOf(ctx.packageName)
        fun minutes(day: LocalDate): Float {
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            val m = usm.queryAndAggregateUsageStats(start, end) ?: return 0f
            return m.values.filter { it.packageName !in skip }.sumOf { it.totalTimeInForeground } / 60_000f
        }
        val past = (1..14).map { minutes(today.minusDays(it.toLong())) }.filter { it > 5f }
        val yesterday = minutes(today.minusDays(1))
        val sofar = minutes(today)
        val state = if (past.size < 4) FAIR else {
            val usual = past.sorted()[past.size / 2]
            // the day so far, as a share of the usual day at this hour, and yesterday as a whole
            val hour = java.time.LocalTime.now().let { it.hour + it.minute / 60f }
            val part = ((hour - 7f) / 16f).coerceIn(.15f, 1f)
            val todayRatio = sofar / (usual * part)
            val ratio = .55f * (yesterday / usual) + .45f * todayRatio
            when { ratio <= .85f -> BLOOMING; ratio >= 1.2f -> WEEDY; else -> FAIR }
        }
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_STATE, state).apply()
    }
}
