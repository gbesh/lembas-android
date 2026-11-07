package com.middleearth.launcher

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * A journey you walk: your real steps carry you along the road from Bag End to Mount Doom. Steps
 * come from Health Connect (so the Pixel Watch's count, through Fitbit, is included) on Android 14
 * and later, or from the phone's own step counter before that. About 0.76 m a step. Passing a
 * place is written in the Deeds, gets a gentle note, and that day its name is strung across the
 * scene. Steps are read on the phone and kept on the phone; only the distance (and the place) go
 * to your watch and, with sync on, your PC.
 */
object Journey {
    class Place(val id: String, val name: String, val km: Float, val x: Float, val y: Float, val deed: Boolean = false)

    /** The road (distances along the Fellowship's way, about), with places on the Red Book's little map (0..1, east and south). */
    val PLACES = listOf(
        Place("bagend", "Bag End", 0f, .17f, .31f),
        Place("ferry", "Bucklebury Ferry", 100f, .225f, .315f),
        Place("crickhollow", "Crickhollow", 105f, .235f, .32f),
        Place("bombadil", "Tom Bombadil's house", 135f, .25f, .335f),
        Place("bree", "Bree", 217f, .3f, .31f, deed = true),
        Place("weathertop", "Weathertop", 383f, .37f, .3f, deed = true),
        Place("lastbridge", "the Last Bridge", 468f, .43f, .29f),
        Place("rivendell", "Rivendell", 737f, .5f, .27f, deed = true),
        Place("hollin", "Hollin", 1050f, .49f, .38f),
        Place("moria", "the Gates of Moria", 1279f, .5f, .43f, deed = true),
        Place("lorien", "Lothlórien", 1480f, .57f, .46f, deed = true),
        Place("argonath", "the Argonath", 2050f, .645f, .6f),
        Place("amonhen", "Amon Hen", 2107f, .655f, .615f, deed = true),
        Place("marshes", "the Dead Marshes", 2237f, .72f, .62f),
        Place("blackgate", "the Black Gate", 2414f, .77f, .64f, deed = true),
        Place("henneth", "Henneth Annûn", 2607f, .725f, .73f),
        Place("cirith", "Cirith Ungol", 2736f, .77f, .76f),
        Place("doom", "Mount Doom", 2863f, .85f, .75f, deed = true),
    )
    val TOTAL = PLACES.last().km
    private const val STRIDE_KM = 0.00076f

    private const val PREF_START = "walk_start"
    private const val PREF_KM = "walk_km"
    private const val PREF_PASSED = "walk_passed"     // the furthest place reached (index)
    private const val PREF_BANNER = "walk_banner"     // "epochDay|place"
    private const val PREF_SENSOR_LAST = "walk_sensor_last"
    private const val PREF_SENSOR_STEPS = "walk_sensor_steps"
    private const val PREF_AT = "walk_at"
    const val READ_STEPS = "android.permission.health.READ_STEPS"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    fun started(ctx: Context) = prefs(ctx).getLong(PREF_START, 0L) > 0L
    fun km(ctx: Context) = prefs(ctx).getFloat(PREF_KM, 0f)
    private val healthConnect get() = Build.VERSION.SDK_INT >= 34

    fun permitted(ctx: Context): Boolean = if (healthConnect) ctx.checkSelfPermission(READ_STEPS) == PackageManager.PERMISSION_GRANTED
        else Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Set out from Bag End (asks for steps the first time). */
    fun start(a: Activity) {
        if (!permitted(a)) {
            val perm = if (healthConnect) READ_STEPS else android.Manifest.permission.ACTIVITY_RECOGNITION
            try { a.requestPermissions(arrayOf(perm), REQUEST) } catch (_: Exception) { }
            prefs(a).edit().putBoolean("walk_wanted", true).apply()
            return
        }
        begin(a)
    }
    const val REQUEST = 4711

    /** After the permission answer: set out if it was granted. */
    fun onPermission(a: Activity) {
        if (prefs(a).getBoolean("walk_wanted", false) && permitted(a)) { prefs(a).edit().remove("walk_wanted").apply(); begin(a) }
    }

    private fun begin(ctx: Context) {
        prefs(ctx).edit().putLong(PREF_START, LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            .putFloat(PREF_KM, 0f).putInt(PREF_PASSED, 0).putLong(PREF_SENSOR_LAST, -1L).putLong(PREF_SENSOR_STEPS, 0L).putLong(PREF_AT, 0L).apply()
        update(ctx, force = true)
    }

    fun stop(ctx: Context) { prefs(ctx).edit().putLong(PREF_START, 0L).apply() }

    /** Read the steps and move along the road (at most every ten minutes unless [force]). [done] runs on the main thread when it moved. */
    fun update(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val p = prefs(ctx)
        val start = p.getLong(PREF_START, 0L)
        if (start == 0L || !permitted(ctx)) return
        if (!force && System.currentTimeMillis() - p.getLong(PREF_AT, 0L) < 10 * 60_000L) return
        p.edit().putLong(PREF_AT, System.currentTimeMillis()).apply()
        val app = ctx.applicationContext
        if (healthConnect) readHealthConnect(app, start) { steps -> moved(app, steps, done) }
        else readSensor(app) { steps -> moved(app, steps, done) }
    }

    private fun readHealthConnect(ctx: Context, start: Long, got: (Long) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val hcm = ctx.getSystemService(android.health.connect.HealthConnectManager::class.java) ?: return
            val filter = android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(Instant.ofEpochMilli(start)).setEndTime(Instant.now()).build()
            val req = android.health.connect.AggregateRecordsRequest.Builder<Long>(filter)
                .addAggregationType(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL).build()
            hcm.aggregate(req, exec, object : OutcomeReceiver<android.health.connect.AggregateRecordsResponse<Long>, android.health.connect.HealthConnectException> {
                override fun onResult(r: android.health.connect.AggregateRecordsResponse<Long>) { got(r.get(android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL) ?: 0L) }
                override fun onError(e: android.health.connect.HealthConnectException) { }
            })
        } catch (_: Throwable) { }
    }

    /** The phone's step counter counts from boot: add what's new since we last looked. */
    private fun readSensor(ctx: Context, got: (Long) -> Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                sm.unregisterListener(this)
                val now = e.values[0].toLong()
                val p = prefs(ctx)
                val last = p.getLong(PREF_SENSOR_LAST, -1L)
                var total = p.getLong(PREF_SENSOR_STEPS, 0L)
                if (last >= 0) total += if (now >= last) now - last else now   // a restart began the count again
                p.edit().putLong(PREF_SENSOR_LAST, now).putLong(PREF_SENSOR_STEPS, total).apply()
                got(total)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        main.post { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun moved(ctx: Context, steps: Long, done: (() -> Unit)?) {
        val p = prefs(ctx)
        val km = (steps * STRIDE_KM).coerceAtMost(TOTAL)
        val was = p.getInt(PREF_PASSED, 0)
        val now = PLACES.indexOfLast { it.km <= km }.coerceAtLeast(0)
        val e = p.edit().putFloat(PREF_KM, km)
        if (now > was) {
            e.putInt(PREF_PASSED, now)
            val place = PLACES[now]
            e.putString(PREF_BANNER, "${LocalDate.now().toEpochDay()}|${place.name}")
            for (i in was + 1..now) if (PLACES[i].deed) Deeds.saw(ctx, "walk_" + PLACES[i].id)
            note(ctx, place)
        }
        e.apply()
        main.post { GoogleSync.tellWatch(ctx); done?.invoke() }
    }

    private fun note(ctx: Context, place: Place) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("journey") == null)
            nm.createNotificationChannel(NotificationChannel("journey", "The walk to Mount Doom", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val text = if (place.id == "doom") "The whole road, on your own two feet. It's done." else next(ctx)?.let { "${it.second} km on to ${it.first.name}." } ?: ""
        val n = android.app.Notification.Builder(ctx, "journey")
            .setSmallIcon(R.drawable.g_clock)
            .setContentTitle("You've reached ${place.name}")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build()
        try { nm.notify(7302, n) } catch (_: SecurityException) { }
    }

    /** The furthest place reached. */
    fun last(ctx: Context): Place = PLACES[prefs(ctx).getInt(PREF_PASSED, 0).coerceIn(0, PLACES.size - 1)]

    /** The next place and the whole kilometres to it, or null at the end of the road. */
    fun next(ctx: Context): Pair<Place, Int>? {
        val km = km(ctx)
        val n = PLACES.firstOrNull { it.km > km } ?: return null
        return n to kotlin.math.ceil(n.km - km).toInt().coerceAtLeast(1)
    }

    /** "312 km on the road · Weathertop in 71 km". */
    fun line(ctx: Context): String {
        if (!started(ctx)) return ""
        val km = km(ctx).toInt()
        val n = next(ctx) ?: return "At Mount Doom: $km km, the whole road"
        return if (km == 0) "Setting out from Bag End · ${n.first.name} in ${n.second} km"
        else "$km km on the road · ${n.first.name.replaceFirstChar { it.uppercase() }} in ${n.second} km"
    }

    /** The place reached today, if any (its name is strung across the scene). */
    fun bannerToday(ctx: Context): String? {
        val v = prefs(ctx).getString(PREF_BANNER, null) ?: return null
        val (d, name) = v.split("|", limit = 2).let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1) ?: "") }
        return if (d == LocalDate.now().toEpochDay() && name.isNotEmpty()) name.replaceFirstChar { it.uppercase() } else null
    }
}
