package com.middleearth.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.Choreographer
import android.view.View
import java.time.LocalDate
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The four scenes, shared with Lembas for Windows and the watch: the same drawing
 * (scenes/index.html in lembas-windows), rendered to still layers in assets/scenes, with the
 * moving parts drawn here between them. A tall version for an upright phone, a panorama
 * (the PC one) for landscape and tablets, each in all four seasons.
 *
 * Layers, back to front: back · [under] · mid · [moving] · front · [over] · weather.
 */
object SceneArt {
    val LAYERS = mapOf("shire" to listOf("back", "mid", "front"), "redbook" to listOf("back"),
        "starlit" to listOf("back", "front"), "elven" to listOf("back", "mid"))
    val SEASONS = listOf("spring", "summer", "autumn", "winter")

    /** The season by the month and hemisphere (the same rule as the PC and the watch). */
    fun season(date: LocalDate = LocalDate.now()): String {
        val north = intArrayOf(3, 3, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3)[date.monthValue - 1]
        return SEASONS[if (southern()) (north + 2) % 4 else north]
    }

    fun southern(): Boolean {
        val id = TimeZone.getDefault().id
        Sun.place(id)?.let { return it.first < 0 }
        return id.startsWith("Australia/") || id.startsWith("Antarctica/") ||
            id == "Pacific/Auckland" || id == "Pacific/Chatham" || id == "Pacific/Fiji" ||
            id.startsWith("America/Argentina") || id == "America/Santiago" || id == "America/Sao_Paulo" ||
            id == "America/Montevideo" || id == "America/Asuncion" || id == "America/La_Paz" || id == "America/Lima" ||
            id == "Africa/Johannesburg" || id == "Africa/Maputo" || id == "Africa/Harare" || id == "Africa/Windhoek" ||
            id == "Indian/Mauritius" || id == "Indian/Reunion" || id == "Indian/Antananarivo"
    }

    /** The design's size: tall (1080×2400) or the panorama (2880×1080, its x running -480..2400). */
    class Design(val land: Boolean) {
        val w = if (land) 2880f else 1080f
        val h = if (land) 1080f else 2400f
        val x0 = if (land) -480f else 0f
    }

    /** Fills [w]×[h] like "cover": centred, anchored to the bottom. Design x,y → dx + x·s, dy + y·s. */
    class Fit(val d: Design, w: Float, h: Float, over: Float = 1f) {
        val s = max(w / d.w, h / d.h) * over
        val dx = (w - d.w * s) / 2 - d.x0 * s
        val dy = h - d.h * s + (if (over > 1f) d.h * s * (over - 1f) / over / 2 else 0f)
        fun rect(shift: Float = 0f, lift: Float = 0f) = RectF(dx + d.x0 * s + shift, dy + lift, dx + (d.x0 + d.w) * s + shift, dy + d.h * s + lift)
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** One still layer, decoded at about the size it's shown ([targetH] pixels tall). */
    fun layer(ctx: Context, look: String, season: String, land: Boolean, layer: String, targetH: Int): Bitmap? {
        val name = "scenes/scene_${look}_${season}_${if (land) "l" else "p"}_$layer.webp"
        val full = if (land) 1080 else 3000
        var sample = 1
        while (full / (sample * 2) >= targetH) sample *= 2
        val key = "$name@$sample"
        cache.get(key)?.let { return it }
        // short of memory (a big screen, a busy phone): drop what's cached and try once more at half size
        for (attempt in 0..1) {
            val sz = sample shl attempt
            try {
                ctx.assets.open(name).use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sz })
                }?.let { it.prepareToDraw(); cache.put(key, it); return it }
            } catch (_: OutOfMemoryError) { cache.evictAll() } catch (_: Exception) { return null }
        }
        return null
    }

    /** The whole scene as one still picture (the wallpaper, the look previews). */
    fun still(ctx: Context, look: Look, w: Int, h: Int, dp: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val d = SceneDrawer(ctx, look, w, h, dp, weather = 0, still = true)
        d.draw(Canvas(bmp), 0f)
        bmp.prepareToDraw()
        return bmp
    }
}

