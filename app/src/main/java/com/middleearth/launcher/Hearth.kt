package com.middleearth.launcher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.time.LocalTime
import kotlin.math.sin

/**
 * The living state the scenes answer to, worked out cheaply and shared by the home screen and the
 * live wallpaper:
 *  - lamp oil: the battery. Below 30% the lamps and the candle burn lower, below 15% they gutter,
 *    and on the charger they flare up as they're refilled;
 *  - the night watch: from your bedtime the lamps go out one by one over half an hour, and the
 *    scene settles darker and calmer until morning;
 *  - the dawn alarm: for the 15 minutes before your next alarm the night brightens into Shire morning;
 *  - music: while something is playing, fireflies pulse;
 *  - messengers and eagles: a burst of messages sends crebain (or a moth, at night) across the
 *    scene; a ride or delivery arriving sends an eagle.
 * Nothing here leaves the phone; notifications are looked at in memory only, as they arrive.
 */
object Hearth {
    const val PREF_WATCH = "night_watch"        // bedtime as minutes after midnight, -1 for off
    const val PREF_DAWN = "dawn_alarm"
    const val PREF_OMENS = "omens"
    const val PREF_FIREFLIES = "fireflies"
    const val DEFAULT_BEDTIME = 23 * 60

    @Volatile private var app: Context? = null
    /** True while the home screen is in front (set by the home screen). */
    @Volatile var homeShowing = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { i?.let { battery(it) } } }
        val sticky = if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else a.registerReceiver(r, f)
        sticky?.let { battery(it) }
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    /** Something worth a frame happened elsewhere (a smoke ring, say): wake the scenes. */
    fun nudge() = changed()
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    private fun prefs() = app?.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ lamp oil
    @Volatile var oil = 1f; private set
    @Volatile var charging = false; private set
    @Volatile private var refillAt = 0L

    private fun battery(i: Intent) {
        if (i.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) oil = level.toFloat() / scale
        }
        val plugged = when (i.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        if (plugged && !charging) {
            refillAt = SystemClock.uptimeMillis()
            if (oil < .15f) app?.let { Deeds.saw(it, "refill") }
            changed()
        }
        charging = plugged
    }

    /** True while the refill flare is playing (the scene needs frames for it). */
    val refilling get() = SystemClock.uptimeMillis() - refillAt < 4000

    /**
     * How brightly a lamp burns from its oil, about 0.3..1.3: full above 30%, lower beneath it,
     * guttering (a stutter now and then) below 15%, and a bright flare for a few seconds when the
     * charger goes in. [i] gives each lamp its own stutter.
     */
    fun flame(t: Float, i: Int = 0): Float {
        val o = if (charging) maxOf(oil, .3f) else oil
        var k = if (o >= .3f) 1f else .32f + .68f * (o / .3f)
        if (!charging && oil < .15f) {
            val g = sin(t * 7.3f + i * 2.1f) * sin(t * 2.3f + i * 1.7f)
            if (g > .55f) k *= .5f
        }
        val since = SystemClock.uptimeMillis() - refillAt
        if (since < 4000) { val p = since / 4000f; k = k + (1.3f - k) * sin(Math.PI * p).toFloat() }
        return k
    }

    // ------------------------------------------------------------------ the night watch and the dawn alarm (worked out once a minute)
    private var minuteKey = -1L
    @Volatile private var watchNow = 0f
    @Volatile private var dawnNow = 0f
    @Volatile private var dawnSoonNow = false

    private fun refreshMinute() {
        val k = System.currentTimeMillis() / 60_000
        if (k == minuteKey) return
        minuteKey = k
        val p = prefs() ?: return
        val m = LocalTime.now().let { it.hour * 60 + it.minute }
        // the watch: from bedtime, half an hour of lamps going out, then dark until five
        val bed = p.getInt(PREF_WATCH, DEFAULT_BEDTIME)
        watchNow = if (bed < 0) 0f else {
            val since = (m - bed + 1440) % 1440
            val untilFive = (5 * 60 - bed + 1440) % 1440
            if (since < untilFive) (since / 30f).coerceAtMost(1f) else 0f
        }
        // the dawn: the 15 minutes before the next alarm
        dawnNow = 0f; dawnSoonNow = false
        if (p.getBoolean(PREF_DAWN, true)) try {
            app?.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let { a ->
                val left = (a.triggerTime - System.currentTimeMillis()) / 60_000f
                if (left in 0f..16f) { dawnSoonNow = true; dawnNow = ((15f - left) / 15f).coerceIn(0f, 1f) }
            }
        } catch (_: Throwable) { }
    }

    /** 0 while you're up; climbs to 1 over the half hour after bedtime; 1 until five in the morning. */
    fun watch(): Float { refreshMinute(); return watchNow }

    /**
     * How lit lamp [i] of [n] is under the night watch, 0..1: they go out one by one, the last
     * one lingering faintly.
     */
    fun lampLit(i: Int, n: Int): Float {
        val w = watch()
        if (w <= 0f) return 1f
        val order = (i * 7 + 3) % n               // not in a row: here and there across the valley
        val at = (order + 1f) / (n + 1f)
        val k = ((at - w) * (n + 1) * 1.5f + .5f).coerceIn(0f, 1f)
        return if (order == n - 1) maxOf(k, .25f) else k
    }

    /** 0..1 through the 15 minutes before your alarm (for the dawn alarm). */
    fun dawn(): Float { refreshMinute(); return dawnNow }
    /** True within a minute of the dawn starting, or during it: build the morning scene ready. */
    fun dawnSoon(): Boolean { refreshMinute(); return dawnSoonNow }

    // ------------------------------------------------------------------ music
    private var musicAt = 0L
    @Volatile private var musicNow = false
    /** True while something is playing (checked every few seconds; no microphone, no audio). */
    fun music(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - musicAt > 3000) {
            musicAt = now
            musicNow = (prefs()?.getBoolean(PREF_FIREFLIES, true) ?: true) &&
                (try { app?.getSystemService(AudioManager::class.java)?.isMusicActive } catch (_: Throwable) { null } ?: false)
        }
        return musicNow
    }

    // ------------------------------------------------------------------ messengers and eagles
    @Volatile var messengerAt = 0L; private set
    @Volatile var eagleAt = 0L; private set
    private val recent = ArrayDeque<Long>()
    private val seen = HashSet<String>()

    private val ridePackages = listOf(
        "com.ubercab", "com.lyft", "com.didiglobal", "com.olacabs", "ee.mtakso", "com.bolt", "com.grabtaxi", "com.gojek",
        "com.dd.doordash", "com.doordash", "com.deliveroo", "com.menulog", "au.com.menulog", "com.justeat", "com.grubhub",
        "com.instacart", "com.skipthedishes", "com.postmates", "com.glovo", "com.wolt", "com.swiggy", "in.swiggy", "com.application.zomato",
        "com.amazon.mshop", "com.amazon.flex", "au.com.auspost", "com.ubercab.eats",
    )
    private val arriving = Regex("\\b(arriving|arrived|is here|are here|outside|almost there|nearly there|around the corner|at your door|pulling up|minutes? away|\\d+ stops? away|nearby|approaching)\\b", RegexOption.IGNORE_CASE)

    /** A notification arrived (called from the notification listener). */
    fun posted(sbn: StatusBarNotification) {
        val p = prefs() ?: return
        if (!p.getBoolean(PREF_OMENS, true)) return
        if (sbn.isOngoing && !isRide(sbn.packageName)) return
        if (!seen.add(sbn.key + sbn.postTime)) return
        if (seen.size > 200) seen.clear()
        val now = SystemClock.uptimeMillis()
        val ex = sbn.notification.extras
        val text = "${ex.getCharSequence(android.app.Notification.EXTRA_TITLE) ?: ""} ${ex.getCharSequence(android.app.Notification.EXTRA_TEXT) ?: ""}"
        if (isRide(sbn.packageName) && arriving.containsMatchIn(text) && now - eagleAt > 5 * 60_000) {
            eagleAt = now; changed(); return
        }
        val cat = sbn.notification.category
        if (cat != null && cat != android.app.Notification.CATEGORY_MESSAGE && cat != android.app.Notification.CATEGORY_EMAIL && cat != android.app.Notification.CATEGORY_SOCIAL) return
        // a burst: four messages inside two minutes, at most once every ten minutes
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > 120_000) recent.removeFirst()
        if (recent.size >= 4 && now - messengerAt > 10 * 60_000) { messengerAt = now; recent.clear(); changed() }
    }
    private fun isRide(pkg: String) = ridePackages.any { pkg.startsWith(it, ignoreCase = true) }

    /** For trying them out: send crebain or an eagle now. */
    fun summon(eagle: Boolean) { val now = SystemClock.uptimeMillis(); if (eagle) eagleAt = now else messengerAt = now; changed() }
}
