package com.middleearth.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max

/**
 * A–Z strip down one edge of the app drawer. Drag along it to jump through the alphabet:
 * the letter under your thumb swells and bulges inward, its neighbours follow in a wave,
 * and you feel a small tick on each new letter. One sits on each side of the screen.
 */
class LetterRail @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    fun interface Listener { fun onLetter(letter: Char) }

    var listener: Listener? = null
    var onRight = false

    private val letters = ('A'..'Z').toList() + '#'
    private var present: Set<Char> = emptySet()
    private var current: Char = 'A'

    private var color = 0xFF888888.toInt()
    private var dim = 0x66888888
    private var accent = 0xFFFFFFFF.toInt()

    private val dp = resources.displayMetrics.density
    private val base = sp(10f)
    private val big = sp(22f)
    private val idle = sp(13f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var touchY = 0f
    private var strength = 0f // 0 = resting, 1 = finger down
    private var lastIndex = -1
    private var fade: ValueAnimator? = null
    private val exclusion = Rect()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun style(look: Look, typeface: Typeface) {
        color = look.railColor
        dim = look.railDim
        accent = look.accent
        paint.typeface = typeface
        invalidate()
    }

    fun setPresent(chars: Set<Char>) {
        present = chars
        invalidate()
    }

    fun setCurrent(c: Char) {
        if (c != current) {
            current = c
            if (strength == 0f) invalidate()
        }
    }

    private val top get() = paddingTop.toFloat()
    private val cell get() = (height - paddingTop - paddingBottom).toFloat() / letters.size

    override fun onLayout(changed: Boolean, left: Int, t: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, t, right, bottom)
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep the system back-swipe from grabbing drags that start on the strip.
            exclusion.set(0, 0, width, height)
            systemGestureExclusionRects = listOf(exclusion)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell
        if (c <= 0f) return
        val pad = 8 * dp
        paint.textAlign = if (onRight) Paint.Align.RIGHT else Paint.Align.LEFT
        for ((i, ch) in letters.withIndex()) {
            val cy = top + (i + 0.5f) * c
            val has = ch in present
            var size = base
            var shift = 0f
            var col = if (has) color else dim
            if (strength > 0f) {
                val d = abs(cy - touchY) / c
                val m = max(0f, 1f - d / 3.2f).let { it * it } * strength
                size = base + (big - base) * m
                shift = 18 * dp * m
                if (d < 0.5f && strength > 0.5f) col = accent
            } else if (ch == current) {
                size = idle
                col = accent
            }
            paint.textSize = size
            paint.color = col
            paint.isFakeBoldText = col == accent
            val fm = paint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            val x = if (onRight) width - pad - shift else pad + shift
            canvas.drawText(ch.toString(), x, baseline, paint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fade?.cancel()
                strength = 1f
                lastIndex = -1
                track(ev.y)
            }
            MotionEvent.ACTION_MOVE -> track(ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fade = ValueAnimator.ofFloat(strength, 0f).apply {
                    duration = 160
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { strength = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        return true
    }

    private fun track(y: Float) {
        touchY = y.coerceIn(top, height - paddingBottom.toFloat())
        val i = ((touchY - top) / cell).toInt().coerceIn(0, letters.size - 1)
        if (i != lastIndex) {
            lastIndex = i
            current = letters[i]
            // a crisp tick on letters that have apps, only the faintest tap on empty ones,
            // so you can feel your way down the alphabet
            Haptics.feel(this, if (letters[i] in present) Haptics.Feel.TICK else Haptics.Feel.LIGHT)
            listener?.onLetter(letters[i])
        }
        invalidate()
    }
}