/** A minimal SVG path reader (M L H V C S Q Z, absolute and relative), for the shapes the scenes share with the PC. */
object SvgPath {
    fun parse(d: String): Path {
        val p = Path()
        var i = 0
        var cmd = 'M'
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f; var lx = 0f; var ly = 0f
        fun skip() { while (i < d.length && (d[i] == ' ' || d[i] == ',' || d[i] == '\n')) i++ }
        fun num(): Float {
            skip()
            val st = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            while (i < d.length && (d[i].isDigit() || d[i] == '.')) i++
            return d.substring(st, i).toFloat()
        }
        while (true) {
            skip()
            if (i >= d.length) break
            if (d[i].isLetter()) cmd = d[i++] else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f; val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { cx = num() + ox; cy = num() + oy; p.moveTo(cx, cy); sx = cx; sy = cy; lx = cx; ly = cy }
                'L' -> { cx = num() + ox; cy = num() + oy; p.lineTo(cx, cy); lx = cx; ly = cy }
                'H' -> { cx = num() + ox; p.lineTo(cx, cy); lx = cx; ly = cy }
                'V' -> { cy = num() + oy; p.lineTo(cx, cy); lx = cx; ly = cy }
                'C' -> {
                    val x1 = num() + ox; val y1 = num() + oy; val x2 = num() + ox; val y2 = num() + oy; val x = num() + ox; val y = num() + oy
                    p.cubicTo(x1, y1, x2, y2, x, y); lx = x2; ly = y2; cx = x; cy = y
                }
                'S' -> {
                    val x1 = 2 * cx - lx; val y1 = 2 * cy - ly
                    val x2 = num() + ox; val y2 = num() + oy; val x = num() + ox; val y = num() + oy
                    p.cubicTo(x1, y1, x2, y2, x, y); lx = x2; ly = y2; cx = x; cy = y
                }
                'Q' -> {
                    val x1 = num() + ox; val y1 = num() + oy; val x = num() + ox; val y = num() + oy
                    p.quadTo(x1, y1, x, y); lx = x1; ly = y1; cx = x; cy = y
                }
                'Z' -> { p.close(); cx = sx; cy = sy }
                else -> i++
            }
        }
        return p
    }
}

/**
 * The moving parts of one scene, in the design's units. Each look has its own (sun, clouds,
 * chimney smoke and river glints; the campfire, embers and compass; stars, the moon, lake glints
 * and the cabin window; mist, lanterns and rising motes), plus the weather.
 */
class Motion(private val look: String, land: Boolean, private val weather: Int, private val still: Boolean) {
    private val L = land
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val x0 = if (land) -480f else 0f
    private val x1 = if (land) 2400f else 1080f
    private val hgt = if (land) 1080f else 2400f
    private fun phase(t: Float, dur: Float, off: Float): Float { val p = ((t + off) % dur) / dur; return if (p < 0) p + 1 else p }
    private fun breathe(t: Float, dur: Float, off: Float = 0f) = (0.5f - 0.5f * cos(2 * PI * phase(t, dur, off))).toFloat()
    private fun easeOut(x: Float) = 1 - (1 - x) * (1 - x)
    private fun radial(r: Float, c0: Int, c1: Int, mid: Float = -1f, cm: Int = 0): RadialGradient =
        if (mid < 0) RadialGradient(0f, 0f, r, c0, c1, Shader.TileMode.CLAMP)
        else RadialGradient(0f, 0f, r, intArrayOf(c0, cm, c1), floatArrayOf(0f, mid, 1f), Shader.TileMode.CLAMP)
    private fun glowAt(c: Canvas, x: Float, y: Float, sc: Float, alpha: Float, shader: Shader, r: Float) {
        c.save(); c.translate(x, y); c.scale(sc, sc)
        glow.shader = shader; glow.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawCircle(0f, 0f, r, glow)
        c.restore()
    }

