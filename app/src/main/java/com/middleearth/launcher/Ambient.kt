package com.middleearth.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The gentle moving parts drawn over the still artwork: twinkling stars, the campfire,
 * silver motes, drifting clouds. Everything is set up once; drawing a frame allocates nothing.
 * Used by both the home screen and the live wallpaper.
 */
class Ambient(private val look: Look, private val w: Float, private val h: Float, private val dp: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // the framed scenes (arch, map) sit where Scene.frame puts them; the sky fills the screen
    private val fr = Scene.frame(w, h)
    private val sx = fr.sx
    private val sy = fr.sy
    private val ox = fr.ox
    private val oy = fr.oy

    fun draw(c: Canvas, t: Float) {
        when (look.id) {
            "starlit" -> starlit(c, t)
            "redbook" -> redBook(c, t)
            "elven" -> elven(c, t)
            "shire" -> shire(c, t)
        }
    }

    private fun wave(t: Float, period: Float, phase: Float) =
        0.5f + 0.5f * sin(2 * PI.toFloat() * t / period + phase)

    // ---------------------------------------------------------------- Starlit Night

    private val rnd = Random(77)
    private val twN = 46
    private val twX = FloatArray(twN) { rnd.nextFloat() * w }
    private val twY = FloatArray(twN) { h * 0.68f * rnd.nextFloat().pow(1.3f) }
    private val twR = FloatArray(twN) { (0.7f + rnd.nextFloat().pow(3f) * 1.1f) * dp }
    private val twP = FloatArray(twN) { 1.6f + rnd.nextFloat() * 3f }
    private val twPh = FloatArray(twN) { rnd.nextFloat() * 6.28f }
    private val twC = IntArray(twN) {
        val k = rnd.nextFloat()
        if (k < 0.12f) 0xFFFFE2B0.toInt() else if (k < 0.25f) 0xFFBFD4FF.toInt() else 0xFFF4F1E8.toInt()
    }
    private val spN = 7
    private val spX = FloatArray(spN) { (20 + rnd.nextFloat() * 350) * (w / 390f) }
    private val spY = FloatArray(spN) { (30 + rnd.nextFloat() * 420) * (h / 844f) }
    private val spP = FloatArray(spN) { 2.2f + rnd.nextFloat() * 2.5f }
    private val spPh = FloatArray(spN) { rnd.nextFloat() * 6.28f }
    private val sparkle = Path().apply {
        val s = 9f * dp
        val k = 1.2f * dp
        moveTo(0f, -s); lineTo(k, -k); lineTo(s, 0f); lineTo(k, k)
        lineTo(0f, s); lineTo(-k, k); lineTo(-s, 0f); lineTo(-k, -k); close()
    }
    private val shootTail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(-90 * dp, 0f, 0f, 0f, 0x00FFF4D8, 0xF2FFF4D8.toInt(), Shader.TileMode.CLAMP)
    }
    private val shootAngle = Math.toDegrees(atan2(0.11 * h, -0.54 * w).toDouble()).toFloat()

    private fun starlit(c: Canvas, t: Float) {
        for (i in 0 until twN) {
            val f = wave(t, twP[i], twPh[i]).let { it * it }
            fill.color = twC[i]
            fill.alpha = (255 * (0.12f + 0.88f * f)).toInt()
            c.drawCircle(twX[i], twY[i], twR[i] * (0.6f + 0.7f * f), fill)
        }
        for (i in 0 until spN) {
            val f = wave(t, spP[i], spPh[i])
            fill.color = 0xFFFFF8E6.toInt()
            fill.alpha = (40 * f).toInt()
            c.drawCircle(spX[i], spY[i], 7 * dp * f, fill)
            fill.alpha = (255 * (0.2f + 0.8f * f)).toInt()
            c.save()
            c.translate(spX[i], spY[i])
            c.rotate(20f * f)
            val s = 0.35f + 0.65f * f
            c.scale(s, s)
            c.drawPath(sparkle, fill)
            c.restore()
        }
        // A shooting star every 9 seconds.
        val p = (t % 9f) / 9f
        if (p in 0.86f..0.96f) {
            val q = (p - 0.86f) / 0.10f
            val a = if (q < 0.2f) q / 0.2f else 1f - (q - 0.2f) / 0.8f
            shootTail.alpha = (255 * a).toInt()
            c.save()
            c.translate(w * (0.77f - 0.54f * q), h * (0.09f + 0.11f * q))
            c.rotate(shootAngle)
            c.drawRect(-90 * dp, -0.75f * dp, 0f, 0.75f * dp, shootTail)
            c.restore()
        }
    }

    // ---------------------------------------------------------------- Red Book

    private val candle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 260 * dp, 0x8CFFECBE.toInt(), 0x00FFECBE, Shader.TileMode.CLAMP)
    }
    private val moteN = 12
    private val moteX = FloatArray(moteN) { ox + (40 + rnd.nextFloat() * 310) * sx }
    private val moteY = FloatArray(moteN) { oy + (260 + rnd.nextFloat() * 460) * sy }
    private val moteP = FloatArray(moteN) { 9f + rnd.nextFloat() * 8f }
    private val moteO = FloatArray(moteN) { rnd.nextFloat() * 14f }

    private val flameOuter = Path().apply {
        moveTo(0f, 0f); cubicTo(-10f, -4f, -10f, -14f, -5f, -21f); cubicTo(-4f, -16f, -1f, -15f, -1f, -18f)
        cubicTo(-2f, -24f, 1f, -30f, 6f, -34f); cubicTo(4f, -26f, 10f, -22f, 9f, -13f); cubicTo(9f, -5f, 6f, -1f, 0f, 0f); close()
    }
    private val flameInner = Path().apply {
        moveTo(0f, 0f); cubicTo(-4f, -2f, -5f, -7f, -3f, -11f); cubicTo(-2f, -8f, 0f, -8f, 0f, -10f)
        cubicTo(0f, -13f, 2f, -16f, 4f, -18f); cubicTo(4f, -13f, 6f, -9f, 5f, -5f); cubicTo(4f, -2f, 2f, 0f, 0f, 0f); close()
    }
    private val wisp = Path().apply {
        moveTo(0f, 0f); cubicTo(-5f, -8f, 5f, -16f, 0f, -24f); cubicTo(-5f, -32f, -5f, -40f, 0f, -48f)
    }
    private val fireGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 38f, 0x8CF2B65A.toInt(), 0x00F2B65A, Shader.TileMode.CLAMP)
    }
    private val stones = floatArrayOf(-19f, 5f, -12f, 10f, -3f, 12f, 6f, 12f, 14f, 9f, 20f, 4f)

    private fun redBook(c: Canvas, t: Float) {
        // Candlelight drifting across the page
        c.save()
        c.translate(
            w * 0.5f + (24 * sin(t * 0.35f) - 10 * sin(t * 0.21f)) * dp,
            h * 0.45f + (22 * sin(t * 0.27f + 1f)) * dp,
        )
        val cs = 1f + 0.04f * sin(t * 0.33f)
        c.scale(cs, cs)
        c.drawCircle(0f, 0f, 260 * dp, candle)
        c.restore()

        // Dust in the light
        fill.color = 0xFFB08A55.toInt()
        for (i in 0 until moteN) {
            val q = ((t + moteO[i]) % moteP[i]) / moteP[i]
            val a = when { q < 0.15f -> q / 0.15f; q > 0.85f -> (1f - q) / 0.15f; else -> 1f }
            fill.alpha = (180 * a).toInt()
            c.drawCircle(moteX[i] + 30 * dp * q, moteY[i] - 140 * dp * q, 1.5f * dp, fill)
        }

        // The campfire, inked on the map
        c.save()
        c.translate(ox + 204 * sx, oy + 654 * sy)
        c.scale(sx, sx)
        val gp = 0.55f + 0.3f * wave(t, 2.2f, 0f)
        c.save()
        c.translate(0f, -6f)
        c.scale(1f, 22f / 38f)
        fireGlow.alpha = (255 * gp).toInt()
        c.drawCircle(0f, 0f, 38f, fireGlow)
        c.restore()

        stroke.color = 0xFF6A5236.toInt()
        stroke.strokeWidth = 1f
        for (k in 0 until 3) {
            val q = ((t + k * 1.4f) % 4.2f) / 4.2f
            val a = if (q < 0.18f) q / 0.18f * 0.75f else 0.75f * (1f - (q - 0.18f) / 0.82f)
            stroke.alpha = (255 * a).toInt()
            c.save()
            c.translate((k - 1) * 3f + 8f * q, -18f - 78f * q)
            c.scale(0.6f + 0.7f * q, 1f)
            c.drawPath(wisp, stroke)
            c.restore()
        }

        c.save()
        c.skew(0.05f * sin(t * 3.7f), 0f)
        c.scale(1f + 0.04f * sin(t * 4.5f), 1f + 0.08f * sin(t * 5.3f + 1f) + 0.03f * sin(t * 11f))
        fill.color = 0xFF9A2A1C.toInt(); fill.alpha = 255
        c.drawPath(flameOuter, fill)
        c.restore()
        c.save()
        c.scale(1f + 0.05f * sin(t * 6.1f + 2f), 1f + 0.1f * sin(t * 7.2f))
        fill.color = 0xFFD9973A.toInt()
        c.drawPath(flameInner, fill)
        c.restore()

        stroke.alpha = 255
        stroke.color = 0xFF4A3420.toInt(); stroke.strokeWidth = 2.2f
        c.drawLine(-14f, 6f, 14f, -3f, stroke)
        c.drawLine(-14f, -3f, 14f, 6f, stroke)
        stroke.color = 0xFF6A5236.toInt(); stroke.strokeWidth = 0.9f
        var i = 0
        while (i < stones.size) {
            c.drawCircle(stones[i], stones[i + 1], 3f, stroke); i += 2
        }
        c.restore()
    }

    // ---------------------------------------------------------------- Elven Silver

    private val arch = Path().apply {
        moveTo(70 * sx, 600 * sy); lineTo(70 * sx, 300 * sy)
        cubicTo(70 * sx, 210 * sy, 130 * sx, 150 * sy, 195 * sx, 96 * sy)
        cubicTo(260 * sx, 150 * sy, 320 * sx, 210 * sy, 320 * sx, 300 * sy)
        lineTo(320 * sx, 600 * sy)
        offset(ox, oy)
    }
    private val archMeasure = PathMeasure(arch, false)
    private val archLen = archMeasure.length
    private val shimmer = Path()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 26 * dp, 0x80BFD8E6.toInt(), 0x00BFD8E6, Shader.TileMode.CLAMP)
    }
    private val evN = 16
    private val evX = FloatArray(evN) { ox + (60 + rnd.nextFloat() * 270) * sx }
    private val evY = FloatArray(evN) { oy + (380 + rnd.nextFloat() * 340) * sy }
    private val evP = FloatArray(evN) { 8f + rnd.nextFloat() * 9f }
    private val evO = FloatArray(evN) { rnd.nextFloat() * 16f }

    private fun elven(c: Canvas, t: Float) {
        val q = ((t % 7f) / 7f) * 1.2f - 0.1f
        val start = (q * archLen).coerceIn(0f, archLen)
        val end = ((q + 0.08f) * archLen).coerceIn(0f, archLen)
        if (end > start) {
            shimmer.reset()
            archMeasure.getSegment(start, end, shimmer, true)
            stroke.color = 0xFFF2FAFC.toInt(); stroke.alpha = 220; stroke.strokeWidth = 1.6f * dp
            c.drawPath(shimmer, stroke)
        }
        val f = wave(t, 5f, 0f)
        c.save()
        c.translate(ox + 195 * sx, oy + 82 * sy)
        val s = 1f + 0.18f * f
        c.scale(s, s)
        halo.alpha = (255 * (0.55f + 0.45f * f)).toInt()
        c.drawCircle(0f, 0f, 26 * dp, halo)
        c.restore()
        for (i in 0 until evN) {
            val p = ((t + evO[i]) % evP[i]) / evP[i]
            val a = when { p < 0.2f -> p / 0.2f * 0.8f; p > 0.8f -> (1f - p) / 0.2f * 0.6f; else -> 0.8f - 0.2f * (p - 0.2f) / 0.6f }
            val x = evX[i]
            val y = evY[i] - 220 * dp * p
            fill.color = 0xFFC8E1EB.toInt(); fill.alpha = (60 * a).toInt()
            c.drawCircle(x, y, 3.2f * dp, fill)
            fill.color = 0xFFDCEBF0.toInt(); fill.alpha = (255 * a).toInt()
            c.drawCircle(x, y, 1f * dp, fill)
        }
    }

    // ---------------------------------------------------------------- Shire Morning

    private val shireT = Scene.shire(w, h)
    private val cloudRect = RectF(14f, 22f, 104f, 34f)

    private fun cloud(c: Canvas, x: Float, y: Float, scale: Float, alpha: Int) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = alpha
        c.drawRoundRect(cloudRect, 6f, 6f, fill)
        c.drawCircle(30f, 22f, 12f, fill)
        c.drawCircle(60f, 20f, 17f, fill)
        c.drawCircle(90f, 23f, 12f, fill)
        c.restore()
    }

    private fun shire(c: Canvas, t: Float) {
        val (s, sox, soy) = shireT
        c.save()
        c.translate(sox, soy)
        c.scale(s, s)
        val span = 660f
        cloud(c, -140f + span * (((t + 20f) % 70f) / 70f), 236f, 1f, 215)
        cloud(c, -140f + span * (((t + 62f) % 95f) / 95f), 300f, 0.7f, 180)
        cloud(c, -140f + span * (((t + 96f) % 120f) / 120f), 196f, 0.53f, 150)
        for (k in 0 until 3) {
            val q = ((t + k * 1.5f) % 4.5f) / 4.5f
            val a = if (q < 0.15f) q / 0.15f else 1f - (q - 0.15f) / 0.85f
            fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (140 * a).toInt()
            c.drawCircle(282f + 26f * q, 568f - 90f * q, 6f * (0.6f + 1.2f * q), fill)
        }
        c.restore()
    }
}

/**
 * Draws [Ambient] over the home-screen artwork at about 30 frames a second, only while
 * the home screen is showing, animations are on and battery saver is off.
 */
class AmbientView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var ambient: Ambient? = null
    private var running = false
    private var lastFrame = 0L
    private val start = SystemClock.uptimeMillis()
    private val power = context.getSystemService(PowerManager::class.java)

    var look: Look = Look.STARLIT
        set(value) {
            field = value
            rebuild()
        }

    var animationsOn = true
        set(value) {
            field = value
            update()
        }

    var active = false
        set(value) {
            field = value
            update()
        }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000
            if (now - lastFrame >= 31) {
                lastFrame = now
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        ambient = if (width > 0 && height > 0)
            Ambient(look, width.toFloat(), height.toFloat(), resources.displayMetrics.density) else null
        invalidate()
    }

    private fun update() {
        val should = animationsOn && active && isAttachedToWindow && power?.isPowerSaveMode != true
        if (should == running) return
        running = should
        if (should) Choreographer.getInstance().postFrameCallback(frame)
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // With animations off this still draws one still frame (e.g. the campfire).
        ambient?.draw(canvas, (SystemClock.uptimeMillis() - start) / 1000f)
    }
}
