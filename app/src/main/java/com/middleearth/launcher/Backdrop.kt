package com.middleearth.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * The home-screen artwork. It is painted ONCE per theme/day/screen size into a bitmap
 * on a background thread; after that, drawing the home screen is a single bitmap blit.
 * No animation loop, so it costs nothing while you look at it.
 */
class BackdropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var renderedKey = ""
    private var token = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var look: Look = Look.STARLIT
        set(value) {
            field = value
            refresh()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refresh()

    /** Re-paint only if something that affects the picture changed (theme, day, size). */
    fun refresh() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val day = LocalDate.now().toEpochDay()
        val key = "${look.id}#$w#$h#$day"
        if (key == renderedKey) return
        renderedKey = key
        val t = ++token
        val lk = look
        val density = resources.displayMetrics.density
        Thread {
            val bmp = try { Painter(density).paint(lk, w, h) } catch (e: Throwable) { null }
            post {
                if (t == token && bmp != null) {
                    bitmap = bmp
                    invalidate()
                }
            }
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-backdrop" }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b != null) canvas.drawBitmap(b, 0f, 0f, paint) else canvas.drawColor(look.windowColor)
    }
}

internal class Painter(private val dp: Float) {

    fun paint(look: Look, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (look.id) {
            "shire" -> shire(c, w.toFloat(), h.toFloat())
            "redbook" -> redBook(c, w.toFloat(), h.toFloat())
            "elven" -> elven(c, w.toFloat(), h.toFloat())
            else -> starlit(c, w.toFloat(), h.toFloat())
        }
        bmp.prepareToDraw()
        return bmp
    }

    // ------------------------------------------------------------ A · Starlit Night

    private fun starlit(c: Canvas, w: Float, h: Float) {
        val sky = Paint().apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF05070F.toInt(), 0xFF0B1226.toInt(), 0xFF1A2444.toInt(), 0xFF2A3354.toInt()),
                floatArrayOf(0f, 0.45f, 0.78f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, sky)

        val rnd = Random(1954)
        stars(c, w, h, rnd, density = 1300f, maxY = 0.72f, brightness = 1f)

        // A handful of brighter stars with a soft halo.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF8E6.toInt() }
        repeat(7) {
            val x = rnd.nextFloat() * w
            val y = h * 0.55f * rnd.nextFloat()
            val r = (6 + rnd.nextFloat() * 5) * dp
            halo.shader = RadialGradient(x, y, r, 0x8CFFF4D8.toInt(), 0x00FFF4D8, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, halo)
            c.drawCircle(x, y, 1.3f * dp, core)
        }