    // --- Shire Morning
    private val sun = if (L) floatArrayOf(1390f, 250f) else floatArrayOf(830f, 900f)
    private val sunShader = radial(300f, 0xFFFFF6D8.toInt(), 0x00FFE7A6, .28f, 0xFFFFEFC0.toInt())
    private class Cloud(val x: Float, val y: Float, val s: Float, val o: Float, val dur: Float)
    private val clouds = if (L) listOf(Cloud(1640f, 190f, 1f, .9f, 260f), Cloud(980f, 140f, .7f, .75f, 320f), Cloud(1240f, 420f, .55f, .8f, 380f), Cloud(320f, 470f, .6f, .7f, 300f), Cloud(-260f, 260f, .8f, .8f, 340f))
        else listOf(Cloud(180f, 900f, .9f, .85f, 220f), Cloud(900f, 1150f, .7f, .8f, 280f), Cloud(520f, 780f, .5f, .7f, 340f))
    private val cloudPath = Path().apply {
        addRoundRect(RectF(-90f, -8f, 110f, 26f), 17f, 17f, Path.Direction.CW)
        addCircle(-30f, -6f, 32f, Path.Direction.CW); addCircle(20f, -22f, 42f, Path.Direction.CW); addCircle(66f, -2f, 26f, Path.Direction.CW)
    }
    private val riverGlints = if (L) listOf(floatArrayOf(140f, 775f, 70f), floatArrayOf(520f, 792f, 40f), floatArrayOf(1010f, 771f, 90f), floatArrayOf(1560f, 752f, 50f), floatArrayOf(-300f, 768f, 60f), floatArrayOf(2100f, 770f, 70f))
        else listOf(floatArrayOf(120f, 1572f, 60f), floatArrayOf(560f, 1590f, 40f), floatArrayOf(900f, 1548f, 50f))
    private val chimney = if (L) floatArrayOf(1392f, 806f, 1f) else floatArrayOf(897f, 1707f, 1.45f)

    private fun cloud(c: Canvas, cl: Cloud, t: Float, color: Int = 0xFFFFFFFF.toInt()) {
        val span = (x1 - x0) + 1400f
        val x = if (still) cl.x else x0 - 700f + span * phase(t, cl.dur, (cl.x - x0 + 700f) / span * cl.dur)
        c.save(); c.translate(x, cl.y); c.scale(cl.s, cl.s)
        fill.color = color; fill.alpha = (255 * cl.o).toInt()
        c.drawPath(cloudPath, fill)
        c.restore()
    }
    private fun glint(c: Canvas, x: Float, y: Float, w: Float, t: Float, dur: Float, off: Float, width: Float, color: Int) {
        val p = if (still) .5f else phase(t, dur, off)
        val k = (0.5f - 0.5f * cos(2 * PI * p)).toFloat()
        val dx = -14f + 28f * p
        line.color = color; line.alpha = (217 * k).toInt(); line.strokeWidth = width
        c.drawLine(x + dx, y, x + dx + w, y, line)
    }

    // --- Red Book
    private val fire = if (L) floatArrayOf(1210f, 930f, 1f) else floatArrayOf(560f, 1860f, 1.5f)
    private val compass = if (L) floatArrayOf(1760f, 900f) else floatArrayOf(900f, 1900f)
    private val fireShader = radial(230f, 0x8CFFB25B.toInt(), 0x00FFB25B)
    private class FlameShape(val path: Path, val ox: Float, val oy: Float, val dur: Float, val off: Float, val color: Int)
    private val flames = listOf(
        Triple("M0,-118 C34,-72 46,-46 30,-6 C22,14 -22,14 -30,-6 C-44,-40 -18,-62 0,-118Z", floatArrayOf(1.3f, 0f), 0xFFC8401F.toInt()),
        Triple("M2,-74 C22,-46 26,-30 16,-8 C10,4 -12,4 -16,-8 C-24,-30 -8,-44 2,-74Z", floatArrayOf(1.05f, .4f), 0xFFF29A3A.toInt()),
        Triple("M2,-40 C12,-26 12,-16 7,-6 C4,0 -6,0 -8,-6 C-11,-16 -4,-24 2,-40Z", floatArrayOf(.8f, .2f), 0xFFFFE07A.toInt()),
    ).map { (d, tm, col) ->
        val p = SvgPath.parse(d); val b = RectF(); p.computeBounds(b, true)
        FlameShape(p, (b.left + b.right) / 2, b.bottom, tm[0], tm[1], col)
    }
    private val needleNS = SvgPath.parse("M0,-96 L14,0 L0,96 L-14,0Z")
    private val needleEW = SvgPath.parse("M-96,0 L0,-14 L96,0 L0,14Z")
    private val logs = SvgPath.parse("M-46,24 L46,-6 M-46,-6 L46,24")
    private val mtx = Matrix()
    private fun track(p: Float, at: FloatArray, v: FloatArray): Float {
        for (i in 1 until at.size) if (p <= at[i]) {
            val k = (p - at[i - 1]) / (at[i] - at[i - 1]); val e = (0.5f - 0.5f * cos(PI * k)).toFloat()
            return v[i - 1] + (v[i] - v[i - 1]) * e
        }
        return v.last()
    }

