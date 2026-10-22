package com.middleearth.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * The phone's sun arc (the watch face's sun ring, laid flat): today's daylight as an arc from
 * sunrise on the left to sunset on the right, with the sun on it. At night the arc dims and the
 * sun waits below the horizon at the side it will rise from. Sunrise and sunset times sit at
 * the ends. Redraws only when the minute changes.
 */
class SunArcView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val horizon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private var minute = 0
    private var day = Sun.today()
    private var is24 = true

    fun setStyle(color: Int, sunColor: Int, font: Typeface) {
        track.color = (color and 0x00FFFFFF) or 0x40000000
        track.strokeWidth = 1.4f * dp
        lit.color = (color and 0x00FFFFFF) or 0xB0000000.toInt()
        lit.strokeWidth = 2.2f * dp
        horizon.color = (color and 0x00FFFFFF) or 0x30000000
        horizon.strokeWidth = 1f * dp
        sun.color = sunColor
        glow.color = (sunColor and 0x00FFFFFF) or 0x40000000
        label.color = (color and 0x00FFFFFF) or 0xA0000000.toInt()
        label.typeface = font
        label.textSize = 11 * dp
        invalidate()
    }

    fun update(now: LocalDateTime, use24: Boolean) {
        val m = now.hour * 60 + now.minute
        if (m == minute && use24 == is24) return
        minute = m
        is24 = use24
        day = Sun.today(ZoneId.systemDefault(), now.toLocalDate())
        contentDescription = "Sunrise ${fmt(day.sunrise)}, sunset ${fmt(day.sunset)}"
        invalidate()
    }

    private fun fmt(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return if (is24) "%d:%02d".format(h, mm) else "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, mm)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost((260 * dp).toInt())
        setMeasuredDimension(w, (64 * dp).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val textW = 40 * dp
        val left = textW
        val right = w - textW
        val baseY = height - 18 * dp
        val rise = 40 * dp
        // a half-ellipse from sunrise (left) over the top to sunset (right)
        oval.set(left, baseY - rise, right, baseY + rise)
        c.drawLine(left - 8 * dp, baseY, right + 8 * dp, baseY, horizon)
        c.drawArc(oval, 180f, 180f, false, track)
        val span = (day.sunset - day.sunrise).coerceAtLeast(1)
        val t = (minute - day.sunrise).toFloat() / span
        val dayNow = t in 0f..1f
        if (dayNow) c.drawArc(oval, 180f, 180f * t, false, lit)
        // the sun: on the arc by day; by night, just under the horizon on the side it rises from
        val (sx, sy) = if (dayNow) {
            val a = Math.PI * (1 - t)
            (oval.centerX() + oval.width() / 2 * cos(a).toFloat()) to (baseY - rise * sin(a).toFloat())
        } else {
            left to (baseY + 7 * dp)
        }
        if (dayNow) c.drawCircle(sx, sy, 9 * dp, glow)
        c.drawCircle(sx, sy, (if (dayNow) 4.5f else 3f) * dp, sun)
        val ty = baseY + 14 * dp
        c.drawText(fmt(day.sunrise), left - 6 * dp, ty, label)
        c.drawText(fmt(day.sunset), right + 6 * dp, ty, label)
    }
}

/** A typeface for part of a text (android.text.style.TypefaceSpan(Typeface) needs Android 9). */
class TypefaceCompatSpan(private val tf: Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}