        // The evening star, low in the west, with a four-point flare.
        val ex = w * 0.15f
        val ey = h * 0.28f
        halo.shader = RadialGradient(ex, ey, 20 * dp, 0x99FFF4D8.toInt(), 0x00FFF4D8, Shader.TileMode.CLAMP)
        c.drawCircle(ex, ey, 20 * dp, halo)
        val flare = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * dp }
        flare.shader = LinearGradient(ex - 16 * dp, ey, ex + 16 * dp, ey,
            intArrayOf(0x00FFF4D8, 0xE6FFF4D8.toInt(), 0x00FFF4D8), null, Shader.TileMode.CLAMP)
        c.drawLine(ex - 16 * dp, ey, ex + 16 * dp, ey, flare)
        flare.shader = LinearGradient(ex, ey - 16 * dp, ex, ey + 16 * dp,
            intArrayOf(0x00FFF4D8, 0xE6FFF4D8.toInt(), 0x00FFF4D8), null, Shader.TileMode.CLAMP)
        c.drawLine(ex, ey - 16 * dp, ex, ey + 16 * dp, flare)
        c.drawCircle(ex, ey, 2.2f * dp, core)

        moon(c, w * 0.77f, h * 0.178f, 24 * dp)

        // Mountains: far range, mist, near range, then a dark foot for the dock.
        val far = ridge(w, h, h * 0.80f, h * 0.11f, Random(7))
        c.drawPath(far, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19213D.toInt() })
        val mist = Paint().apply {
            shader = LinearGradient(0f, h * 0.66f, 0f, h * 0.81f, 0x003A4A72, 0x593A4A72, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h * 0.66f, w, h * 0.81f, mist)
        val near = ridge(w, h, h * 0.90f, h * 0.07f, Random(11))
        c.drawPath(near, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C111F.toInt() })
        val foot = Paint().apply {
            shader = LinearGradient(0f, h * 0.78f, 0f, h, 0x0005070F, 0xD905070F.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h * 0.78f, w, h, foot)
    }

    private fun stars(c: Canvas, w: Float, h: Float, rnd: Random, density: Float, maxY: Float, brightness: Float) {
        // density = square dp of sky per star
        val count = (w * h / (dp * dp) / density).toInt().coerceIn(40, 600)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(count) {
            val x = rnd.nextFloat() * w
            val y = h * maxY * rnd.nextFloat().pow(1.3f)
            val r = (0.35f + rnd.nextFloat().pow(3f) * 1.25f) * dp
            val tint = rnd.nextFloat()
            p.color = when {
                tint < 0.12f -> 0xFFFFE2B0.toInt()
                tint < 0.25f -> 0xFFBFD4FF.toInt()
                else -> 0xFFF4F1E8.toInt()
            }
            p.alpha = ((60 + rnd.nextInt(180)) * brightness).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, r, p)
        }
    }

    /** Jagged mountain silhouette, closed down to the bottom of the screen. */
    private fun ridge(w: Float, h: Float, baseY: Float, amp: Float, rnd: Random): Path {
        val path = Path()
        path.moveTo(0f, h)
        var x = 0f
        var y = baseY - amp * 0.2f
        path.lineTo(0f, y)
        var peak = true
        while (x < w) {
            val step = (24 + rnd.nextFloat() * 46) * dp
            val nx = x + step
            val ny = if (peak) baseY - amp * (0.35f + rnd.nextFloat() * 0.65f)
            else baseY - amp * rnd.nextFloat() * 0.3f
            // two small jags on the way, so slopes aren't ruler-straight
            for (i in 1..2) {
                val t = i / 3f
                val jx = x + (nx - x) * t
                val jy = y + (ny - y) * t + (rnd.nextFloat() - 0.5f) * amp * 0.12f
                path.lineTo(jx, jy)
            }
            path.lineTo(nx, ny)
            x = nx
            y = ny
            peak = !peak
        }
        path.lineTo(w, h)
        path.close()
        return path
    }

    /** The real moon phase for today, lit on the correct side for your hemisphere. */
    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float) {
        val synodic = 29.530588853
        val knownNewMoon = 947182440000L // 2000-01-06 18:14 UTC
        var phase = ((System.currentTimeMillis() - knownNewMoon) / 86_400_000.0 / synodic) % 1.0
        if (phase < 0) phase += 1.0

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 3.2f, 0x38CFD8F0, 0x00CFD8F0, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r * 3.2f, glow)
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C2235.toInt() })

        // Lit area = right half-disc plus/minus an ellipse whose width follows the phase.
        val termW = (r * cos(2 * PI * phase)).toFloat()
        val lit = Path()
        lit.moveTo(cx, cy - r)
        lit.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f, false)
        val aw = max(abs(termW), 0.5f)
        val oval = RectF(cx - aw, cy - r, cx + aw, cy + r)
        if (termW >= 0) lit.arcTo(oval, 90f, -180f, false) else lit.arcTo(oval, 90f, 180f, false)
        lit.close()

        val waning = phase > 0.5
        c.save()
        if (waning xor southernHemisphere()) c.scale(-1f, 1f, cx, cy)
        val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEFE8D6.toInt()
            maskFilter = BlurMaskFilter(r * 0.04f + 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(lit, litPaint)
        // a few faint "seas" on the lit part
        c.clipPath(lit)
        val sea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F5A6070 }
        c.drawCircle(cx + r * 0.25f, cy - r * 0.2f, r * 0.28f, sea)
        c.drawCircle(cx - r * 0.1f, cy + r * 0.3f, r * 0.22f, sea)
        c.drawCircle(cx + r * 0.45f, cy + r * 0.35f, r * 0.14f, sea)
        c.restore()
    }

    private fun southernHemisphere(): Boolean {
        val id = TimeZone.getDefault().id
        return id.startsWith("Australia/") || id.startsWith("Antarctica/") ||
            id == "Pacific/Auckland" || id == "Pacific/Chatham" || id == "Pacific/Fiji" ||
            id.startsWith("America/Argentina") || id == "America/Santiago" || id == "America/Sao_Paulo" ||
            id == "America/Montevideo" || id == "America/Asuncion" || id == "America/La_Paz" || id == "America/Lima" ||
            id == "Africa/Johannesburg" || id == "Africa/Maputo" || id == "Africa/Harare" || id == "Africa/Windhoek" ||
            id == "Indian/Mauritius" || id == "Indian/Reunion" || id == "Indian/Antananarivo"
    }


    // ------------------------------------------------------------ Shire Morning

    private fun shire(c: Canvas, w: Float, h: Float) {
        val sky = Paint().apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFFDCEBEA.toInt(), 0xFFEEF2E2.toInt(), 0xFFFBF1DA.toInt(), 0xFFF7E6C4.toInt()),
                floatArrayOf(0f, 0.42f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, sky)

        val (s, ox, oy) = Scene.shire(w, h)
        c.save()
        c.translate(ox, oy)
        c.scale(s, s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sun with soft rays
        p.shader = RadialGradient(318f, 150f, 96f,
            intArrayOf(0xFFFFF6D6.toInt(), 0x8CFFE9A8.toInt(), 0x00FFE9A8), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(318f, 150f, 96f, p)
        p.shader = null
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2EF2C969; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        for (i in 0 until 8) {
            val a = i * PI / 4
            val x1 = 318f + (88 * cos(a)).toFloat(); val y1 = 150f + (88 * kotlin.math.sin(a)).toFloat()
            val x2 = 318f + (112 * cos(a)).toFloat(); val y2 = 150f + (112 * kotlin.math.sin(a)).toFloat()
            c.drawLine(x1, y1, x2, y2, ray)
        }
        p.color = 0xFFFFF3C9.toInt()
        c.drawCircle(318f, 150f, 34f, p)

        // Far hill
        p.color = 0xFFB9CF98.toInt()
        c.drawPath(Path().apply {
            moveTo(-3000f, 520f); lineTo(0f, 520f)
            cubicTo(70f, 470f, 150f, 462f, 230f, 488f); cubicTo(300f, 510f, 350f, 486f, 390f, 470f)
            lineTo(3390f, 470f); lineTo(3390f, 2000f); lineTo(-3000f, 2000f); close()
        }, p)
        // Middle hill with the party tree
        p.color = 0xFF94B373.toInt()
        c.drawPath(Path().apply {
            moveTo(-3000f, 560f); lineTo(0f, 560f)
            cubicTo(40f, 500f, 120f, 470f, 180f, 520f); cubicTo(220f, 552f, 300f, 560f, 390f, 540f)
            lineTo(3390f, 540f); lineTo(3390f, 2000f); lineTo(-3000f, 2000f); close()
        }, p)
        val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5B4A33.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(94f, 470f, 96f, 514f, trunk)
        c.drawLine(96f, 500f, 86f, 490f, trunk)
        c.drawLine(96f, 494f, 106f, 484f, trunk)
        p.color = 0xFF6F954F.toInt()
        c.drawCircle(96f, 456f, 26f, p); c.drawCircle(78f, 468f, 16f, p); c.drawCircle(114f, 466f, 18f, p)
        p.color = 0xFF7EA35C.toInt()
        c.drawCircle(100f, 446f, 16f, p)

        // Near hill with the round door
        p.color = 0xFF6E9752.toInt()
        c.drawPath(Path().apply {
            moveTo(-3000f, 640f); lineTo(0f, 640f)
            cubicTo(60f, 598f, 150f, 580f, 230f, 590f); cubicTo(300f, 600f, 350f, 620f, 390f, 640f)
            lineTo(3390f, 640f); lineTo(3390f, 2000f); lineTo(-3000f, 2000f); close()
        }, p)
        p.color = 0xFF7A6247.toInt()
        c.drawRoundRect(RectF(276f, 574f, 288f, 600f), 2f, 2f, p)
        p.color = 0xFFC9B48A.toInt(); c.drawCircle(228f, 640f, 34f, p)
        p.color = 0xFF2E5E3A.toInt(); c.drawCircle(228f, 640f, 28f, p)
        val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99244D2F.toInt(); strokeWidth = 1.2f }
        c.drawLine(200f, 640f, 256f, 640f, seam)
        c.drawLine(228f, 612f, 228f, 668f, seam)
        p.color = 0xFFE2B64A.toInt(); c.drawCircle(228f, 640f, 3.4f, p)
        p.color = 0xFFC9B48A.toInt(); c.drawCircle(168f, 628f, 12f, p)
        p.color = 0xFFFCE9B6.toInt(); c.drawCircle(168f, 628f, 9f, p)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9B48A.toInt(); strokeWidth = 1.4f }
        c.drawLine(159f, 628f, 177f, 628f, bar)
        c.drawLine(168f, 619f, 168f, 637f, bar)
        val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCD9C59A.toInt(); strokeWidth = 10f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        c.drawPath(Path().apply { moveTo(120f, 700f); cubicTo(150f, 680f, 190f, 676f, 228f, 674f) }, path)
        p.color = 0xFFE58C6B.toInt()
        c.drawCircle(150f, 664f, 3f, p); c.drawCircle(300f, 652f, 3f, p); c.drawCircle(314f, 660f, 2.6f, p)
        p.color = 0xFFF2D06B.toInt()
        c.drawCircle(138f, 670f, 2.6f, p); c.drawCircle(290f, 664f, 2.6f, p)
        // Foreground meadow under the dock
        p.color = 0xFF5E8645.toInt()
        c.drawRect(-3000f, 700f, 3390f, 2000f, p)
        c.restore()
    }

    // ------------------------------------------------------------ B · Red Book parchment

    private fun redBook(c: Canvas, w: Float, h: Float) {
        val base = Paint().apply {
            isDither = true
            shader = RadialGradient(
                w * 0.5f, h * 0.4f, max(w, h) * 0.75f,
                intArrayOf(0xFFF3E8CD.toInt(), 0xFFEADBB8.toInt(), 0xFFD9C59A.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, base)

        // Paper grain: tiny warm specks.
        val rnd = Random(1937)
        val speck = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat((w * h / (900 * dp * dp)).toInt().coerceIn(200, 1500)) {
            speck.color = if (rnd.nextBoolean()) 0xFF7A5A34.toInt() else 0xFFFFFBEF.toInt()
            speck.alpha = 10 + rnd.nextInt(22)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.4f + rnd.nextFloat() * 0.9f) * dp, speck)
        }

        // Hand-drawn map contours, in the design's 390×844 coordinates.
        val fr = Scene.frame(w, h)
        c.save()
        c.translate(fr.ox, fr.oy)
        c.scale(fr.sx, fr.sy)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = 0x478B6B43
        }
        fun curve(vararg pts: Float): Path = Path().apply {
            moveTo(pts[0], pts[1])
            var i = 2
            while (i + 5 < pts.size) {
                cubicTo(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4], pts[i + 5]); i += 6
            }
        }
        c.drawPath(curve(-20f, 560f, 60f, 520f, 120f, 580f, 190f, 540f, 260f, 500f, 320f, 500f, 410f, 540f), ink)
        c.drawPath(curve(-20f, 586f, 50f, 552f, 130f, 606f, 196f, 566f, 262f, 526f, 318f, 532f, 410f, 566f), ink)
        c.drawPath(curve(-20f, 612f, 70f, 584f, 136f, 630f, 204f, 594f, 272f, 558f, 330f, 560f, 410f, 594f), ink)
        c.drawPath(curve(30f, 690f, 80f, 650f, 150f, 670f, 170f, 700f, 190f, 730f, 110f, 760f, 60f, 740f, 10f, 720f, 0f, 720f, 30f, 690f), ink)
        c.drawPath(curve(52f, 694f, 84f, 670f, 136f, 684f, 148f, 704f, 160f, 724f, 104f, 742f, 72f, 730f, 40f, 718f, 34f, 712f, 52f, 694f), ink)
        c.drawPath(curve(250f, 660f, 300f, 640f, 360f, 660f, 360f, 700f, 360f, 740f, 300f, 740f, 270f, 720f, 240f, 700f, 220f, 680f, 250f, 660f), ink)
        c.drawPath(curve(-20f, 140f, 40f, 170f, 90f, 120f, 150f, 150f), ink)
        c.drawPath(curve(260f, 120f, 300f, 90f, 350f, 130f, 410f, 100f), ink)
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x595A4026 }
        for ((x, y, s) in listOf(Triple(96f, 612f, 12f), Triple(110f, 616f, 14f), Triple(126f, 612f, 10f), Triple(300f, 600f, 12f), Triple(314f, 604f, 10f))) {
            c.drawPath(Path().apply { moveTo(x, y); lineTo(x + s / 2, y - s); lineTo(x + s, y); close() }, hill)
        }
        c.restore()

        // Compass rose, bottom right.
        val cx = w - 58 * dp
        val cy = h - 66 * dp
        val rose = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C5A4026.toInt() }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * dp; color = 0x8C5A4026.toInt() }
        c.drawCircle(cx, cy, 22 * dp, ring)
        c.drawPath(Path().apply { moveTo(cx, cy - 28 * dp); lineTo(cx + 4 * dp, cy); lineTo(cx, cy + 28 * dp); lineTo(cx - 4 * dp, cy); close() }, rose)
        rose.alpha = 90
        c.drawPath(Path().apply { moveTo(cx - 28 * dp, cy); lineTo(cx, cy - 4 * dp); lineTo(cx + 28 * dp, cy); lineTo(cx, cy + 4 * dp); close() }, rose)
        val n = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9A2A1C.toInt(); textSize = 9 * dp; textAlign = Paint.Align.CENTER
        }
        c.drawText("N", cx, cy - 31 * dp, n)

        // Double ruled border.
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x807A5A34.toInt() }
        rule.strokeWidth = 0.8f * dp
        val o = 14 * dp
        c.drawRect(o, o, w - o, h - o, rule)
        rule.strokeWidth = 0.4f * dp
        val i = 19 * dp
        c.drawRect(i, i, w - i, h - i, rule)
    }

    // ------------------------------------------------------------ C · Elven Silver

    private fun elven(c: Canvas, w: Float, h: Float) {
        val base = Paint().apply {
            isDither = true
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF0B0F12.toInt(), 0xFF11171B.toInt(), 0xFF161D22.toInt()),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, base)
        stars(c, w, h, Random(1955), density = 5000f, maxY = 0.6f, brightness = 0.55f)

        // Arch in the design's 390×844 coordinates, framed to the screen.
        val fr = Scene.frame(w, h)
        val sx = fr.sx
        val sy = fr.sy
        c.save()
        c.translate(fr.ox, fr.oy)
        fun arch(inset: Float, apex: Float): Path = Path().apply {
            moveTo((70 + inset) * sx, 600 * sy)
            lineTo((70 + inset) * sx, 300 * sy)
            cubicTo((70 + inset) * sx, (210 + inset) * sy, (130 + inset * 0.6f) * sx, (150 + inset * 1.4f) * sy, 195 * sx, apex * sy)
            cubicTo((260 - inset * 0.6f) * sx, (150 + inset * 1.4f) * sy, (320 - inset) * sx, (210 + inset) * sy, (320 - inset) * sx, 300 * sy)
            lineTo((320 - inset) * sx, 600 * sy)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xBFB9C6CA.toInt(); strokeWidth = 0.9f * dp
        }
        c.drawPath(arch(0f, 96f), line)
        line.color = 0x66B9C6CA; line.strokeWidth = 0.5f * dp
        c.drawPath(arch(10f, 112f), line)
        c.drawLine(70 * sx, 600 * sy, 320 * sx, 600 * sy, line)

        // Star at the apex.
        val sxC = 195 * sx
        val syC = 82 * sy
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(sxC, syC, 24 * dp, 0x59BFD8E6, 0x00BFD8E6, Shader.TileMode.CLAMP)
        }
        c.drawCircle(sxC, syC, 24 * dp, halo)
        val s = 12 * dp
        val k = 3 * dp
        val star = Path().apply {
            moveTo(sxC, syC - s); lineTo(sxC + k, syC - k); lineTo(sxC + s, syC); lineTo(sxC + k, syC + k)
            lineTo(sxC, syC + s); lineTo(sxC - k, syC + k); lineTo(sxC - s, syC); lineTo(sxC - k, syC - k); close()
        }
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8F0F2.toInt() })
        val diag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99E8F0F2.toInt(); strokeWidth = 0.6f * dp }
        val d = 7 * dp
        c.drawLine(sxC - d, syC - d, sxC + d, syC + d, diag)
        c.drawLine(sxC - d, syC + d, sxC + d, syC - d, diag)

        // Leaf tendrils at the foot of each pillar.
        val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0x999FB2B8.toInt(); strokeWidth = 0.8f * dp; strokeCap = Paint.Cap.ROUND
        }
        for (side in listOf(1f, -1f)) {
            c.save()
            if (side < 0) c.scale(-1f, 1f, 195 * sx, 0f)
            val px = 70 * sx
            c.drawPath(Path().apply { moveTo(px, 560 * sy); cubicTo(px - 14 * dp, 540 * sy, px - 12 * dp, 516 * sy, px, 500 * sy) }, leaf)
            c.drawPath(Path().apply {
                moveTo(px - 6 * dp, 548 * sy); cubicTo(px - 18 * dp, 546 * sy, px - 24 * dp, 538 * sy, px - 22 * dp, 528 * sy)
                cubicTo(px - 12 * dp, 530 * sy, px - 6 * dp, 538 * sy, px - 6 * dp, 548 * sy)
            }, leaf)
            c.drawPath(Path().apply {
                moveTo(px - 4 * dp, 520 * sy); cubicTo(px + 6 * dp, 516 * sy, px + 12 * dp, 508 * sy, px + 10 * dp, 498 * sy)
                cubicTo(px, 500 * sy, px - 4 * dp, 510 * sy, px - 4 * dp, 520 * sy)
            }, leaf)
            c.restore()
        }
        c.restore()
    }
}