    // --- Starlit Night
    private val moon = if (L) floatArrayOf(1480f, 230f, 54f) else floatArrayOf(820f, 960f, 64f)
    private val moonShader = radial(moon[2] * 3.2f, 0x8CFFF7E0.toInt(), 0x00FFF7E0)
    private val lakeGlint = if (L) floatArrayOf(1480f, 812f) else floatArrayOf(820f, 1786f)
    private val cabin = if (L) floatArrayOf(600f, 905f, 1f) else floatArrayOf(430f, 1960f, 1.25f)
    private val twinkles = Random(1954).let { r -> List(40) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), r.nextFloat() * (if (L) 430f else 900f), 1.4f + r.nextFloat() * 1.2f, 2f + r.nextFloat() * 3.5f, r.nextFloat() * 5f) } }

    // --- Elven Silver
    private val star = if (L) floatArrayOf(1160f, 150f, 1f) else floatArrayOf(540f, 140f, 1.2f)
    private val starShader = radial(90f, 0x80BFE3EC.toInt(), 0x00BFE3EC)
    private val lamps = if (L) listOf(floatArrayOf(880f, 760f), floatArrayOf(1030f, 700f), floatArrayOf(1180f, 740f), floatArrayOf(960f, 820f), floatArrayOf(1110f, 800f), floatArrayOf(1250f, 830f), floatArrayOf(760f, 820f))
        else listOf(floatArrayOf(420f, 1700f), floatArrayOf(560f, 1600f), floatArrayOf(700f, 1680f), floatArrayOf(500f, 1800f), floatArrayOf(640f, 1780f), floatArrayOf(330f, 1820f))
    private val lampShader = radial(if (L) 22f else 28f, 0x73F3E3B4, 0x00F3E3B4)
    private val motes = Random(1955).let { r -> List(18) { floatArrayOf(if (L) 640 + r.nextFloat() * 760 else 200 + r.nextFloat() * 700, if (L) 900 + r.nextFloat() * 120 else 1950 + r.nextFloat() * 200, 9 + r.nextFloat() * 8, r.nextFloat() * 16) } }
    private val mistY = if (L) floatArrayOf(640f, 140f) else floatArrayOf(1500f, 220f)
    private val mistShader = LinearGradient(0f, mistY[0], 0f, mistY[0] + mistY[1], intArrayOf(0x009FC7D0, 0x1C9FC7D0, 0x009FC7D0), null, Shader.TileMode.CLAMP)

    // --- weather
    private val drops = Random(77).let { r -> List(if (L) 90 else 70) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0 + 200), .8f + r.nextFloat() * .5f, r.nextFloat() * 2, 30 + r.nextFloat() * 30) } }
    private val flakes = Random(78).let { r -> List(if (L) 80 else 70) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), 10 + r.nextFloat() * 9, r.nextFloat() * 20, 1.6f + r.nextFloat() * 2.2f) } }
    private val fogShader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x00E6EEF0, 0x30E6EEF0, 0x00E6EEF0), null, Shader.TileMode.CLAMP)

    /** Behind the middle layer. */
    fun under(c: Canvas, t: Float) {
        when (look) {
            "shire" -> {
                glowAt(c, sun[0], sun[1], 1f + .08f * (if (still) 0f else breathe(t, 6f)), .8f + .2f * breathe(t, 6f), sunShader, 300f)
                if (weather != 5) for (cl in clouds) cloud(c, cl, t)
            }
            "starlit" -> {
                for (s in twinkles) {
                    val k = if (still) .7f else breathe(t, s[3], s[4])
                    fill.color = 0xFFFFF8E6.toInt(); fill.alpha = (255 * (.15f + .85f * k)).toInt()
                    c.drawCircle(s[0], s[1], s[2] * (.6f + .65f * k), fill)
                }
                glowAt(c, moon[0], moon[1], 1f + .06f * breathe(t, 7f), .75f + .25f * breathe(t, 7f), moonShader, moon[2] * 3.2f)
                drawMoon(c, moon[0], moon[1], moon[2])
                for ((i, g) in listOf(floatArrayOf(-80f, 0f, 160f), floatArrayOf(-60f, 28f, 120f), floatArrayOf(-44f, 58f, 88f), floatArrayOf(-30f, 94f, 60f), floatArrayOf(-20f, 136f, 40f), floatArrayOf(-12f, 186f, 24f)).withIndex())
                    glint(c, lakeGlint[0] + g[0], lakeGlint[1] + g[1], g[2], t, 3f + i * .5f, i * .6f, if (L) 4f else 5f, 0xFFFFF3D6.toInt())
            }
            "elven" -> {
                glowAt(c, star[0], star[1], star[2] * (1f + .08f * breathe(t, 6f)), .8f + .2f * breathe(t, 6f), starShader, 90f)
                val dx = if (still) 0f else (-120 * cos(2 * PI * phase(t, 80f, 0f))).toFloat()
                glow.shader = mistShader; glow.alpha = 255
                c.drawRect(x0 - 200 + dx, mistY[0], x1 + 200 + dx, mistY[0] + mistY[1], glow)
            }
        }
    }

    /** Between the middle layer and the front. */
    fun moving(c: Canvas, t: Float) {
        when (look) {
            "shire" -> for ((i, g) in riverGlints.withIndex()) glint(c, g[0], g[1], g[2], t, 4f + i % 3, i * 1.3f, if (L) 3f else 4f, 0xFFFFFFFF.toInt())
            "redbook" -> {
                val fx = fire[0]; val fy = fire[1]; val fs = fire[2]
                glowAt(c, fx, fy - 30 * fs, fs * (1f + .04f * breathe(t, 2.2f)), .7f + .3f * breathe(t, 2.2f), fireShader, 230f)
                c.save(); c.translate(fx, fy); c.scale(fs, fs)
                line.color = 0xFF5A3F22.toInt(); line.alpha = 255; line.strokeWidth = 9f
                c.drawPath(logs, line)
                for (f in flames) {
                    val p = if (still) 0f else phase(t, f.dur, f.off)
                    val at = floatArrayOf(0f, .25f, .5f, .75f, 1f)
                    mtx.reset()
                    mtx.postTranslate(-f.ox, -f.oy)
                    mtx.postSkew(Math.tan(Math.toRadians(track(p, at, floatArrayOf(0f, -3f, 2f, -1f, 0f)).toDouble())).toFloat(), 0f)
                    mtx.postScale(track(p, at, floatArrayOf(1f, .94f, 1.04f, .97f, 1f)), track(p, at, floatArrayOf(1f, 1.08f, .93f, 1.05f, 1f)))
                    mtx.postTranslate(f.ox, f.oy)
                    c.save(); c.concat(mtx)
                    fill.color = f.color; fill.alpha = 255
                    c.drawPath(f.path, fill)
                    c.restore()
                }
                if (!still) for (i in 0 until 6) {
                    val p = phase(t, 2.4f + i * .4f, i * .7f)
                    fill.color = 0xFFFFC56A.toInt(); fill.alpha = (255 * (if (p < .15f) p / .15f else 1 - (p - .15f) / .85f)).toInt()
                    c.drawCircle(-20f + i * 8 + 30 * p, -100 - 260 * p, 2.4f, fill)
                }
                c.restore()
                c.save(); c.translate(compass[0], compass[1])
                c.rotate(if (still) 0f else track(phase(t, 7f, 0f), floatArrayOf(0f, .5f, 1f), floatArrayOf(-6f, 5f, -6f)))
                fill.color = 0x8C5A3F22.toInt(); c.drawPath(needleEW, fill)
                fill.color = 0xD99A2A1C.toInt(); c.drawPath(needleNS, fill)
                c.restore()
            }
        }
    }

    /** In front of everything (smoke above the chimney, the cabin window, lanterns and motes). */
    fun over(c: Canvas, t: Float) {
        when (look) {
            "shire" -> for (k in 0 until 4) {
                val cs = chimney[2]
                if (still) {
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * (.55f - k * .11f)).toInt()
                    c.drawCircle(chimney[0] + k * 14 * cs, chimney[1] - k * 40 * cs, (9 + k * 5) * cs, fill)
                } else {
                    val p = phase(t, 5f, k * 1.25f); val e = easeOut(p)
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (153 * (if (p < .15f) p / .15f else 1 - (p - .15f) / .85f)).toInt()
                    c.drawCircle(chimney[0] + 50 * cs * e, chimney[1] - 200 * cs * e, 12 * cs * (.6f + 1.3f * e), fill)
                }
            }
            "starlit" -> {
                val p = phase(t, 3.2f, 0f)
                val o = if (still) 1f else if (p > .31f && p < .33f) .82f else if (p > .64f && p < .67f) .88f else 1f
                val ks = cabin[2]
                fill.color = 0xFFFFC867.toInt(); fill.alpha = (255 * o).toInt()
                c.drawRect(cabin[0] - 14 * ks, cabin[1] - 3 * ks, cabin[0], cabin[1] + 11 * ks, fill)
                c.drawRect(cabin[0] + 6 * ks, cabin[1] - 3 * ks, cabin[0] + 20 * ks, cabin[1] + 11 * ks, fill)
            }
            "elven" -> {
                for ((i, l) in lamps.withIndex()) {
                    val k = if (still) .5f else breathe(t, 3f + i % 3, i * .9f)
                    glowAt(c, l[0], l[1], .85f + .3f * k, .6f + .4f * k, lampShader, if (L) 22f else 28f)
                    fill.color = 0xFFF3E3B4.toInt(); fill.alpha = 255
                    c.drawCircle(l[0], l[1], if (L) 4.5f else 6f, fill)
                }
                if (!still) for (m in motes) {
                    val p = phase(t, m[2], m[3]); val o = if (p < .2f) p / .2f * .8f else if (p > .8f) (1 - p) / .2f * .6f else .8f - .2f * (p - .2f) / .6f
                    val y = m[1] - 360 * p
                    fill.color = 0xFFC8E1EB.toInt(); fill.alpha = (56 * o).toInt(); c.drawCircle(m[0], y, 6f, fill)
                    fill.color = 0xFFDCEBF0.toInt(); fill.alpha = (255 * o).toInt(); c.drawCircle(m[0], y, 2f, fill)
                }
            }
        }
        weather(c, t)
    }

    private fun weather(c: Canvas, t: Float) {
        if (look == "redbook" || weather == 0 || still) return
        if (weather == 1 || weather == 5) {
            val col = if (look == "shire") 0xFFF2F4F4.toInt() else 0xFF3A4266.toInt()
            val o = if (look == "shire") .85f else .55f
            val list = if (L) listOf(Cloud(200f, 90f, 1.6f, o, 300f), Cloud(1100f, 160f, 1.3f, o, 360f), Cloud(1900f, 70f, 1.8f, o, 420f))
                else listOf(Cloud(160f, 700f, 1.6f, o, 260f), Cloud(800f, 820f, 1.3f, o, 320f))
            for (cl in list) cloud(c, cl, t, col)
        }
        if (weather == 2) for ((y, h) in if (L) listOf(560f to 260f, 760f to 320f) else listOf(1300f to 400f, 1700f to 500f)) {
            val dx = (-160 * cos(2 * PI * phase(t, 90f, y / 100))).toFloat()
            c.save(); c.translate(0f, y); c.scale(1f, h)
            glow.shader = fogShader; glow.alpha = 255
            c.drawRect(x0 - 200 + dx, 0f, x1 + 200 + dx, 1f, glow)
            c.restore()
        }
        if (weather == 3 || weather == 5) {
            line.color = 0x73DCE6EE; line.strokeWidth = if (L) 1.6f else 2.4f
            for (d in drops) {
                val p = phase(t, d[1], d[2]); val x = d[0] - 120 * p; val y = -60 + (hgt + 120) * p
                c.drawLine(x, y, x - d[3] * .1f, y + d[3], line)
            }
        }
        if (weather == 4) {
            fill.color = 0xD9FFFFFF.toInt()
            for (f in flakes) {
                val p = phase(t, f[1], f[2])
                c.drawCircle(f[0] + 30 * sin(p * 2 * PI * 2 + f[0]).toFloat(), -20 + (hgt + 40) * p, f[3] * (if (L) 1f else 1.4f), fill)
            }
        }
    }

    /** Tonight's real moon phase, lit on the correct side for your hemisphere. */
    private fun drawMoon(c: Canvas, cx: Float, cy: Float, r: Float) {
        var phase = ((System.currentTimeMillis() - 947182440000L) / 86_400_000.0 / 29.530588853) % 1.0
        if (phase < 0) phase += 1.0
        fill.color = 0xFF2A3058.toInt(); fill.alpha = 255
        c.drawCircle(cx, cy, r, fill)
        val termW = (r * cos(2 * PI * phase)).toFloat()
        val lit = Path()
        lit.moveTo(cx, cy - r)
        lit.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f, false)
        val aw = max(abs(termW), 0.5f)
        val oval = RectF(cx - aw, cy - r, cx + aw, cy + r)
        if (termW >= 0) lit.arcTo(oval, 90f, -180f, false) else lit.arcTo(oval, 90f, 180f, false)
        lit.close()
        c.save()
        if ((phase > 0.5) xor SceneArt.southern()) c.scale(-1f, 1f, cx, cy)
        fill.color = 0xFFFFF3D6.toInt()
        c.drawPath(lit, fill)
        c.restore()
    }

    /** How often this scene needs a new frame: gentle scenes less often, saving battery. */
    val fps: Int = when {
        weather == 3 || weather == 5 || weather == 4 -> 30
        look == "shire" -> 30      // slow drifting clouds and smoke need more frames to look smooth
        look == "redbook" -> 24
        look == "elven" -> 20
        else -> 20
    }
}

