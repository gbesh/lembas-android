package com.middleearth.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rune shortcuts: draw a letter on the home screen to open an app (tap once, then draw; or start
 * the stroke sideways). You teach each rune by drawing it twice; an M opens Maps out of the box.
 * The shapes are kept on the phone. Recognition compares the stroke's shape with each rune you
 * taught (resampled, centred and scaled, keeping its direction) and only opens an app when one
 * matches clearly.
 */
object Runes {
    private const val PREF = "runes"
    private const val N = 48

    class Rune(val key: String, val label: String, val pts: FloatArray)

    // ------------------------------------------------------------------ the shapes

    /** A stroke as [N] evenly spaced points, centred and scaled to fit a unit box (keeping its proportions). */
    fun normalise(raw: FloatArray): FloatArray? {
        if (raw.size < 8) return null
        var len = 0f
        for (i in 2 until raw.size step 2) len += hypot(raw[i] - raw[i - 2], raw[i + 1] - raw[i - 1])
        if (len <= 0f) return null
        val step = len / (N - 1)
        val out = FloatArray(N * 2)
        out[0] = raw[0]; out[1] = raw[1]
        var n = 1; var acc = 0f
        var px = raw[0]; var py = raw[1]
        var i = 2
        while (i < raw.size && n < N) {
            val qx = raw[i]; val qy = raw[i + 1]
            val d = hypot(qx - px, qy - py)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val nx = px + t * (qx - px); val ny = py + t * (qy - py)
                out[n * 2] = nx; out[n * 2 + 1] = ny; n++
                px = nx; py = ny; acc = 0f
            } else { acc += d; px = qx; py = qy; i += 2 }
        }
        while (n < N) { out[n * 2] = raw[raw.size - 2]; out[n * 2 + 1] = raw[raw.size - 1]; n++ }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var cx = 0f; var cy = 0f
        for (k in 0 until N) { val x = out[k * 2]; val y = out[k * 2 + 1]; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); cx += x; cy += y }
        cx /= N; cy /= N
        val s = max(maxX - minX, maxY - minY).takeIf { it > 0f } ?: return null
        for (k in 0 until N) { out[k * 2] = (out[k * 2] - cx) / s; out[k * 2 + 1] = (out[k * 2 + 1] - cy) / s }
        return out
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (k in 0 until N) d += hypot(a[k * 2] - b[k * 2], a[k * 2 + 1] - b[k * 2 + 1])
        return d / N
    }

    /** The rune a drawn stroke matches (its key is an app key, or "pkg:<package>"), or null when nothing matches clearly. */
    fun match(ctx: Context, raw: FloatArray): Rune? {
        val s = normalise(raw) ?: return null
        val scored = all(ctx).map { it to distance(s, it.pts) }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second > .13f) return null
        val rival = scored.firstOrNull { it.first.key != best.first.key }
        if (rival != null && rival.second < best.second * 1.25f) return null
        return best.first
    }

    // ------------------------------------------------------------------ the runes you've taught

    private val mapsM: FloatArray by lazy {   // M, drawn up from the bottom left
        normalise(floatArrayOf(0f, 1f, 0f, .5f, 0f, 0f, .25f, .3f, .5f, .6f, .75f, .3f, 1f, 0f, 1f, .5f, 1f, 1f))!!
    }
    private val mapsM2: FloatArray by lazy {  // and with slanted legs
        normalise(floatArrayOf(.05f, 1f, .1f, 0f, .5f, .7f, .9f, 0f, .95f, 1f))!!
    }
    private const val MAPS = "com.google.android.apps.maps"

    fun all(ctx: Context): List<Rune> {
        val list = ArrayList<Rune>()
        val raw = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getString(PREF, null)
        if (raw != null) try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = o.getJSONArray("p")
                list.add(Rune(o.getString("k"), o.optString("l"), FloatArray(p.length()) { p.getDouble(it).toFloat() }))
            }
        } catch (_: Exception) { }
        // M for Maps, until you teach an M (or anything) for Maps yourself, or remove it
        val mapsOff = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean("rune_maps_off", false)
        if (!mapsOff && list.none { it.key.contains(MAPS) }) {
            val key = mapsKey(ctx)
            if (key != null) { list.add(Rune(key, "Maps", mapsM)); list.add(Rune(key, "Maps", mapsM2)) }
        }
        return list
    }

    private fun mapsKey(ctx: Context): String? = try {
        val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        la?.getActivityList(MAPS, android.os.Process.myUserHandle())?.firstOrNull()?.let { "pkg:$MAPS" }
    } catch (_: Exception) { null }

    private fun save(ctx: Context, list: List<Rune>) {
        val a = JSONArray()
        for (r in list) a.put(JSONObject().put("k", r.key).put("l", r.label).put("p", JSONArray().apply { r.pts.forEach { put(it.toDouble()) } }))
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, a.toString()).apply()
    }

    private fun taught(ctx: Context): MutableList<Rune> = all(ctx).filter { it.pts !== mapsM && it.pts !== mapsM2 }.toMutableList()

    fun add(ctx: Context, key: String, label: String, strokes: List<FloatArray>) {
        val list = taught(ctx)
        strokes.mapNotNull { normalise(it) }.forEach { list.add(Rune(key, label, it)) }
        save(ctx, list)
    }

    fun remove(ctx: Context, key: String) {
        if (key.contains(MAPS)) ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean("rune_maps_off", true).apply()
        save(ctx, taught(ctx).filter { it.key != key })
    }

    /** One line per rune for the settings sheet: "Maps, Camera". */
    fun summary(ctx: Context): String = all(ctx).map { it.label }.distinct().let { if (it.isEmpty()) "None yet" else it.joinToString(", ") }

    // ------------------------------------------------------------------ teaching a rune

    /**
     * The runes you have, and a way to teach a new one: pick an app, then draw its rune twice on
     * the pad. [apps] are (key, label) pairs.
     */
    fun manage(activity: Activity, theme: Int, look: Look, apps: List<Pair<String, String>>) {
        val have = all(activity).distinctBy { it.key }
        val items = have.map { "Forget the rune for ${it.label}" } + "Teach a new rune…"
        AlertDialog.Builder(activity, theme)
            .setTitle("Rune shortcuts")
            .setMessage("On the home screen, tap once and then draw (or start your stroke sideways). A rune you've taught opens its app.")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < have.size) remove(activity, have[i].key)
                else pickApp(activity, theme, look, apps)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun pickApp(activity: Activity, theme: Int, look: Look, apps: List<Pair<String, String>>) {
        AlertDialog.Builder(activity, theme)
            .setTitle("A rune for which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> teach(activity, theme, look, apps[i].first, apps[i].second) }
            .show()
    }

    private fun teach(activity: Activity, theme: Int, look: Look, key: String, label: String) {
        val dp = activity.resources.displayMetrics.density
        val strokes = ArrayList<FloatArray>()
        val prompt = TextView(activity).apply {
            text = "Draw the rune for $label"
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val pad = RunePad(activity).apply { color = look.accent }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            addView(prompt)
            addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (260 * dp).toInt()))
        }
        val dialog = AlertDialog.Builder(activity, theme).setTitle("Teach a rune").setView(box).setNegativeButton("Cancel", null).create()
        pad.onStroke = { s ->
            if (normalise(s) == null) prompt.text = "A little bigger, please"
            else {
                strokes.add(s)
                if (strokes.size == 1) { prompt.text = "And once more, the same way"; pad.clearSoon() }
                else {
                    add(activity, key, label, strokes)
                    android.widget.Toast.makeText(activity, "Draw it on the home screen to open $label", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}

/** The pad you teach a rune on: one stroke at a time, drawn in the look's accent. */
class RunePad @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var color = 0xFFC9A04C.toInt()
    var onStroke: ((FloatArray) -> Unit)? = null
    private val dp = resources.displayMetrics.density
    private val path = Path()
    private val pts = ArrayList<Float>()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 8f), 0f) }

    fun clearSoon() = postDelayed({ path.reset(); invalidate() }, 450)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); path.reset(); pts.clear(); path.moveTo(e.x, e.y); pts.add(e.x); pts.add(e.y) }
            MotionEvent.ACTION_MOVE -> { for (h in 0 until e.historySize) { path.lineTo(e.getHistoricalX(h), e.getHistoricalY(h)); pts.add(e.getHistoricalX(h)); pts.add(e.getHistoricalY(h)) }; path.lineTo(e.x, e.y); pts.add(e.x); pts.add(e.y) }
            MotionEvent.ACTION_UP -> onStroke?.invoke(pts.toFloatArray())
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        frame.color = (color and 0x00FFFFFF) or 0x55000000; frame.strokeWidth = 1.5f * dp
        c.drawRoundRect(dp, dp, width - dp, height - dp, 18 * dp, 18 * dp, frame)
        ink.color = color; ink.strokeWidth = 5 * dp
        c.drawPath(path, ink)
    }
}

