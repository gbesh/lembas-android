package com.middleearth.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.time.LocalDate

/**
 * The smoke-ring focus timer: a quiet stretch of focus (25 minutes unless you say otherwise). While
 * it runs, a wisp of pipe smoke curls up the scene and a countdown sits in the notification shade;
 * when it's done, a smoke ring drifts up the home screen (or the next time you come home), and the
 * day's rings are counted. No nagging: stop whenever you like.
 */
object Focus {
    private const val PREF_END = "focus_end"
    private const val PREF_MIN = "focus_started_minutes"
    private const val PREF_RINGS = "focus_rings"          // "epochDay:count"
    private const val PREF_PENDING = "focus_ring_pending"
    private const val CHANNEL = "focus"
    private const val NOTE = 7301
    const val ACTION_DONE = "com.middleearth.launcher.FOCUS_DONE"
    const val ACTION_STOP = "com.middleearth.launcher.FOCUS_STOP"

    /** When a ring last finished (uptime): the scene blows it up the screen. */
    @Volatile var ringAt = 0L; private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    fun end(ctx: Context) = prefs(ctx).getLong(PREF_END, 0L)
    fun running(ctx: Context) = end(ctx) > System.currentTimeMillis()
    /** Whole minutes left, rounded up. */
    fun minutesLeft(ctx: Context) = ((end(ctx) - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(0)
    /** 0..1 through the session. */
    fun progress(ctx: Context): Float {
        val total = prefs(ctx).getInt(PREF_MIN, 25) * 60_000f
        return (1f - (end(ctx) - System.currentTimeMillis()) / total).coerceIn(0f, 1f)
    }

    fun ringsToday(ctx: Context): Int {
        val v = prefs(ctx).getString(PREF_RINGS, "") ?: ""
        val (d, n) = v.split(":").let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        return if (d == LocalDate.now().toEpochDay()) n else 0
    }

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, FocusReceiver::class.java).setAction(ACTION_DONE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun start(ctx: Context, minutes: Int) {
        val m = minutes.coerceIn(1, 240)
        val end = System.currentTimeMillis() + m * 60_000L
        prefs(ctx).edit().putLong(PREF_END, end).putInt(PREF_MIN, m).apply()
        try { ctx.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC_WAKEUP, end, 30_000L, alarmIntent(ctx)) } catch (_: Throwable) { }
        notifyRunning(ctx, end, m)
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().putLong(PREF_END, 0L).apply()
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx)) } catch (_: Throwable) { }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE)
    }

    /** The session ended (the alarm, or the home screen noticing first). Safe to call twice. */
    fun finish(ctx: Context, homeShowing: Boolean) {
        if (end(ctx) == 0L || running(ctx)) return
        val n = ringsToday(ctx) + 1
        prefs(ctx).edit().putLong(PREF_END, 0L).putString(PREF_RINGS, "${LocalDate.now().toEpochDay()}:$n")
            .putBoolean(PREF_PENDING, !homeShowing).apply()
        Deeds.saw(ctx, "smokering")
        if (homeShowing) { ringAt = SystemClock.uptimeMillis(); Hearth.nudge() }
        notifyDone(ctx, n)
    }

    /** Coming home after a session finished elsewhere: blow its ring now. */
    fun playPending(ctx: Context) {
        if (end(ctx) != 0L && !running(ctx)) finish(ctx, true)
        if (prefs(ctx).getBoolean(PREF_PENDING, false)) {
            prefs(ctx).edit().putBoolean(PREF_PENDING, false).apply()
            ringAt = SystemClock.uptimeMillis()
            Hearth.nudge()
        }
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The smoke-ring focus timer"; setShowBadge(false)
            })
    }

    private fun home(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun notifyRunning(ctx: Context, end: Long, minutes: Int) {
        channel(ctx)
        val stop = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, FocusReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.g_clock)
            .setContentTitle("Focusing for $minutes minutes")
            .setContentText("A smoke ring when you're done")
            .setWhen(end).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(home(ctx))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTE, n) } catch (_: SecurityException) { }
    }

    private fun notifyDone(ctx: Context, rings: Int) {
        channel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.g_clock)
            .setContentTitle("Done. A smoke ring for you")
            .setContentText(if (rings == 1) "Your first today." else "$rings today.")
            .setContentIntent(home(ctx)).setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTE, n) } catch (_: SecurityException) { }
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Focus.ACTION_DONE -> Focus.finish(ctx, homeShowing = Hearth.homeShowing)
            Focus.ACTION_STOP -> Focus.stop(ctx)
        }
    }
}