/** Draws one scene (still layers plus the moving parts) at a given size. */
class SceneDrawer(ctx: Context, val look: Look, val w: Int, val h: Int, dp: Float, val weather: Int, still: Boolean = false, val parallax: Boolean = false) {
    val land = w > h
    val season = SceneArt.season()
    private val design = SceneArt.Design(land)
    private val fit = SceneArt.Fit(design, w.toFloat(), h.toFloat(), if (parallax) 1.04f else 1f)
    private val names = SceneArt.LAYERS[look.id] ?: listOf("back")
    private val bitmaps = names.map { SceneArt.layer(ctx, look.id, season, land, it, (design.h * fit.s).toInt()) }
    private val motion = Motion(look.id, land, weather, still)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val maxShift = 12 * dp
    val fps get() = motion.fps

    /** [tx], [ty]: the tilt, -1..1 (for depth); nearer layers move further. */
    fun draw(c: Canvas, t: Float, tx: Float = 0f, ty: Float = 0f) {
        if (bitmaps.firstOrNull() == null) c.drawColor(look.windowColor)
        fun layer(i: Int, depth: Float) {
            val b = bitmaps.getOrNull(i) ?: return
            c.drawBitmap(b, null, fit.rect(tx * maxShift * depth, ty * maxShift * depth * .5f), paint)
        }
        fun moving(depth: Float, block: (Canvas) -> Unit) {
            c.save()
            c.translate(fit.dx + tx * maxShift * depth, fit.dy + ty * maxShift * depth * .5f)
            c.scale(fit.s, fit.s)
            block(c)
            c.restore()
        }
        layer(0, .25f)
        moving(.25f) { motion.under(it, t) }
        val mid = names.indexOf("mid")
        if (mid >= 0) layer(mid, .6f)
        moving(.6f) { motion.moving(it, t) }
        val front = names.indexOf("front")
        if (front >= 0) layer(front, 1f)
        moving(1f) { motion.over(it, t) }
    }
}

