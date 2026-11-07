package com.middleearth.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.random.Random

/**
 * The Red Book's little map of the road: the Shire to Mordor in brown ink on parchment, mountains,
 * rivers and forests sketched in, the road dashed, the part you've walked drawn in red, and a
 * small lamp where you are.
 */
class JourneyMap(ctx: Context, look: Look, private val km: Float) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val ink = 0xFF5A3E26.toInt()
    private val red = 0xFF9A2A1C.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER }
    private val italic: Typeface = Fonts.get(ctx.assets, "IMFellEnglish-Italic.ttf", 400, true)

    init {
        val n = Journey.next(ctx)
        contentDescription = "Map of the road from Bag End to Mount Doom. " + Journey.line(ctx) + (n?.let { "." } ?: "")
    }

    // map space: x 0.12..0.98 east, y 0.04..0.98 south
    private fun X(x: Float) = (x - .12f) / .86f * (width - 16 * dp) + 8 * dp
    private fun Y(y: Float) = (y - .04f) / .94f * (height - 16 * dp) + 8 * dp

    private fun mountains(c: Canvas, pts: List<Pair<Float, Float>>, step: Float) {
        stroke.color = ink; stroke.alpha = 190; stroke.strokeWidth = 1.2f * dp; stroke.pathEffect = null
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]; val (x1, y1) = pts[i + 1]
            val len = kotlin.math.hypot(X(x1) - X(x0), Y(y1) - Y(y0))
            val n = (len / (step * dp)).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                val f = k / n.toFloat()
                val x = X(x0 + (x1 - x0) * f); val y = Y(y0 + (y1 - y0) * f)
                val s = 4.5f * dp
                c.drawPath(Path().apply { moveTo(x - s, y + s * .5f); lineTo(x, y - s); lineTo(x + s, y + s * .5f) }, stroke)
            }
        }
    }

    private fun river(c: Canvas, pts: List<Pair<Float, Float>>) {
        val p = Path(); p.moveTo(X(pts[0].first), Y(pts[0].second))
        for (i in 1 until pts.size) { val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]; p.quadTo(X(x0), Y(y0), (X(x0) + X(x1)) / 2, (Y(y0) + Y(y1)) / 2) }
        p.lineTo(X(pts.last().first), Y(pts.last().second))
        stroke.color = 0xFF4E6E86.toInt(); stroke.alpha = 170; stroke.strokeWidth = 1.4f * dp; stroke.pathEffect = null
        c.drawPath(p, stroke)
    }

    private fun forest(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, n: Int, color: Int, seed: Int) {
        val r = Random(seed)
        fill.shader = null; fill.color = color
        repeat(n) {
            val a = r.nextFloat() * 6.28f; val d = kotlin.math.sqrt(r.nextFloat())
            val x = X(cx + kotlin.math.cos(a) * rx * d); val y = Y(cy + kotlin.math.sin(a) * ry * d)
            fill.alpha = 150; c.drawCircle(x, y, 2.4f * dp, fill)
            stroke.color = ink; stroke.alpha = 120; stroke.strokeWidth = .9f * dp; stroke.pathEffect = null
            c.drawLine(x, y + 2.4f * dp, x, y + 4f * dp, stroke)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // parchment, darker at the edges
        fill.shader = LinearGradient(0f, 0f, 0f, h, 0xFFF2E6CA.toInt(), 0xFFE6D5AE.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, w, h, 16 * dp, 16 * dp, fill)
        fill.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * .7f, intArrayOf(0x00000000, 0x00000000, 0x33704A20), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, w, h, 16 * dp, 16 * dp, fill)
        fill.shader = null

        // the lie of the land
        river(c, listOf(.205f to .12f, .215f to .3f, .225f to .45f, .235f to .62f))                       // the Brandywine
        river(c, listOf(.6f to .04f, .61f to .25f, .625f to .45f, .65f to .58f, .68f to .66f, .7f to .82f, .72f to .98f))   // the Great River
        mountains(c, listOf(.535f to .05f, .55f to .2f, .54f to .35f, .525f to .5f), 9f)                  // the Misty Mountains
        mountains(c, listOf(.79f to .615f, .98f to .6f), 9f)                                              // the Ash Mountains
        mountains(c, listOf(.79f to .64f, .8f to .96f), 9f)                                               // the Mountains of Shadow
        forest(c, .26f, .34f, .025f, .03f, 9, 0xFF5E7A44.toInt(), 1)       // the Old Forest
        forest(c, .69f, .2f, .07f, .13f, 34, 0xFF4E6A3A.toInt(), 2)        // Mirkwood
        forest(c, .575f, .46f, .02f, .025f, 7, 0xFFC9A23A.toInt(), 3)      // Lórien
        forest(c, .585f, .56f, .025f, .03f, 9, 0xFF5E7A44.toInt(), 4)      // Fangorn
        // Mount Doom, smouldering
        val dx = X(.85f); val dy = Y(.75f)
        fill.color = 0xFF6A4A36.toInt(); fill.alpha = 230
        c.drawPath(Path().apply { moveTo(dx - 9 * dp, dy + 5 * dp); lineTo(dx - 2 * dp, dy - 8 * dp); lineTo(dx + 2 * dp, dy - 8 * dp); lineTo(dx + 9 * dp, dy + 5 * dp); close() }, fill)
        fill.color = 0xFFD9542A.toInt(); c.drawCircle(dx, dy - 8 * dp, 2.2f * dp, fill)

        // the road: dashed ahead, red where you've walked
        val road = Path(); val walked = Path()
        val pl = Journey.PLACES
        road.moveTo(X(pl[0].x), Y(pl[0].y)); walked.moveTo(X(pl[0].x), Y(pl[0].y))
        var hx = X(pl[0].x); var hy = Y(pl[0].y)
        for (i in 1 until pl.size) {
            val a = pl[i - 1]; val b = pl[i]
            road.lineTo(X(b.x), Y(b.y))
            if (km >= b.km) { walked.lineTo(X(b.x), Y(b.y)); hx = X(b.x); hy = Y(b.y) }
            else if (km > a.km) {
                val f = (km - a.km) / (b.km - a.km)
                hx = X(a.x + (b.x - a.x) * f); hy = Y(a.y + (b.y - a.y) * f); walked.lineTo(hx, hy)
            }
        }
        stroke.color = ink; stroke.alpha = 200; stroke.strokeWidth = 1.3f * dp; stroke.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 3 * dp), 0f)
        c.drawPath(road, stroke)
        stroke.pathEffect = null; stroke.color = red; stroke.alpha = 255; stroke.strokeWidth = 2.6f * dp
        c.drawPath(walked, stroke)

        // the places, named where there's room
        label.typeface = italic; label.textSize = 10.5f * dp
        for (p in pl) {
            fill.color = if (km >= p.km) red else ink; fill.alpha = 255
            c.drawCircle(X(p.x), Y(p.y), (if (p.deed) 2.6f else 1.6f) * dp, fill)
            if (p.deed || p.id == "bagend") {
                val below = p.id in setOf("moria", "amonhen", "doom", "weathertop", "bagend")
                c.drawText(p.name.removePrefix("the ").removePrefix("Gates of ").let { if (it == "Black Gate") "the Black Gate" else it }, X(p.x), Y(p.y) + (if (below) 13 else -7) * dp, label)
            }
        }
        // and you, a little lamp on the road
        fill.shader = RadialGradient(hx, hy, 12 * dp, 0x99FFC867.toInt(), 0x00FFC867, Shader.TileMode.CLAMP)
        c.drawCircle(hx, hy, 12 * dp, fill); fill.shader = null
        fill.color = 0xFFFFF0C4.toInt(); c.drawCircle(hx, hy, 3.2f * dp, fill)
        stroke.color = red; stroke.strokeWidth = 1.2f * dp; c.drawCircle(hx, hy, 3.2f * dp, stroke)
    }
}
