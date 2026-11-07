package com.middleearth.launcher

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The touches on top of a scene, in screen pixels:
 *  - the light of the hour: the Shire warms through the afternoon, Starlit Night deepens toward
 *    midnight, and for twenty minutes either side of sunrise and sunset a pink and amber glow
 *    rises from the horizon;
 *  - now and then (for a minute, a few times a day) a hidden visitor: a fox trotting over the
 *    Shire, a little boat on the starlit lake, an owl blinking in Rivendell's trees;
 *  - bunting across the sky on a day with a birthday in your calendar;
 *  - and whatever you tap: sparkles in the night sky, embers from the fire, a puff of cloud.
 */
class SceneExtras(private val look: String, private val w: Int, private val h: Int, private val dp: Float, private val still: Boolean) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    var bunting = false

    // ------------------------------------------------------------------ the light of the hour (worked out once a minute)
    private var tintAt = -1
    private var tint: Paint? = null
    private var glow: Paint? = null

    private fun updateLight() {
        val now = LocalTime.now()
        val m = now.hour * 60 + now.minute
        if (m == tintAt) return
        tintAt = m
        val day = Sun.today()
        tint = null; glow = null
        when (look) {
            "shire" -> {   // warmer as the afternoon goes on
                val f = ((m - day.noon).toFloat() / (day.evening - day.noon).coerceAtLeast(1)).coerceIn(0f, 1f)
                if (f > 0f) tint = Paint().apply { color = 0xFFFFB45A.toInt(); alpha = (26 * f).toInt() }
            }
            "starlit" -> {   // deeper toward 11pm (and in the small hours before dawn), lighter near dusk and dawn
                val fromDusk = (m - day.sunset).let { if (it < 0) it + 1440 else it }
                val toDawn = (day.sunrise - m).let { if (it < 0) it + 1440 else it }
                val f = (minOf(fromDusk, toDawn) / 150f).coerceIn(0f, 1f)
                tint = Paint().apply { color = 0xFF050A26.toInt(); alpha = (64 * f).toInt() }
            }
        }
        // sunrise and sunset: a wash of colour from the horizon
        if (look != "redbook") {
            val near = minOf(abs(m - day.sunrise), abs(m - day.sunset))
            if (near < 20) {
                val a = 1f - near / 20f
                glow = Paint().apply {
                    shader = LinearGradient(0f, h.toFloat(), 0f, h * .35f,
                        intArrayOf((0x8C shl 24) or 0xFF8A5C, (0x48 shl 24) or 0xF2789A, 0x00F2789A), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                    alpha = (255 * a).toInt()
                }
            }
        }
    }

    // ------------------------------------------------------------------ hidden visitors
    private val dayHash = LocalDate.now().toEpochDay().toInt().let { (it * 2654435761L % 180).toInt().let { v -> if (v < 0) v + 180 else v } }

    /** 0..1 through the visitor's minute, or -1 when there's none (eight a day, at different times each day). */
    private fun visitor(): Float {
        val now = LocalTime.now()
        val m = now.hour * 60 + now.minute
        if ((m + dayHash) % 180 != 0) return -1f
        return (now.second + now.nano / 1e9f) / 60f
    }

    private fun drawVisitor(c: Canvas, p: Float) {
        val land = w > h
        val s = (if (land) h / 1080f else w / 1080f) * (if (land) 1f else 1.3f)
        when (look) {
            "shire" -> {   // a fox trots across the meadow
                val x = -80 * s + (w + 160 * s) * p
                val y = h * (if (land) .9f else .82f) - 6 * s * abs(sin(p * 60))
                c.save(); c.translate(x, y); c.scale(s, s)
                fill.color = 0xFFD9733A.toInt(); fill.alpha = 255
                c.drawPath(fox, fill)
                line.color = 0xFF8A3E1A.toInt(); line.strokeWidth = 2.6f; line.alpha = 255
                val k = sin(p * 120) * 4
                c.drawLine(-14f, 8f, -16f + k, 20f, line); c.drawLine(14f, 8f, 16f - k, 20f, line)
                fill.color = 0xFFF4EFE4.toInt(); c.drawCircle(-30f, 2f, 4f, fill)
                c.restore()
            }
            "starlit" -> {   // a little boat crosses the lake, its lantern lit
                val x = w * .15f + w * .55f * p
                val y = h * (if (land) .79f else .75f)
                c.save(); c.translate(x, y); c.scale(s, s)
                fill.color = 0xFF0E0C28.toInt(); fill.alpha = 255
                c.drawPath(boat, fill)
                fill.color = 0xFFFFC867.toInt(); fill.alpha = (200 + 55 * sin(p * 40)).toInt()
                c.drawCircle(10f, -22f, 3.5f, fill)
                fill.alpha = 60; c.drawCircle(10f, -22f, 10f, fill)
                c.restore()
            }
            "elven" -> {   // an owl in the trees, blinking
                val x = w * (if (land) .3f else .28f); val y = h * (if (land) .64f else .62f)
                val fadeIn = (minOf(p, 1 - p) * 8).coerceIn(0f, 1f)
                c.save(); c.translate(x, y); c.scale(s, s)
                fill.color = 0xFF2E3A40.toInt(); fill.alpha = (255 * fadeIn).toInt()
                c.drawOval(-12f, -16f, 12f, 16f, fill)
                val blink = (p * 60 % 4) < 0.25f
                fill.color = 0xFFF3E3B4.toInt(); fill.alpha = (255 * fadeIn).toInt()
                if (blink) { c.drawRect(-8f, -6f, -2f, -5f, fill); c.drawRect(2f, -6f, 8f, -5f, fill) }
                else { c.drawCircle(-5f, -6f, 3.4f, fill); c.drawCircle(5f, -6f, 3.4f, fill) }
                c.restore()
            }
        }
    }
    private val fox = SvgPath.parse("M-30,0 C-24,-8 -8,-10 4,-6 L12,-12 L14,-4 C18,-2 20,2 16,4 L6,4 C2,8 -8,8 -16,6 C-22,10 -30,10 -34,6 C-28,6 -26,4 -30,0Z")
    private val boat = SvgPath.parse("M-26,-4 L26,-4 L18,6 L-18,6Z M8,-4 L8,-28 L10,-28 L10,-4Z M10,-26 L22,-10 L10,-10Z")

    // ------------------------------------------------------------------ bunting (a birthday today)
    private fun drawBunting(c: Canvas) {
        val y0 = h * (if (w > h) .05f else .045f)
        val sag = h * .025f
        val colors = intArrayOf(0xFFE07A5F.toInt(), 0xFFF2CC8F.toInt(), 0xFF81B29A.toInt(), 0xFF7A9CC6.toInt(), 0xFFD98BB0.toInt())
        line.color = 0xCCFFFFFF.toInt(); line.strokeWidth = 1.5f * dp
        val path = Path().apply { moveTo(0f, y0); quadTo(w / 2f, y0 + sag * 2, w.toFloat(), y0) }
        c.drawPath(path, line)
        val n = (w / (26 * dp)).toInt()
        for (i in 0..n) {
            val x = i * w / n.toFloat()
            val f = x / w
            val y = y0 + sag * 4 * f * (1 - f)
            fill.color = colors[i % colors.size]; fill.alpha = 230
            val fl = Path().apply { moveTo(x - 7 * dp, y); lineTo(x + 7 * dp, y); lineTo(x, y + 14 * dp); close() }
            c.drawPath(fl, fill)
        }
    }

    // ------------------------------------------------------------------ Middle-earth's calendar
    private val fireworks = !still && Feasts.fireworks()
    private val lanterns = Feasts.lanterns()
    private val moonLetters = look == "redbook" && Feasts.durin()
    /** Frames a second the calendar needs (fireworks move fast). */
    val fps get() = if (fireworks) 30 else 0

    /** Gandalf's fireworks for Hobbit Day: bursts of gold, green and red over the scene, clear of the clock. */
    private val burstColors = intArrayOf(0xFFFFD27A.toInt(), 0xFF9FE6A0.toInt(), 0xFFFF8A7A.toInt(), 0xFFFFFFFF.toInt(), 0xFFB9C8FF.toInt())
    private fun drawFireworks(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val land = w > h
        for (slot in 0 until 3) {
            val dur = 2.6f; val off = slot * .87f
            val n = floor((t + off) / dur).toInt(); val p = ((t + off) % dur) / dur
            val r = Random(n * 7 + slot)
            val cx = if (land) w * (.45f + .5f * r.nextFloat()) else w * (.15f + .7f * r.nextFloat())
            val cy = if (land) h * (.12f + .3f * r.nextFloat()) else h * (.44f + .14f * r.nextFloat())
            val col = burstColors[r.nextInt(burstColors.size)]
            if (p < .18f) {   // the rocket climbing
                val k = p / .18f
                fill.color = 0xFFFFF0C4.toInt(); fill.alpha = 220
                c.drawCircle(cx, cy + (1 - k) * h * .2f, 2.2f * dp, fill)
                continue
            }
            val k = (p - .18f) / .82f
            val reach = (if (land) h * .16f else w * .2f) * (1 - (1 - k) * (1 - k))
            for (i in 0 until 26) {
                val a = i / 26f * 2 * PI.toFloat() + r.nextFloat() * .2f
                val x = cx + kotlin.math.cos(a) * reach; val y = cy + sin(a) * reach + k * k * 40 * dp   // and they fall a little
                fill.color = col; fill.alpha = (255 * (1 - k)).toInt()
                c.drawCircle(x, y, (2.4f - 1.2f * k) * dp, fill)
            }
        }
    }

    /** Yule and Lithe: paper lanterns strung across the sky, glowing. */
    private fun drawLanterns(c: Canvas) {
        val y0 = h * (if (w > h) .06f else .05f)
        val sag = h * .025f
        line.color = 0x99FFFFFF.toInt(); line.strokeWidth = 1.2f * dp
        c.drawPath(Path().apply { moveTo(0f, y0); quadTo(w / 2f, y0 + sag * 2, w.toFloat(), y0) }, line)
        val n = (w / (54 * dp)).toInt()
        val t = SystemClock.uptimeMillis() / 1000f
        val cols = intArrayOf(0xFFFFC867.toInt(), 0xFFFF9A6A.toInt(), 0xFFFFE08A.toInt(), 0xFFF2B8C6.toInt())
        for (i in 1 until n) {
            val x = i * w / n.toFloat(); val f = x / w
            val y = y0 + sag * 4 * f * (1 - f) + 10 * dp
            val k = if (still) .8f else .7f + .3f * sin(t * 1.3f + i).let { it * it }
            fill.color = cols[i % cols.size]; fill.alpha = (60 * k).toInt()
            c.drawCircle(x, y, 16 * dp, fill)
            fill.alpha = (235 * k).toInt()
            c.drawOval(x - 6 * dp, y - 8 * dp, x + 6 * dp, y + 8 * dp, fill)
            line.color = 0xFF4A3A28.toInt(); line.strokeWidth = 1.4f * dp
            c.drawLine(x - 4 * dp, y - 8.5f * dp, x + 4 * dp, y - 8.5f * dp, line); c.drawLine(x - 4 * dp, y + 8.5f * dp, x + 4 * dp, y + 8.5f * dp, line)
        }
    }

    /** Durin's Day: moon-letters glowing silver-blue on the Red Book's page, two lines of runes. */
    private val runes: List<Path> = Random(1937).let { r ->
        List(26) {
            Path().apply {
                moveTo(0f, 0f); lineTo(0f, 22f)   // the stave
                repeat(1 + r.nextInt(2)) {
                    val y = 2f + r.nextFloat() * 14f; val side = if (r.nextBoolean()) 1f else -1f
                    moveTo(0f, y); lineTo(side * (5f + r.nextFloat() * 4f), y + (if (r.nextBoolean()) 6f else -4f))
                }
            }
        }
    }
    private fun drawMoonLetters(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val k = if (still) .7f else .45f + .3f * (.5f + .5f * sin(t * .7f))
        val land = w > h
        val sc = (if (land) h / 1080f else w / 1080f) * 2.2f
        val left = w * (if (land) .52f else .14f); val top = h * (if (land) .3f else .55f)
        for ((i, g) in runes.withIndex()) {
            val row = i / 13; val col = i % 13
            c.save(); c.translate(left + col * 16 * sc, top + row * 36 * sc); c.scale(sc, sc)
            line.color = 0xFFCFE6FF.toInt(); line.alpha = (60 * k).toInt(); line.strokeWidth = 4.5f; c.drawPath(g, line)
            line.alpha = (230 * k).toInt(); line.strokeWidth = 1.5f; c.drawPath(g, line)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ taps
    private class Poke(val x: Float, val y: Float, val at: Long, val seed: Int)
    private val pokes = ArrayList<Poke>()
    fun poke(x: Float, y: Float) { pokes.add(Poke(x, y, SystemClock.uptimeMillis(), pokes.size + x.toInt())); if (pokes.size > 8) pokes.removeAt(0) }
    val busy get() = pokes.isNotEmpty()

    private fun drawPokes(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        pokes.removeAll { now - it.at > 1600 }
        for (pk in pokes) {
            val p = (now - pk.at) / 1600f
            val r = Random(pk.seed)
            when (look) {
                "redbook" -> for (i in 0 until 9) {   // embers fly up
                    val dx = (r.nextFloat() - .5f) * 80 * dp * p; val dy = -(60 + r.nextFloat() * 120) * dp * p
                    fill.color = 0xFFFFC56A.toInt(); fill.alpha = (255 * (1 - p)).toInt()
                    c.drawCircle(pk.x + dx, pk.y + dy, (1.5f + r.nextFloat() * 1.5f) * dp, fill)
                }
                "shire" -> {   // a little cloud puffs out and drifts away
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (220 * (1 - p)).toInt()
                    val gx = pk.x + 30 * dp * p; val sc = (0.6f + p) * dp
                    c.drawCircle(gx, pk.y, 14 * sc, fill); c.drawCircle(gx + 14 * sc, pk.y - 6 * sc, 18 * sc, fill); c.drawCircle(gx + 30 * sc, pk.y, 12 * sc, fill)
                }
                else -> for (i in 0 until 10) {   // sparkles
                    val a = r.nextFloat() * 2 * PI.toFloat(); val d = (20 + r.nextFloat() * 60) * dp * p
                    val k = sin(PI * p).toFloat()
                    fill.color = if (look == "elven") 0xFFDCEBF0.toInt() else 0xFFFFF3D6.toInt(); fill.alpha = (255 * k).toInt()
                    c.drawCircle(pk.x + d * kotlin.math.cos(a), pk.y + d * sin(a), (1.2f + r.nextFloat() * 2f) * dp * k, fill)
                }
            }
        }
    }

    fun draw(c: Canvas) {
        updateLight()
        tint?.let { c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
        glow?.let { c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
        if (!still) visitor().let { if (it >= 0f) drawVisitor(c, it) }
        if (bunting) drawBunting(c)
        if (lanterns && !bunting) drawLanterns(c)
        if (moonLetters) drawMoonLetters(c)
        if (fireworks) drawFireworks(c)
        if (pokes.isNotEmpty()) drawPokes(c)
    }
}