/**
 * The home screen's scene, animated: only while the home screen is showing, animations are on and
 * battery saver is off, at the scene's own gentle frame rate. With "tilt depth" on, the layers
 * shift a little as you tilt the phone.
 */
class SceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), SensorEventListener {
    private var drawer: SceneDrawer? = null
    private var running = false
    private var lastFrame = 0L
    private val start = SystemClock.uptimeMillis()
    private val power = context.getSystemService(PowerManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var tiltX = 0f
    private var tiltY = 0f
    private var builtFor = ""

    var look: Look = Look.STARLIT
        set(value) { field = value; rebuild() }
    var animationsOn = true
        set(value) { field = value; update() }
    var active = false
        set(value) { field = value; update() }
    var weather = 0
        set(value) { if (field != value) { field = value; rebuild() } }
    var parallax = true
        set(value) { if (field != value) { field = value; rebuild(); update() } }

    /** Rebuild if something that changes the picture changed (look, size, season, weather). */
    fun refresh() = rebuild()

    private fun rebuild() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val key = "${look.id}#$w#$h#${SceneArt.season()}#$weather#$parallax"
        if (key == builtFor && drawer != null) { invalidate(); return }
        builtFor = key
        drawer = SceneDrawer(context, look, w, h, resources.displayMetrics.density, weather, parallax = parallax)
        invalidate()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000
            val gap = 1000L / (drawer?.fps ?: 15)
            if (now - lastFrame >= gap - 4) { lastFrame = now; invalidate() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        drawer?.draw(canvas, if (running) t else 0f, tiltX, tiltY) ?: canvas.drawColor(look.windowColor)
    }

    private fun update() {
        val should = animationsOn && active && isAttachedToWindow && power?.isPowerSaveMode != true
        if (should != running) {
            running = should
            if (should) Choreographer.getInstance().postFrameCallback(frame)
            else Choreographer.getInstance().removeFrameCallback(frame)
        }
        // the tilt sensor only while animating with tilt depth on
        val tilt = should && parallax
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (tilt && sensor != null) sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        else { sensors?.unregisterListener(this); if (tiltX != 0f || tiltY != 0f) { tiltX = 0f; tiltY = 0f; invalidate() } }
    }

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var baseRoll = Float.NaN
    private var basePitch = Float.NaN
    override fun onSensorChanged(e: SensorEvent) {
        var roll: Float; var pitch: Float
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            roll = (e.values[0] / 9.81f).coerceIn(-1f, 1f); pitch = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
        } else {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, ori)
            roll = ori[2]; pitch = ori[1]
        }
        // relative to how the phone is usually held, drifting slowly so it never gets stuck to one side
        if (baseRoll.isNaN()) { baseRoll = roll; basePitch = pitch }
        baseRoll += (roll - baseRoll) * .02f; basePitch += (pitch - basePitch) * .02f
        val tx = ((roll - baseRoll) * 2.5f).coerceIn(-1f, 1f)
        val ty = ((pitch - basePitch) * 2.5f).coerceIn(-1f, 1f)
        tiltX += (tx - tiltX) * .25f; tiltY += (ty - tiltY) * .25f
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }
}
