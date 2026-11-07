package com.middleearth.launcher

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hidden phrases: type "mellon" in search and an Elven door glows silver and opens on warm light;
 * type "ash nazg" and the screen darkens while fire-letters burn around a gold ring. A few seconds
 * each; tap to dismiss. (The letters are drawn for Lembas, not copied from anywhere.)
 */
object Wonders {
    const val MELLON = 0
    const val RING = 1

    fun show(ctx: Context, parent: ViewGroup, which: Int) {
        if (parent.findViewWithTag<View>("wonder") != null) return
        val v = WonderView(ctx, which).apply { tag = "wonder" }
        parent.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        Haptics.feel(parent, Haptics.Feel.CONFIRM)
        Deeds.saw(ctx, if (which == MELLON) "mellon" else "ring")
    }
}

private class WonderView(ctx: Context, private val which: Int) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val dur = if (which == Wonders.MELLON) 6000f else 5500f
    private var leaving = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val italic: Typeface = Fonts.get(ctx.assets, "CormorantGaramond-Italic.ttf", 500, true)

    init { isClickable = true; contentDescription = if (which == Wonders.MELLON) "Mellon: friend. The doors open." else "Fire-letters burn on a gold ring." }

    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_UP) leave(); return true }
    private fun leave() { if (leaving == 0L) { leaving = SystemClock.uptimeMillis(); invalidate() } }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / dur
        if (t >= 1f && leaving == 0L) leaving = now
        val out = if (leaving == 0L) 1f else 1f - (now - leaving) / 600f
        if (out <= 0f) { (parent as? ViewGroup)?.removeView(this); return }
        val inn = min(1f, (now - start) / 700f)
        val a = inn * out
        if (which == Wonders.MELLON) drawDoor(c, t.coerceAtMost(1f), a) else drawRing(c, t.coerceAtMost(1f), a)
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ mellon: an Elven door
    private val doorStars = Random(7).let { r -> List(60) { floatArrayOf(r.nextFloat(), r.nextFloat() * .7f, .6f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f) } }

    private fun drawDoor(c: Canvas, t: Float, a: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = null; fill.color = 0xFF070A14.toInt(); fill.alpha = (235 * a).toInt()
        c.drawRect(0f, 0f, w, h, fill)
        val s = (now() / 1000f)
        for (st in doorStars) { fill.color = 0xFFF2F4FA.toInt(); fill.alpha = (a * 255 * (.3f + .7f * (.5f + .5f * sin(s * 1.3f + st[3])))).toInt(); c.drawCircle(st[0] * w, st[1] * h, st[2] * dp, fill) }
        val cx = w / 2; val dw = min(w * .62f, 300 * dp); val dh = dw * 1.45f; val top = h * .5f - dh * .55f; val bot = top + dh
        // the light behind, as the doors part
        val open = ((t - .45f) / .35f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        if (open > 0f) {
            fill.shader = RadialGradient(cx, bot - dh * .4f, dh * .8f, intArrayOf(0xFFFFE9B0.toInt(), 0xCCFFC867.toInt(), 0x00FFC867), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            fill.alpha = (255 * a * open).toInt(); c.drawRect(cx - dw / 2, top, cx + dw / 2, bot, fill); fill.shader = null
        }
        // the lines draw themselves in silver, then the two leaves swing apart
        val draw = (t / .4f).coerceIn(0f, 1f)
        val arch = Path().apply {
            moveTo(cx - dw / 2, bot); lineTo(cx - dw / 2, top + dw / 2)
            arcTo(cx - dw / 2, top, cx + dw / 2, top + dw, 180f, 180f, false)
            lineTo(cx + dw / 2, bot)
        }
        val pm = android.graphics.PathMeasure(arch, false)
        val part = Path(); pm.getSegment(0f, pm.length * draw, part, true)
        glow.color = 0xFFCFE0FF.toInt(); glow.alpha = (110 * a).toInt(); glow.strokeWidth = 9 * dp
        if (glow.maskFilter == null) glow.maskFilter = BlurMaskFilter(7 * dp, BlurMaskFilter.Blur.NORMAL)
        stroke.color = 0xFFF2F6FF.toInt(); stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = 2.4f * dp
        c.drawPath(part, glow); c.drawPath(part, stroke)
        // two slender trees up the sides, a crown of arches, and a star at the top
        val dk = ((t - .15f) / .3f).coerceIn(0f, 1f)
        for (side in listOf(-1f, 1f)) {
            val x = cx + side * dw * .36f
            val leaf = Path().apply { moveTo(x, bot); lineTo(x, bot - (dh * .72f) * dk) }
            c.drawPath(leaf, glow); c.drawPath(leaf, stroke)
            for (k in 1..4) {
                val y = bot - dh * .72f * dk * k / 4.5f
                val br = Path().apply { moveTo(x, y); quadTo(x - side * dw * .08f, y - dh * .05f, x - side * dw * .12f, y - dh * .11f) }
                if (dk * 4.5f > k) { c.drawPath(br, glow); c.drawPath(br, stroke) }
            }
        }
        if (dk > .9f) {
            val sy = top + dw * .32f; val r = dw * .07f
            val star = Path()
            for (k in 0 until 16) { val ang = k * PI.toFloat() / 8 - PI.toFloat() / 2; val rr = if (k % 2 == 0) r else r * .38f; if (k == 0) star.moveTo(cx + cos(ang) * rr, sy + sin(ang) * rr) else star.lineTo(cx + cos(ang) * rr, sy + sin(ang) * rr) }
            star.close()
            fill.color = 0xFFF2F6FF.toInt(); fill.alpha = (255 * a * ((dk - .9f) * 10)).toInt(); c.drawPath(star, fill)
        }
        // the leaves swinging open: a darker panel on each side narrowing toward the hinge
        if (open > 0f) {
            fill.color = 0xFF0C1222.toInt(); fill.alpha = (220 * a).toInt()
            val leafW = dw / 2 * (1f - open)
            c.drawRect(cx - dw / 2, top + dw / 2, cx - dw / 2 + leafW, bot, fill)
            c.drawRect(cx + dw / 2 - leafW, top + dw / 2, cx + dw / 2, bot, fill)
        }
        text.typeface = italic; text.textSize = 30 * dp; text.color = 0xFFF2F6FF.toInt()
        text.alpha = (255 * a * ((t - .2f) / .2f).coerceIn(0f, 1f)).toInt()
        c.drawText("Mellon", cx, bot + 56 * dp, text)
        text.textSize = 17 * dp; text.alpha = (text.alpha * .75f).toInt()
        c.drawText("friend, and the doors open", cx, bot + 84 * dp, text)
    }

    // ------------------------------------------------------------------ ash nazg: fire-letters on a ring
    private val glyphs: List<Path> = Random(1954).let { r ->
        List(34) {
            Path().apply {
                // a bowl or a stem, with a flourish: script-like, belonging to no real alphabet
                when (r.nextInt(3)) {
                    0 -> { moveTo(0f, -6f); cubicTo(6f, -10f, 8f, 2f, 0f, 4f) ; moveTo(0f, -10f); lineTo(0f, 10f) }
                    1 -> { moveTo(-4f, -8f); cubicTo(6f, -8f, 6f, 6f, -2f, 6f); moveTo(2f, 6f); quadTo(6f, 10f, 2f, 12f) }
                    else -> { moveTo(-3f, 8f); lineTo(0f, -10f); moveTo(-5f, -2f); quadTo(2f, -6f, 6f, 0f) }
                }
                if (r.nextBoolean()) { moveTo(-3f, -13f); lineTo(3f, -14f) }
            }
        }
    }

    private fun drawRing(c: Canvas, t: Float, a: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = null; fill.color = 0xFF050203.toInt(); fill.alpha = (240 * a).toInt()
        c.drawRect(0f, 0f, w, h, fill)
        val cx = w / 2; val cy = h * .46f; val r = min(w, h) * .3f
        val s = now() / 1000f
        // embers rising
        val er = Random(3)
        for (i in 0 until 40) {
            val p = (s * (.05f + er.nextFloat() * .08f) + er.nextFloat()) % 1f
            fill.color = 0xFFFF8A3A.toInt(); fill.alpha = (a * 200 * sin(PI.toFloat() * p)).toInt()
            c.drawCircle(er.nextFloat() * w + 20 * dp * sin(s + i), h * (1f - p), (1f + er.nextFloat() * 2f) * dp, fill)
        }
        // the ring: a gold band, lit from above
        val band = r * .13f
        stroke.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(0xFFFFE8A0.toInt(), 0xFFD9A43A.toInt(), 0xFF8E6620.toInt(), 0xFFF6D06A.toInt()), floatArrayOf(0f, .4f, .75f, 1f), Shader.TileMode.CLAMP)
        stroke.alpha = (255 * a).toInt(); stroke.strokeWidth = band
        c.drawOval(cx - r, cy - r * .42f, cx + r, cy + r * .42f, stroke)
        stroke.shader = null
        stroke.color = 0xFFFFF6D8.toInt(); stroke.alpha = (180 * a).toInt(); stroke.strokeWidth = band * .18f
        c.drawArc(cx - r + band * .25f, cy - r * .42f + band * .1f, cx + r - band * .25f, cy + r * .42f - band * .3f, 200f, 70f, false, stroke)
        // the letters kindle one by one around the band, flickering
        val lit = (t / .5f).coerceIn(0f, 1f)
        for ((i, g) in glyphs.withIndex()) {
            val f = i / glyphs.size.toFloat()
            if (f > lit) break
            val ang = (f * 2 * PI + PI * .5).toFloat()
            val x = cx + cos(ang) * r; val y = cy + sin(ang) * r * .42f
            val flick = .75f + .25f * sin(s * 9f + i * 1.3f)
            c.save(); c.translate(x, y); c.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f); c.scale(band / 26f, band / 26f * (.5f + .5f * kotlin.math.abs(sin(ang))))
            glow.color = 0xFFFF6A1A.toInt(); glow.alpha = (160 * a * flick).toInt(); glow.strokeWidth = 5f
            if (glow.maskFilter == null) glow.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            c.drawPath(g, glow)
            stroke.color = 0xFFFFE08A.toInt(); stroke.alpha = (255 * a * flick).toInt(); stroke.strokeWidth = 1.8f
            c.drawPath(g, stroke)
            c.restore()
        }
        text.typeface = italic; text.textSize = 17 * dp; text.color = 0xFFE8B66A.toInt()
        text.alpha = (200 * a * ((t - .6f) / .2f).coerceIn(0f, 1f)).toInt()
        c.drawText("Best put it away again.", cx, cy + r * .42f + 90 * dp, text)
    }

    private fun now() = SystemClock.uptimeMillis()
}
