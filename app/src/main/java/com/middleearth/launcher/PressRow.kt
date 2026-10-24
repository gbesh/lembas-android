package com.middleearth.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout

/**
 * A row in the Red Book that lights up the moment your finger lands on it: a soft pill in the
 * look's tint fades in behind it and the icon swells a little, and it stays lit while the app's
 * menu is open. Lifting or scrolling away lets it fade back out.
 */
class PressRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var amount = 0f
    private var anim: ValueAnimator? = null
    private var target = 0f

    /** The highlight's colour (the look's tint). */
    var highlight: Int = 0x22000000
        set(v) { field = v; invalidate() }

    /** Set by the list as soon as a finger touches this row, before Android decides it's a tap. */
    var touching = false
        set(v) { if (field != v) { field = v; update() } }

    init { setWillNotDraw(false) }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        update()
    }

    private fun update() {
        val on = touching || isPressed || isActivated
        val to = if (on) 1f else 0f
        if (to == target) return
        target = to
        anim?.cancel()
        anim = ValueAnimator.ofFloat(amount, to).apply {
            duration = if (on) 90 else 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                amount = it.animatedValue as Float
                val s = 1f + 0.08f * amount
                getChildAt(0)?.let { icon -> icon.scaleX = s; icon.scaleY = s }
                invalidate()
            }
            start()
        }
    }

    /** Snap back with no animation (a recycled row showing another app). */
    fun reset() {
        anim?.cancel(); touching = false; target = 0f; amount = 0f
        getChildAt(0)?.let { it.scaleX = 1f; it.scaleY = 1f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (amount > 0f) {
            val inset = 14 * dp
            rect.set(paddingLeft - inset, 3 * dp, width - paddingRight + inset * 0.5f, height - 3 * dp)
            paint.color = highlight
            paint.alpha = (android.graphics.Color.alpha(highlight) * amount).toInt()
            val r = 18 * dp
            canvas.drawRoundRect(rect, r, r, paint)
        }
        super.onDraw(canvas)
    }

    companion object {
        /** The look's highlight: its accent, faint. */
        fun colorFor(look: Look): Int = (look.accent and 0x00FFFFFF) or (if (look.darkScene) 0x2E000000 else 0x24000000)

        /** The row inside a list item (the item itself for search rows). */
        fun of(item: View?): PressRow? = item as? PressRow ?: item?.findViewById(R.id.row)
    }
}