/** The glowing trail a rune leaves on the home screen as you draw it, fading when you lift. */
class RuneTrail @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val path = Path()
    private var fadeFrom = 0L
    private var good = false
    var color = 0xFFC9A04C.toInt()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun begin(x: Float, y: Float) { path.reset(); path.moveTo(x, y); fadeFrom = 0L; visibility = VISIBLE; invalidate() }
    fun extend(x: Float, y: Float) { path.lineTo(x, y); invalidate() }
    /** Lifted: [matched] flares the trail before it fades; otherwise it just fades. */
    fun end(matched: Boolean) { good = matched; fadeFrom = SystemClock.uptimeMillis(); invalidate() }

    override fun onDraw(c: Canvas) {
        var a = 1f
        if (fadeFrom > 0L) {
            val p = (SystemClock.uptimeMillis() - fadeFrom) / (if (good) 700f else 450f)
            if (p >= 1f) { path.reset(); fadeFrom = 0L; visibility = GONE; return }
            a = 1f - p
            postInvalidateOnAnimation()
        }
        val boost = if (good && fadeFrom > 0L) 1.6f else 1f
        glow.color = color; glow.alpha = (90 * a).toInt(); glow.strokeWidth = 14 * dp * boost
        if (glow.maskFilter == null) glow.maskFilter = BlurMaskFilter(8 * dp, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, glow)
        core.color = 0xFFFFF6E0.toInt(); core.alpha = (235 * a).toInt(); core.strokeWidth = 3.2f * dp
        c.drawPath(path, core)
    }
}
