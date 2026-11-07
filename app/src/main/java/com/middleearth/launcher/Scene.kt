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
import kotlin.math.sqrt
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
        "starlit" to listOf("back", "front"), "elven" to listOf("back", "mid"),
        "lorien" to listOf("back", "mid", "front"), "havens" to listOf("back", "mid", "front"))
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

    /**
     * Fills [w]×[h] like "cover": centred, anchored to the bottom (or, with [anchor] 0.5, cropped
     * evenly top and bottom: Rivendell's trees stay below the clock on wide upright screens).
     * Design x,y → dx + x·s, dy + y·s.
     */
    class Fit(val d: Design, w: Float, h: Float, over: Float = 1f, anchor: Float = 1f) {
        val s = max(w / d.w, h / d.h) * over
        val dx = (w - d.w * s) / 2 - d.x0 * s
        val dy = (h - d.h * s) * anchor + (if (over > 1f) d.h * s * (over - 1f) / over / 2 else 0f)
        fun rect(shift: Float = 0f, lift: Float = 0f) = RectF(dx + d.x0 * s + shift, dy + lift, dx + (d.x0 + d.w) * s + shift, dy + d.h * s + lift)
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Phones with little memory get the pictures at half size from the start (they still look soft and fine). */
    private var lowMemory: Boolean? = null
    private fun lowMemory(ctx: Context): Boolean = lowMemory ?: run {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        (am == null || am.isLowRamDevice || am.memoryClass < 192).also { lowMemory = it }
    }

    /** One still layer, decoded at about the size it's shown ([targetH] pixels tall). */
    fun layer(ctx: Context, look: String, season: String, land: Boolean, layer: String, targetH: Int): Bitmap? {
        val name = "scenes/scene_${look}_${season}_${if (land) "l" else "p"}_$layer.webp"
        val full = if (land) 1080 else 3000
        var sample = if (lowMemory(ctx)) 2 else 1
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
class Motion(private val look: String, land: Boolean, private val weather: Int, private val still: Boolean, private val wind: Boolean = false, private val sky: Weather.Sky? = null, private val garden: Int = -1) {
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
    private val sun = if (L) floatArrayOf(1390f, 250f) else floatArrayOf(820f, 1060f)
    private val sunShader = radial(300f, 0xFFFFF6D8.toInt(), 0x00FFE7A6, .28f, 0xFFFFEFC0.toInt())
    private class Cloud(val x: Float, val y: Float, val s: Float, val o: Float, val dur: Float)
    private val clouds = if (L) listOf(Cloud(1640f, 190f, 1f, .9f, 260f), Cloud(980f, 140f, .7f, .75f, 320f), Cloud(1240f, 420f, .55f, .8f, 380f), Cloud(320f, 470f, .6f, .7f, 300f), Cloud(-260f, 260f, .8f, .8f, 340f))
        else listOf(Cloud(160f, 1100f, .85f, .85f, 220f), Cloud(930f, 1150f, .62f, .8f, 280f), Cloud(470f, 1210f, .45f, .7f, 340f))   // below the clock and the sun's arc
    private val cloudPath = Path().apply {
        addRoundRect(RectF(-90f, -8f, 110f, 26f), 17f, 17f, Path.Direction.CW)
        addCircle(-30f, -6f, 32f, Path.Direction.CW); addCircle(20f, -22f, 42f, Path.Direction.CW); addCircle(66f, -2f, 26f, Path.Direction.CW)
    }
    private val riverGlints = if (L) listOf(floatArrayOf(140f, 775f, 70f), floatArrayOf(520f, 792f, 40f), floatArrayOf(1010f, 771f, 90f), floatArrayOf(1560f, 752f, 50f), floatArrayOf(-300f, 768f, 60f), floatArrayOf(2100f, 770f, 70f))
        else listOf(floatArrayOf(120f, 1512f, 60f), floatArrayOf(560f, 1530f, 40f), floatArrayOf(900f, 1478f, 50f))
    private val chimney = if (L) floatArrayOf(1392f, 806f, 1f) else floatArrayOf(897f, 1677f, 1.45f)
    // the lantern by Bag End's door (it burns on the lamp oil), and Sam's garden beside the path
    private val lantern = if (L) floatArrayOf(1310f, 875f, 1f) else floatArrayOf(852f, 1802f, 1.6f)
    private val lanternShader = radial(1f, 0xB3FFD98A.toInt(), 0x00FFD98A, .35f, 0x55FFC867)
    private class Plant(val x: Float, val y: Float, val h: Float, val kind: Int, val tone: Int, val sway: Float)
    private val plants: List<Plant> = if (garden < 0 || look != "shire") emptyList() else Random(1937 + garden).let { r ->
        // a bed left of the path: rows of cabbages and flowers, or a tangle of thistles and dock
        val bx = if (L) 1090f else 430f; val by = if (L) 968f else 1990f; val bw = if (L) 120f else 150f; val sc = if (L) 1f else 1.45f
        val n = when (garden) { Garden.BLOOMING -> 22; Garden.FAIR -> 16; else -> 18 }
        List(n) { i ->
            val weed = when (garden) { Garden.WEEDY -> i % 3 != 0; Garden.FAIR -> i % 6 == 0; else -> false }
            val kind = if (weed) 3 + r.nextInt(2) else if (i % 4 == 0) 0 else if (garden == Garden.BLOOMING || i % 2 == 0) 1 else 2
            Plant(bx + r.nextFloat() * bw, by + (i % 3) * 14f * sc / 1.45f * (if (L) 1f else 1.2f) + r.nextFloat() * 6f, (10f + r.nextFloat() * 10f) * sc * (if (weed) 1.6f else 1f), kind, r.nextInt(4), r.nextFloat() * 6.28f)
        }.sortedBy { it.y }
    }
    private val bloomTones = intArrayOf(0xFFE07A8A.toInt(), 0xFFF2C14E.toInt(), 0xFFB08AD8.toInt(), 0xFFF29A6A.toInt())

    private fun cloud(c: Canvas, cl: Cloud, t: Float, color: Int = 0xFFFFFFFF.toInt()) {
        val span = (x1 - x0) + 1400f
        val dur = if (wind) cl.dur / 4 else cl.dur      // a windy day: the clouds race
        val x = if (still) cl.x else x0 - 700f + span * phase(t, dur, (cl.x - x0 + 700f) / span * dur)
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
    private val fire = if (L) floatArrayOf(1210f, 820f, 1f) else floatArrayOf(560f, 1630f, 1.4f)   // the candle's wick (the candle itself is in the art)
    private val compass = if (L) floatArrayOf(1760f, 900f) else floatArrayOf(900f, 1900f)
    private val fireShader = radial(150f, 0x99FFC06A.toInt(), 0x00FFB25B, .4f, 0x40FFB25B)
    private class FlameShape(val path: Path, val ox: Float, val oy: Float, val dur: Float, val off: Float, val color: Int)
    private val flames = listOf(
        Triple("M0,-118 C34,-72 46,-46 30,-6 C22,14 -22,14 -30,-6 C-44,-40 -18,-62 0,-118Z", floatArrayOf(1.3f, 0f), 0xFFE8762A.toInt()),
        Triple("M2,-74 C22,-46 26,-30 16,-8 C10,4 -12,4 -16,-8 C-24,-30 -8,-44 2,-74Z", floatArrayOf(1.05f, .4f), 0xFFF7B24A.toInt()),
        Triple("M2,-40 C12,-26 12,-16 7,-6 C4,0 -6,0 -8,-6 C-11,-16 -4,-24 2,-40Z", floatArrayOf(.8f, .2f), 0xFFFFF0B0.toInt()),
    ).map { (d, tm, col) ->
        val p = SvgPath.parse(d); val b = RectF(); p.computeBounds(b, true)
        FlameShape(p, (b.left + b.right) / 2, b.bottom, tm[0], tm[1], col)
    }
    private val needleNS = SvgPath.parse("M0,-96 L14,0 L0,96 L-14,0Z")
    private val needleEW = SvgPath.parse("M-96,0 L0,-14 L96,0 L0,14Z")
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
    private val lakeMist = if (L) floatArrayOf(820f, 90f) else floatArrayOf(1820f, 140f)
    private val lakeMistShader = LinearGradient(0f, lakeMist[0], 0f, lakeMist[0] + lakeMist[1], intArrayOf(0x00B9C2EE, 0x38B9C2EE, 0x00B9C2EE), null, Shader.TileMode.CLAMP)
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

    // --- Lothlórien
    private val lorienLamps = if (L) listOf(floatArrayOf(0f, 515f), floatArrayOf(250f, 515f), floatArrayOf(1440f, 455f), floatArrayOf(1730f, 455f), floatArrayOf(1700f, 695f), floatArrayOf(2150f, 555f), floatArrayOf(860f, 640f), floatArrayOf(1000f, 700f), floatArrayOf(1180f, 620f))
        else listOf(floatArrayOf(-30f, 1235f), floatArrayOf(230f, 1235f), floatArrayOf(840f, 1315f), floatArrayOf(1130f, 1315f), floatArrayOf(200f, 1655f), floatArrayOf(870f, 1715f), floatArrayOf(420f, 1500f), floatArrayOf(560f, 1440f), floatArrayOf(700f, 1560f))
    private val goldLampShader = radial(if (L) 24f else 32f, 0x99FFE7A8.toInt(), 0x00FFE7A8)
    private val leafColors = when (SceneArt.season()) { "spring" -> intArrayOf(0xFFE8C45A.toInt(), 0xFFF2D98A.toInt(), 0xFFC9A23A.toInt()); "summer" -> intArrayOf(0xFFB9C46A.toInt(), 0xFFD9D08A.toInt(), 0xFF8FA64E.toInt()); else -> intArrayOf(0xFFE9B23A.toInt(), 0xFFF6D06A.toInt(), 0xFFC98A2A.toInt()) }
    private val leaves = Random(86).let { r -> List(16) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), 9 + r.nextFloat() * 6, r.nextFloat() * 12, r.nextFloat() * 6.28f) } }

    // --- The Grey Havens
    private val havenMoon = if (L) floatArrayOf(1480f, 210f, 46f) else floatArrayOf(760f, 1060f, 58f)
    private val havenSea = if (L) 760f else 1640f
    private val havenMoonShader = radial(havenMoon[2] * 3.2f, 0x80F2F4FA.toInt(), 0x00F2F4FA)
    private val ship = if (L) floatArrayOf(1180f, 860f, 1f) else floatArrayOf(560f, 1830f, 1.35f)
    private val beacon = if (L) floatArrayOf(2010f, 650f - 197f * .9f, 60f * .9f) else floatArrayOf(980f, 1440f - 197f * 1.3f, 60f * 1.3f)
    private val beaconShader = radial(1f, 0xB3FFF2D0.toInt(), 0x00FFF2D0)
    private val hull = SvgPath.parse("M-120,0 C-90,26 80,26 120,0 C124,-10 132,-30 150,-44 C140,-28 136,-14 134,0 Z M150,-44 C158,-50 166,-46 164,-38 C160,-40 156,-40 150,-44Z")
    private val keel = SvgPath.parse("M-120,0 C-90,26 80,26 120,0 L118,8 C80,32 -90,32 -118,8Z")
    private val sailA = SvgPath.parse("M-2,-186 C60,-150 74,-80 64,-18 L-2,-18Z")
    private val sailB = SvgPath.parse("M-10,-170 C-60,-130 -70,-70 -62,-20 L-10,-20Z")
    private val sailShader = LinearGradient(-70f, 0f, 70f, 0f, 0xFFF6F6F2.toInt(), 0xFFC9CED8.toInt(), Shader.TileMode.CLAMP)
    private val seaTwinkles = Random(1956).let { r -> List(36) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), r.nextFloat() * havenSea * .75f, 1.3f + r.nextFloat() * 1.1f, 2f + r.nextFloat() * 3.5f, r.nextFloat() * 5f) } }

    // --- the sky's moods: a rainbow after rain, beacons for severe weather, lightning, frost, heat, smoke
    private val outdoors = look != "redbook"
    private val rainbowOn = sky?.rainbow == true && (look == "shire" || look == "lorien")
    private val rainbowAt = if (L) floatArrayOf(1200f, 1000f, 800f) else floatArrayOf(540f, 1720f, 720f)
    private val rainbowColors = intArrayOf(0xFFE8574A.toInt(), 0xFFF29A3A.toInt(), 0xFFF6D35A.toInt(), 0xFF7CC26A.toInt(), 0xFF5AA8D8.toInt(), 0xFF6A6CC8.toInt(), 0xFF9A6AC0.toInt())
    private val beaconSpots: List<FloatArray> = if (sky?.beacons != true) emptyList() else when (look) {
        "shire" -> if (L) listOf(floatArrayOf(-220f, 488f), floatArrayOf(380f, 480f), floatArrayOf(1020f, 492f), floatArrayOf(1660f, 484f), floatArrayOf(2240f, 490f)) else listOf(floatArrayOf(110f, 1168f), floatArrayOf(420f, 1160f), floatArrayOf(760f, 1172f), floatArrayOf(1010f, 1164f))
        "starlit" -> if (L) listOf(floatArrayOf(640f, 540f), floatArrayOf(1180f, 498f), floatArrayOf(1500f, 610f), floatArrayOf(-300f, 570f)) else listOf(floatArrayOf(260f, 1430f), floatArrayOf(620f, 1350f), floatArrayOf(900f, 1520f))
        "elven" -> if (L) listOf(floatArrayOf(300f, 530f), floatArrayOf(1700f, 520f), floatArrayOf(-200f, 560f)) else listOf(floatArrayOf(300f, 1124f), floatArrayOf(860f, 1150f))
        "havens" -> if (L) listOf(floatArrayOf(300f, 690f), floatArrayOf(1700f, 700f), floatArrayOf(900f, 730f)) else listOf(floatArrayOf(300f, 1520f), floatArrayOf(820f, 1566f))
        else -> emptyList()
    }
    private val beaconGlow = radial(1f, 0xCCFF9A3A.toInt(), 0x00FF7A2A)
    private val bolt = Path()
    private val stormOn = sky?.storm == true && outdoors
    private val frostOn = sky?.frost == true && outdoors
    private val heatOn = sky?.heat == true && outdoors
    private val smoke = sky?.smoke ?: 0f
    private val horizonY = if (L) 560f else when (look) { "shire" -> 1240f; "starlit" -> 1700f; "havens" -> 1640f; "elven" -> 1300f; else -> 1500f }
    private val frostSparks = Random(91).let { r -> List(if (L) 70 else 50) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), horizonY + r.nextFloat() * (hgt - horizonY), r.nextFloat() * 4f, 1f + r.nextFloat() * 1.6f) } }
    private val windowDrops = Random(92).let { r -> List(12) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), 6f + r.nextFloat() * 8f, r.nextFloat() * 14f, 4f + r.nextFloat() * 5f) } }

    /** Behind the land: the rainbow, and the bolt of a storm far off. */
    private fun skyBehind(c: Canvas, t: Float) {
        if (rainbowOn) {
            val (cx, cy, r) = Triple(rainbowAt[0], rainbowAt[1], rainbowAt[2])
            val band = if (L) 12f else 16f
            line.shader = null; line.strokeWidth = band
            for ((i, col) in rainbowColors.withIndex()) {
                line.color = col; line.alpha = 54
                c.drawCircle(cx, cy, r - i * band, line)
            }
        }
        if (stormOn && !still) {
            val n = floor(t / 9f).toInt(); val p = (t % 9f) / 9f
            if (p < .03f) {   // a bolt for a quarter of a second, somewhere new each time
                val bx = x0 + (x1 - x0) * (.15f + .7f * ((n * 0.618f) % 1f))
                val top = if (L) 80f else 900f
                bolt.reset(); bolt.moveTo(bx, top)
                var x = bx; var y = top; val r = Random(n)
                while (y < horizonY) { x += (r.nextFloat() - .5f) * 60f; y += 40f + r.nextFloat() * 40f; bolt.lineTo(x, y) }
                line.color = 0xFFF4F2FF.toInt(); line.alpha = (255 * (1 - p / .03f)).toInt(); line.strokeWidth = if (L) 3f else 4f
                line.style = Paint.Style.STROKE; c.drawPath(bolt, line)
            }
        }
    }

    /** On the far hills: beacons lit for severe weather; a shimmer over the land on a hot afternoon. */
    private fun skyBetween(c: Canvas, t: Float) {
        for ((i, b) in beaconSpots.withIndex()) {
            val k = if (still) .6f else .55f + .45f * abs(sin(t * (5f + i) + i)).toFloat()
            glowAt(c, b[0], b[1] - 8, (if (L) 34f else 46f) * (.85f + .25f * k), .6f + .4f * k, beaconGlow, 1f)
            fill.color = 0xFFFFD27A.toInt(); fill.alpha = 255
            val fh = (if (L) 14f else 19f) * (.8f + .3f * k); val fw = if (L) 5f else 7f
            val fl = Path().apply { moveTo(b[0] - fw, b[1]); quadTo(b[0] - fw * .4f, b[1] - fh * .5f, b[0], b[1] - fh); quadTo(b[0] + fw * .4f, b[1] - fh * .5f, b[0] + fw, b[1]); close() }
            c.drawPath(fl, fill)
        }
        if (heatOn && !still) for (k in 0 until 4) {
            val y = horizonY - 20 + k * 22f
            val dx = 30f * sin(t * .9f + k * 1.7f)
            line.color = 0xFFFFF6E0.toInt(); line.alpha = 22; line.strokeWidth = if (L) 6f else 8f
            c.drawLine(x0 - 60 + dx, y + 4 * sin(t * 2f + k), x1 + 60 + dx, y - 4 * sin(t * 2f + k), line)
        }
    }

    /** Over everything: the storm's flash, frost, a haze of smoke, rain on the window by the Red Book. */
    private fun skyOver(c: Canvas, t: Float) {
        if (stormOn && !still) {
            val p = (t % 9f) / 9f
            val f = when { p < .012f -> .30f; p in .02f..0.03f -> .18f; else -> 0f }
            if (f > 0) { fill.color = 0xFFF4F2FF.toInt(); fill.alpha = (255 * f).toInt(); c.drawRect(x0 - 50, -50f, x1 + 50, hgt + 50, fill) }
        }
        if (frostOn) {
            glow.shader = LinearGradient(0f, horizonY, 0f, hgt, 0x00E8F2FF, 0x4DE8F2FF, Shader.TileMode.CLAMP); glow.alpha = 255
            c.drawRect(x0 - 50, horizonY, x1 + 50, hgt + 50, glow)
            for (sp in frostSparks) {
                val k = if (still) .6f else breathe(t, 3f + sp[2], sp[2])
                fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (200 * k).toInt()
                c.drawCircle(sp[0], sp[1], sp[3] * (if (L) 1f else 1.3f), fill)
            }
        }
        if (smoke > 0f) {
            val warm = if (look == "shire" || look == "redbook" || look == "lorien") 0xC0905A else 0x6A5040
            val a = (smoke * 110).toInt().coerceIn(0, 110)
            glow.shader = LinearGradient(0f, 0f, 0f, hgt, intArrayOf(((a / 3) shl 24) or warm, (a shl 24) or warm, ((a * 2 / 3) shl 24) or warm), floatArrayOf(0f, horizonY / hgt, 1f), Shader.TileMode.CLAMP)
            glow.alpha = 255
            c.drawRect(x0 - 50, -50f, x1 + 50, hgt + 50, glow)
        }
        if (look == "redbook" && (weather == 3 || weather == 5)) {   // rain on the window: cool light and the shadows of drops sliding down the page
            fill.color = 0xFF8AA0C0.toInt(); fill.alpha = 20; c.drawRect(x0 - 50, -50f, x1 + 50, hgt + 50, fill)
            if (!still) for (d in windowDrops) {
                val p = phase(t, d[1] * 4f, d[2]); val y = -40 + (hgt + 80) * (p * p * (3 - 2 * p))   // slow, then quicker, as drops do
                fill.color = 0xFF3A2614.toInt(); fill.alpha = 26
                c.drawOval(d[0] - d[3], y - d[3] * 1.3f, d[0] + d[3], y + d[3] * 1.3f, fill)
                line.color = 0xFF3A2614.toInt(); line.alpha = 12; line.strokeWidth = d[3] * .7f
                c.drawLine(d[0], y - d[3] * 9, d[0], y - d[3], line)
            }
        }
    }

    // --- the real sky over you (night looks): the bright stars and the planets where they really are
    private val nightSky = look == "starlit" || look == "elven" || look == "havens"
    private val skyTop = if (L) (if (look == "starlit") 430f else if (look == "havens") 680f else 470f) else (if (look == "starlit") 1300f else if (look == "havens") 1560f else 1180f)
    private val skyHorizon = if (L) (if (look == "havens") 760f else 640f) else (if (look == "havens") 1640f else 1500f)
    private var realAt = 0L
    private class SkyDot(val x: Float, val y: Float, val r: Float, val color: Int, val tw: Float)   // tw < 0: a planet, steady
    private val realStars = ArrayList<SkyDot>()
    private var realMoon: Heavens.Moon? = null
    private fun updateRealSky() {
        val now = System.currentTimeMillis()
        if (now - realAt < 60_000L) return
        realAt = now
        val (lat, lon) = Sun.where()
        realMoon = Heavens.moon(lat, lon, now)
        realStars.clear()
        if (!nightSky) return
        val az0 = if (lat < 0) 180.0 else 0.0                 // face the pole you can see: the Cross in the south, the Plough in the north
        val pxH = if (L) 16f else 13.5f; val pxV = if (L) 9f else 13.5f
        val cx = (x0 + x1) / 2
        fun place(alt: Double, az: Double): Pair<Float, Float>? {
            val da = (((az - az0) % 360 + 540) % 360 - 180).toFloat()
            val x = cx + da * pxH; val y = skyHorizon - alt.toFloat() * pxV
            return if (alt > 2 && x in x0..x1 && y < skyTop && y > 0) x to y else null
        }
        for ((st, p) in Heavens.stars(lat, lon, now)) place(p.alt, p.az)?.let { (x, y) ->
            realStars.add(SkyDot(x, y, ((2.7 - .5 * st.mag).toFloat()).coerceIn(.9f, 3.4f) * (if (L) 1f else 1.25f), st.color, (st.ra * 7 % 5).toFloat()))
        }
        for (pl in Heavens.planets(lat, lon, now)) place(pl.pos.alt, pl.pos.az)?.let { (x, y) ->
            realStars.add(SkyDot(x, y, pl.size * (if (L) 1f else 1.25f), pl.color, -1f))
        }
    }
    private fun drawRealSky(c: Canvas, t: Float) {
        updateRealSky()
        for (st in realStars) {
            val planet = st.tw < 0
            val k = if (still || planet) 1f else .75f + .25f * breathe(t, 3f + st.tw, st.tw)
            fill.color = st.color
            if (planet) { fill.alpha = 60; c.drawCircle(st.x, st.y, st.r * 2.4f, fill) }
            fill.alpha = (255 * k).toInt()
            c.drawCircle(st.x, st.y, st.r, fill)
        }
    }

    // --- weather
    private val drops = Random(77).let { r -> List(if (L) 90 else 70) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0 + 200), .8f + r.nextFloat() * .5f, r.nextFloat() * 2, 30 + r.nextFloat() * 30) } }
    private val flakes = Random(78).let { r -> List(if (L) 80 else 70) { floatArrayOf(x0 + r.nextFloat() * (x1 - x0), 10 + r.nextFloat() * 9, r.nextFloat() * 20, 1.6f + r.nextFloat() * 2.2f) } }
    private val fogShader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x00E6EEF0, 0x30E6EEF0, 0x00E6EEF0), null, Shader.TileMode.CLAMP)

    /** Behind the middle layer. */
    fun under(c: Canvas, t: Float) {
        skyBehind(c, t)
        when (look) {
            "shire" -> {
                glowAt(c, sun[0], sun[1], 1f + .08f * (if (still) 0f else breathe(t, 6f)), .8f + .2f * breathe(t, 6f), sunShader, 300f)
                if (weather != 5) for (cl in clouds) cloud(c, cl, t)
            }
            "starlit" -> {
                drawRealSky(c, t)
                for (s in twinkles) {
                    val k = if (still) .7f else breathe(t, s[3], s[4])
                    fill.color = 0xFFFFF8E6.toInt(); fill.alpha = (255 * (.15f + .85f * k)).toInt()
                    c.drawCircle(s[0], s[1], s[2] * (.6f + .65f * k), fill)
                }
                // tonight's moon where it really is: low after it rises, high near midnight, gone when it's set
                val alt = moonAltitude()
                if (alt > 0f) {
                    val my = moon[1] + (1f - alt) * (if (L) 330f else 640f)
                    val sc = moonScale()
                    val mr = moon[2] * sc
                    glowAt(c, moon[0], my, sc * (1f + .06f * breathe(t, 7f)), (.75f + .25f * breathe(t, 7f)) * (1f - .7f * (realMoon?.eclipse ?: 0.0).toFloat()), moonShader, moon[2] * 3.2f)
                    drawMoon(c, moon[0], my, mr)
                }
                shootingStar(c, t)
                for ((i, g) in listOf(floatArrayOf(-80f, 0f, 160f), floatArrayOf(-60f, 28f, 120f), floatArrayOf(-44f, 58f, 88f), floatArrayOf(-30f, 94f, 60f), floatArrayOf(-20f, 136f, 40f), floatArrayOf(-12f, 186f, 24f)).withIndex())
                    glint(c, lakeGlint[0] + g[0], lakeGlint[1] + g[1], g[2], t, 3f + i * .5f, i * .6f, if (L) 4f else 5f, 0xFFFFF3D6.toInt())
            }
            "havens" -> {
                drawRealSky(c, t)
                for (st in seaTwinkles) {
                    val k = if (still) .7f else breathe(t, st[3], st[4])
                    fill.color = 0xFFF2F2EE.toInt(); fill.alpha = (255 * (.15f + .85f * k)).toInt()
                    c.drawCircle(st[0], st[1], st[2] * (.6f + .65f * k), fill)
                }
                val alt = moonAltitude()
                if (alt > 0f) {
                    val my = havenMoon[1] + (1f - alt) * (if (L) 330f else 520f)
                    glowAt(c, havenMoon[0], my, 1f + .06f * breathe(t, 7f), .75f + .25f * breathe(t, 7f), havenMoonShader, havenMoon[2] * 3.2f)
                    drawMoon(c, havenMoon[0], my, havenMoon[2])
                }
                shootingStar(c, t)
                for ((i, g) in listOf(floatArrayOf(-70f, 14f, 140f), floatArrayOf(-50f, 44f, 100f), floatArrayOf(-36f, 80f, 72f), floatArrayOf(-24f, 124f, 48f), floatArrayOf(-14f, 176f, 30f)).withIndex())
                    glint(c, havenMoon[0] + g[0], havenSea + g[1] * (if (L) 1f else 1.4f), g[2], t, 3.2f + i * .5f, i * .7f, if (L) 4f else 5f, 0xFFF2F4FA.toInt())
            }
            "elven" -> {
                drawRealSky(c, t)
                glowAt(c, star[0], star[1], star[2] * (1f + .08f * breathe(t, 6f)), .8f + .2f * breathe(t, 6f), starShader, 90f)
                val dx = if (still) 0f else (-120 * cos(2 * PI * phase(t, 80f, 0f))).toFloat()
                glow.shader = mistShader; glow.alpha = 255
                c.drawRect(x0 - 200 + dx, mistY[0], x1 + 200 + dx, mistY[0] + mistY[1], glow)
            }
        }
    }

    /** Between the middle layer and the front. */
    fun moving(c: Canvas, t: Float) {
        skyBetween(c, t)
        when (look) {
            "shire" -> for ((i, g) in riverGlints.withIndex()) glint(c, g[0], g[1], g[2], t, 4f + i % 3, i * 1.3f, if (L) 3f else 4f, 0xFFFFFFFF.toInt())
            "lorien" -> {
                for ((i, l) in lorienLamps.withIndex()) {
                    val k = if (still) .5f else breathe(t, 3f + i % 3, i * .9f)
                    val lit = Hearth.lampLit(i, lorienLamps.size); if (lit <= 0f) continue
                    val fl = Hearth.flame(t, i)
                    glowAt(c, l[0], l[1], (.85f + .3f * k) * fl, (.6f + .4f * k) * lit * fl.coerceAtMost(1f), goldLampShader, if (L) 24f else 32f)
                    fill.color = 0xFFFFF0C4.toInt(); fill.alpha = (255 * lit).toInt()
                    c.drawCircle(l[0], l[1], if (L) 4.5f else 6f, fill)
                }
                if (!still) for ((i, lf) in leaves.withIndex()) {   // a leaf drifting down, turning as it falls
                    val p = phase(t, lf[1], lf[2])
                    val x = lf[0] + 60 * sin(p * 2 * PI * 1.5 + lf[3]).toFloat(); val y = -20 + (hgt + 40) * p
                    c.save(); c.translate(x, y); c.rotate(p * 540f + lf[3] * 57f)
                    fill.color = leafColors[i % 3]; fill.alpha = (230 * (if (p > .85f) (1 - p) / .15f else 1f)).toInt()
                    c.drawOval(-7f, -3.4f, 7f, 3.4f, fill)
                    c.restore()
                }
            }
            "havens" -> {
                val k = if (still) .5f else breathe(t, 4.5f)
                val fl = Hearth.flame(t, 5)
                glowAt(c, beacon[0], beacon[1], beacon[2] * (.9f + .2f * k) * fl, (.7f + .3f * k) * fl.coerceAtMost(1f), beaconShader, 1f)
                // the white ship, rising and falling on the swell
                val rock = if (still) 0f else (1.2 * sin(2 * PI * phase(t, 8f, 0f))).toFloat()
                val bob = if (still) 0f else (3 * sin(2 * PI * phase(t, 8f, 2f))).toFloat()
                c.save(); c.translate(ship[0], ship[1] + bob * ship[2]); c.rotate(rock); c.scale(ship[2], ship[2])
                fill.shader = null; fill.color = 0xFFB9C0CE.toInt(); fill.alpha = 255; c.drawPath(keel, fill)
                fill.color = 0xFFF2F2EE.toInt(); c.drawPath(hull, fill)
                line.color = 0xFFD8DCE4.toInt(); line.alpha = 255; line.strokeWidth = 5f; c.drawLine(-6f, -6f, -6f, -190f, line)
                fill.shader = sailShader; c.drawPath(sailA, fill); fill.alpha = 230; c.drawPath(sailB, fill); fill.shader = null
                fill.color = 0xFFFFF2D0.toInt(); fill.alpha = 255; c.drawCircle(-112f, -8f, 5f, fill)
                c.restore()
            }
            "starlit" -> {   // mist drifting on the water
                val dx = if (still) 0f else (-120 * cos(2 * PI * phase(t, 80f, 20f))).toFloat()
                glow.shader = lakeMistShader; glow.alpha = 255
                c.drawRect(x0 - 200 + dx, lakeMist[0], x1 + 200 + dx, lakeMist[0] + lakeMist[1], glow)
            }
            "redbook" -> {
                val fx = fire[0]; val fy = fire[1]; val fs = fire[2]
                val fl = Hearth.flame(t)
                val fk = sqrt(fl)   // the flame shrinks (gently) as the candle burns down
                glowAt(c, fx, fy - 34 * fs * fk, fs * fk * (1f + .04f * breathe(t, 2.2f)), (.7f + .3f * breathe(t, 2.2f)) * fl.coerceAtMost(1.1f), fireShader, 150f)
                c.save(); c.translate(fx, fy - 4 * fs); c.scale(fs * .52f * (.6f + .4f * fk), fs * .52f * fk)
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
            "shire" -> {
              drawGarden(c, t)
              // the door lantern: a soft glow by day, warmer as evening comes, guttering when the oil's low
              val fl = Hearth.flame(t)
              val ls = lantern[2]
              glowAt(c, lantern[0], lantern[1], 34f * ls * (.75f + .25f * fl) * (if (still) 1f else 1f + .04f * breathe(t, 2.6f)), (.55f + .45f * fl.coerceAtMost(1.2f)) * .9f, lanternShader, 1f)
              for (k in 0 until 4) {
                val cs = chimney[2]
                if (still) {
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * (.55f - k * .11f)).toInt()
                    c.drawCircle(chimney[0] + k * 14 * cs, chimney[1] - k * 40 * cs, (9 + k * 5) * cs, fill)
                } else {
                    val p = phase(t, 5f, k * 1.25f); val e = easeOut(p)
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (153 * (if (p < .15f) p / .15f else 1 - (p - .15f) / .85f)).toInt()
                    val lean = if (wind) 170f else 50f       // and the smoke leans
                    fill.alpha = (fill.alpha * (.35f + .65f * fl.coerceAtMost(1f))).toInt()   // a low fire, thin smoke
                    c.drawCircle(chimney[0] + lean * cs * e, chimney[1] - (if (wind) 120 else 200) * cs * e, 12 * cs * (.6f + 1.3f * e), fill)
                }
              }
            }
            "starlit" -> {
                val p = phase(t, 3.2f, 0f)
                val o = if (still) 1f else if (p > .31f && p < .33f) .82f else if (p > .64f && p < .67f) .88f else 1f
                val ks = cabin[2]
                val lit = (Hearth.flame(t, 3).coerceAtMost(1.15f) * Hearth.lampLit(0, 1)).coerceIn(0f, 1f)
                fill.color = 0xFFFFC867.toInt(); fill.alpha = (255 * o * lit).toInt()
                c.drawRect(cabin[0] - 14 * ks, cabin[1] - 3 * ks, cabin[0], cabin[1] + 11 * ks, fill)
                c.drawRect(cabin[0] + 6 * ks, cabin[1] - 3 * ks, cabin[0] + 20 * ks, cabin[1] + 11 * ks, fill)
            }
            "elven" -> {
                for ((i, l) in lamps.withIndex()) {
                    val k = if (still) .5f else breathe(t, 3f + i % 3, i * .9f)
                    val lit = Hearth.lampLit(i, lamps.size); if (lit <= 0f) continue      // the night watch puts them out one by one
                    val fl = Hearth.flame(t, i)
                    glowAt(c, l[0], l[1], (.85f + .3f * k) * fl, (.6f + .4f * k) * lit * fl.coerceAtMost(1f), lampShader, if (L) 22f else 28f)
                    fill.color = 0xFFE5DCC2.toInt(); fill.alpha = (255 * lit).toInt()
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
        skyOver(c, t)
    }

    /** Sam's garden: cabbages and flowers in a tidy bed when it's flourishing; thistles and dock when it isn't. */
    private fun drawGarden(c: Canvas, t: Float) {
        if (plants.isEmpty()) return
        val sc = if (L) 1f else 1.45f
        val bx = if (L) 1084f else 422f; val by = if (L) 962f else 1984f; val bw = if (L) 132f else 166f; val bh = if (L) 40f else 70f
        fill.shader = null
        fill.color = 0xFF7A5A3A.toInt(); fill.alpha = 200
        c.drawOval(bx, by, bx + bw, by + bh, fill)
        fill.color = 0xFF5E4430.toInt(); fill.alpha = 150
        c.drawOval(bx + 6 * sc, by + bh * .45f, bx + bw - 6 * sc, by + bh * .95f, fill)
        for (p in plants) {
            val sway = if (still) 0f else 1.6f * sin(t * .9f + p.sway)
            when (p.kind) {
                0 -> {   // a cabbage
                    fill.color = 0xFF6E9A4E.toInt(); fill.alpha = 255; c.drawCircle(p.x, p.y, 6.5f * sc, fill)
                    fill.color = 0xFF9CC27A.toInt(); c.drawCircle(p.x - 1.5f * sc, p.y - 1.5f * sc, 3.5f * sc, fill)
                }
                1, 2 -> {   // a flower on its stem (a bud on a fair day)
                    val tx = p.x + sway; val ty = p.y - p.h
                    line.color = 0xFF4E7A3A.toInt(); line.alpha = 255; line.strokeWidth = 1.6f * sc; line.shader = null
                    c.drawLine(p.x, p.y, tx, ty, line)
                    fill.color = 0xFF5E8C46.toInt(); fill.alpha = 255; c.drawOval(p.x - 4 * sc, p.y - p.h * .45f - 1.5f * sc, p.x, p.y - p.h * .45f + 1.5f * sc, fill)
                    fill.color = bloomTones[p.tone]
                    if (p.kind == 1) { for (k in 0 until 5) { val a = k * 1.2566f; c.drawCircle(tx + cos(a) * 2.6f * sc, ty + sin(a) * 2.6f * sc, 2.2f * sc, fill) }
                        fill.color = 0xFFFFE9A0.toInt(); c.drawCircle(tx, ty, 1.5f * sc, fill) }
                    else c.drawOval(tx - 1.8f * sc, ty - 3f * sc, tx + 1.8f * sc, ty + 1f * sc, fill)
                }
                3 -> {   // a thistle: spiky, with a purple head
                    val tx = p.x + sway * 1.4f; val ty = p.y - p.h
                    line.color = 0xFF6F7E4A.toInt(); line.alpha = 255; line.strokeWidth = 1.8f * sc; line.shader = null
                    c.drawLine(p.x, p.y, tx, ty, line)
                    for (k in 1..3) { val yy = p.y - p.h * k / 4f; c.drawLine(p.x + (tx - p.x) * k / 4f, yy, p.x + (tx - p.x) * k / 4f + (if (k % 2 == 0) 5f else -5f) * sc, yy - 3f * sc, line) }
                    fill.color = 0xFF8E6A9A.toInt(); fill.alpha = 255; c.drawCircle(tx, ty - 1.5f * sc, 3f * sc, fill)
                }
                else -> {   // dock and long grass, untidy
                    line.color = 0xFF7F8C4C.toInt(); line.alpha = 230; line.strokeWidth = 2.2f * sc; line.shader = null
                    for (k in -1..1) c.drawLine(p.x + k * 3f * sc, p.y, p.x + k * 6f * sc + sway * 1.6f, p.y - p.h * (1f - .15f * k * k), line)
                }
            }
        }
    }

    private fun weather(c: Canvas, t: Float) {
        if (look == "redbook" || weather == 0 || still) return
        if (weather == 1 || weather == 5) {
            val col = if (look == "shire") 0xFFF2F4F4.toInt() else 0xFF3A4266.toInt()
            val o = if (look == "shire") .85f else .55f
            val list = if (L) listOf(Cloud(200f, 90f, 1.6f, o, 300f), Cloud(1100f, 160f, 1.3f, o, 360f), Cloud(1900f, 70f, 1.8f, o, 420f))
                else listOf(Cloud(160f, 1130f, 1.6f, o, 260f), Cloud(800f, 1240f, 1.3f, o, 320f))
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

    private fun moonPhase(): Double { updateRealSky(); return realMoon?.phase ?: 0.0 }

    /**
     * How high the moon is, 0 (on the horizon) to 1, or below 0 when it has set. The moon crosses the
     * sky about 50 minutes later each day: at new moon with the sun (midday), at full moon at midnight.
     */
    /** 0 on the horizon .. 1 high (60° and up), below 0 once it has set: from its real altitude. */
    private fun moonAltitude(): Float { updateRealSky(); return ((realMoon?.pos?.alt ?: 30.0) / 60.0).toFloat().coerceAtMost(1f) }
    /** A supermoon a little larger; a full moon a touch larger anyway. */
    private fun moonScale(): Float { val m = realMoon ?: return 1f; return if (m.supermoon) 1.32f else if (m.full) 1.18f else 1f }

    /** Now and then a shooting star; every few seconds on meteor-shower nights (mid-August, mid-December). */
    private val meteorNight = java.time.LocalDate.now().let { (it.monthValue == 8 && it.dayOfMonth in 11..13) || (it.monthValue == 12 && it.dayOfMonth in 13..15) }
    private fun shootingStar(c: Canvas, t: Float) {
        if (still) return
        val dur = if (meteorNight) 6f else 23f
        val p = phase(t, dur, 3f) / (2.2f / dur)        // 0..1 over 2.2 s, then waits
        if (p >= 1f) return
        val n = floor(t / dur).toInt()
        val sx = x0 + (x1 - x0) * (0.25f + 0.5f * ((n * 0.618f) % 1f))
        val sy = (if (L) 80f else 260f) + 120f * ((n * 0.382f) % 1f)
        val len = if (L) 160f else 220f
        val hx = sx + len * p; val hy = sy + len * .35f * p
        meteorSeen = true
        line.color = 0xFFFFFFFF.toInt(); line.strokeWidth = if (L) 2.4f else 3.2f
        line.alpha = (255 * sin(PI * p)).toInt()
        line.shader = LinearGradient(hx - 90, hy - 30, hx, hy, 0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawLine(hx - 90, hy - 30, hx, hy, line)
        line.shader = null
    }

    /** Tonight's real moon phase, lit on the correct side for your hemisphere. */
    private fun drawMoon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = moonPhase()
        fill.color = 0xFF2A3058.toInt(); fill.alpha = 255
        c.drawCircle(cx, cy, r, fill)
        val ecl = (realMoon?.eclipse ?: 0.0).toFloat()
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
        if (ecl > 0f) { fill.color = 0xFF9A3A22.toInt(); fill.alpha = (235 * ecl).toInt(); c.drawCircle(cx, cy, r, fill); fill.alpha = 255 }   // in Earth's shadow: the blood-red moon
    }

    /** Set when a falling star has been drawn (for the Deeds). */
    var meteorSeen = false
    /** The rare things this scene is showing, for the Deeds of the Red Book. */
    val deeds: List<String> get() = buildList {
        if (rainbowOn) add("rainbow")
        if (frostOn) add("frost")
        if (beaconSpots.isNotEmpty()) add("beacons")
        if (stormOn) add("storm")
        updateRealSky()
        realMoon?.let { m -> if (m.pos.alt > 0 && (look == "starlit" || look == "havens" || look == "elven")) { if (m.eclipse > .3) add("eclipse"); if (m.supermoon) add("supermoon") } }
    }

    /** How often this scene needs a new frame: gentle scenes less often, saving battery. */
    val fps: Int = when {
        weather == 3 || weather == 5 || weather == 4 -> 30
        beaconSpots.isNotEmpty() || heatOn -> 24
        look == "shire" -> 30      // slow drifting clouds and smoke need more frames to look smooth
        look == "redbook" -> 24
        look == "elven" -> 20
        look == "lorien" -> 30     // falling leaves
        look == "havens" -> 24
        else -> 20
    }
}

/** Draws one scene (still layers plus the moving parts) at a given size. */
class SceneDrawer(ctx: Context, val look: Look, val w: Int, val h: Int, dp: Float, val weather: Int, still: Boolean = false, val parallax: Boolean = false, seasonOverride: String? = null) {
    val land = w > h
    val season = seasonOverride ?: SceneArt.season()
    private val design = SceneArt.Design(land)
    // Rivendell on a wide upright screen (a tablet): crop evenly, so the trees stay below the clock
    private val fit = SceneArt.Fit(design, w.toFloat(), h.toFloat(), if (parallax) 1.04f else 1f,
        anchor = if (look.id == "elven" && !land && h.toFloat() / w < 2f) .5f else 1f)
    val extras = SceneExtras(ctx.applicationContext, look.id, w, h, dp, still)
    private val app = ctx.applicationContext
    private val live = !still
    private val names = SceneArt.LAYERS[look.id] ?: listOf("back")
    private val bitmaps = names.map { SceneArt.layer(ctx, look.id, season, land, it, (design.h * fit.s).toInt()) }
    private val motion = Motion(look.id, land, weather, still, wind = !still && Weather.windy(ctx), sky = Weather.sky(ctx),
        garden = if (look.id == "shire" && !still) Garden.state(ctx) else -1)
    /** The dawn alarm: in the quarter hour before your alarm, Shire morning rises through the night. */
    private val dawn: SceneDrawer? = if (live && seasonOverride == null && look.darkScene && look.id != "shire" && Hearth.dawnSoon())
        try { SceneDrawer(ctx, Look.byId("shire"), w, h, dp, weather, parallax = parallax) } catch (_: Throwable) { null } else null
    private var deedsChecked = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val maxShift = 12 * dp
    /** Frames a second: the scene's own, more for fireworks or a visitor in flight; calmer under the night watch. */
    val fps: Int get() {
        val f = maxOf(motion.fps, extras.fps)
        return if (Hearth.watch() >= .5f && extras.fps == 0) maxOf(12, f * 3 / 5) else f
    }

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
        extras.draw(c)
        dawn?.let { d ->
            val k = Hearth.dawn()
            if (k > 0f) { c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (255 * k * k * (3 - 2 * k)).toInt()); d.draw(c, t, tx, ty); c.restore() }
        }
        if (live) noteDeeds()
    }

    private fun noteDeeds() {
        if (!deedsChecked) {
            deedsChecked = true
            motion.deeds.forEach { Deeds.saw(app, it) }
            if (Feasts.fireworks()) Deeds.saw(app, "fireworks")
            if (Feasts.lanterns()) Deeds.saw(app, "lanterns")
            if (look.id == "redbook" && Feasts.durin()) Deeds.saw(app, "durin")
        }
        if (motion.meteorSeen) { motion.meteorSeen = false; Deeds.saw(app, "meteor") }
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
    fun refresh() { update(); rebuild() }

    /** A birthday in the calendar today: bunting across the sky. */
    var bunting = false
        set(value) { field = value; drawer?.extras?.bunting = value; invalidate() }

    /** Tapped the scene: sparkles, embers or a puff of cloud where you touched. */
    fun poke(x: Float, y: Float) {
        val d = drawer ?: return
        if (!animationsOn) return
        d.extras.poke(x, y)
        ensureTicking()
    }

    // The new scene is drawn off the main thread (no hitch when the look changes), then fades in
    // over the old one for a couple of seconds, like the light changing.
    private var old: SceneDrawer? = null
    private var fadeStart = 0L
    private var fadeMs = 2000L
    private var building = 0
    // the season turning: on its first day the old season gives way to the new over a minute,
    // with the new season blowing in (blossom, leaves, snow, or summer's drifting seeds)
    private var turning: String? = null
    private val seasonPrefs = context.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)

    private fun rebuild() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val sk = Weather.sky(context).let { "${it.rainbow}${it.frost}${it.heat}${(it.smoke * 10).toInt()}${it.beacons}${it.storm}" }
        val feast = "${Feasts.fireworks()}${Feasts.lanterns()}${Feasts.durin()}"
        val garden = if (look.id == "shire") Garden.state(context) else -1
        val key = "${look.id}#$w#$h#${SceneArt.season()}#$weather#$parallax#$sk#$feast#$garden#${Hearth.dawnSoon()}"
        if (key == builtFor && drawer != null) { invalidate(); return }
        builtFor = key
        val token = ++building
        val lk = look; val wx = weather; val px = parallax; val dpx = resources.displayMetrics.density
        val season = SceneArt.season()
        val seen = seasonPrefs.getString("season_seen", null)
        val turn = seen != null && seen != season && animationsOn && !Quiet.now(context)
        if (turn) Deeds.saw(context, "turning")
        seasonPrefs.edit().putString("season_seen", season).apply()
        Thread {
            val d = try { SceneDrawer(context, lk, w, h, dpx, wx, parallax = px) } catch (_: Throwable) { null }
            val was = if (turn) try { SceneDrawer(context, lk, w, h, dpx, wx, parallax = px, seasonOverride = seen) } catch (_: Throwable) { null } else null
            post {
                if (token != building || d == null) return@post
                d.extras.bunting = bunting
                val prev = drawer
                val fade = (prev != null && prev.look.id != d.look.id && animationsOn) || was != null
                old = was ?: if (fade) prev else null
                fadeMs = if (was != null) 60_000L else 2000L
                turning = if (was != null) season else null
                fadeStart = SystemClock.uptimeMillis()
                drawer = d
                if (fade) ensureTicking()
                invalidate()
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1; name = "lembas-scene" }.start()
    }

    /** Keep drawing frames for a fade or a tap even when the scene itself is still. */
    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            val fading = old != null && SystemClock.uptimeMillis() - fadeStart < fadeMs
            if (!running && (fading || drawer?.extras?.busy == true)) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun ensureTicking() { if (!running) Choreographer.getInstance().postFrameCallback(ticker) }

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

    private val turnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val turnBits = Random(2026).let { r -> List(48) { floatArrayOf(r.nextFloat(), r.nextFloat(), .6f + r.nextFloat() * .8f, r.nextFloat() * 6.28f) } }
    /** The new season blowing in across the old, thick in the middle of the minute, thinning at the ends. */
    private fun drawTurning(c: Canvas, season: String, f: Float) {
        val dp = resources.displayMetrics.density
        val amount = sin(PI * f).toFloat()
        val t = (SystemClock.uptimeMillis() - fadeStart) / 1000f
        for ((i, b) in turnBits.withIndex()) {
            val sp = b[2]
            val y = ((b[1] + t * .05f * sp) % 1.1f - .05f) * height
            val x = ((b[0] + t * .02f * sp + .03f * sin(t * .8f + b[3])) % 1f) * width
            turnPaint.alpha = (230 * amount).toInt()
            when (season) {
                "winter" -> { turnPaint.color = 0xFFFFFFFF.toInt(); turnPaint.alpha = (230 * amount).toInt(); c.drawCircle(x, y, (2f + sp * 2f) * dp, turnPaint) }
                "spring" -> { turnPaint.color = if (i % 3 == 0) 0xFFFFFFFF.toInt() else 0xFFF2B8C6.toInt(); turnPaint.alpha = (230 * amount).toInt(); c.save(); c.rotate(t * 60 * sp + b[3] * 57, x, y); c.drawOval(x - 4 * dp, y - 2.4f * dp, x + 4 * dp, y + 2.4f * dp, turnPaint); c.restore() }
                "autumn" -> { turnPaint.color = intArrayOf(0xFFD9843A.toInt(), 0xFFE8A646.toInt(), 0xFFC8562E.toInt())[i % 3]; turnPaint.alpha = (230 * amount).toInt(); c.save(); c.rotate(t * 90 * sp + b[3] * 57, x, y); c.drawOval(x - 6 * dp, y - 2.8f * dp, x + 6 * dp, y + 2.8f * dp, turnPaint); c.restore() }
                else -> { turnPaint.color = 0xFFFFF6D8.toInt(); turnPaint.alpha = (180 * amount).toInt(); c.drawCircle(x, y, 1.6f * dp, turnPaint) }   // summer: thistledown on the air
            }
        }
    }

    private val fadePaint = android.graphics.Paint()
    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val tt = if (running) t else 0f
        val o = old
        val f = (SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeMs
        if (o != null && f < 1f) {
            o.draw(canvas, tt, tiltX, tiltY)
            val k = f * f * (3 - 2 * f)
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * k).toInt())
            drawer?.draw(canvas, tt, tiltX, tiltY)
            canvas.restore()
            turning?.let { drawTurning(canvas, it, f) }
            return
        }
        old = null; turning = null
        drawer?.draw(canvas, tt, tiltX, tiltY) ?: canvas.drawColor(look.windowColor)
    }

    private fun update() {
        val should = animationsOn && active && isAttachedToWindow && power?.isPowerSaveMode != true && !Themes.paused(context)
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

    /** A messenger, an eagle, a refill or a smoke ring: keep frames coming while it plays. */
    private val omen: () -> Unit = { ensureTicking() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Hearth.init(context); Hearth.addListener(omen); update() }
    override fun onDetachedFromWindow() {
        Hearth.removeListener(omen)
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }
}