/** Where the artwork sits on screen, shared by the still painting and the animated layer. */
internal object Scene {
    /**
     * Shire Morning, drawn in a 390×844 design, is scaled to fill the width on a phone and
     * anchored to the bottom; on a wide screen (a tablet, a foldable, landscape) it's scaled to
     * the height instead and centred, with the hills running out to both edges.
     * Returns scale, x offset, y offset.
     */
    fun shire(w: Float, h: Float): Triple<Float, Float, Float> {
        val s = minOf(w / 390f, h / 844f)
        return Triple(s, (w - 390f * s) / 2f, h - 844f * s)
    }

    /** Where the 390×844 design sits for the framed scenes (the Elven arch, the Red Book map). */
    class Frame(val sx: Float, val sy: Float, val ox: Float, val oy: Float)

    /**
     * On a phone this is simply the whole screen (as designed). On a wider screen the design
     * stays in proportion (stretched sideways at most 1.5×) and is centred, so the arch doesn't
     * flatten into a wide tunnel.
     */
    fun frame(w: Float, h: Float): Frame {
        val sx0 = w / 390f
        val sy0 = h / 844f
        val sx = minOf(sx0, sy0 * 1.5f)
        val sy = minOf(sy0, sx0 * 1.15f)
        return Frame(sx, sy, (w - 390f * sx) / 2f, (h - 844f * sy) / 2f)
    }
}
