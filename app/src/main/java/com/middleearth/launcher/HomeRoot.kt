package com.middleearth.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root of the screen. Watches for vertical swipes anywhere:
 *  - swipe up on home → drag the app drawer up with your finger
 *  - swipe down in the drawer (when its list is at the top) → drag it closed
 *  - swipe down on home → notification shade
 *  - long-press on empty home space → settings
 *  - tap once then draw (or start a stroke sideways) on home → a rune shortcut
 * Touches on the letter strips and dock icons are left alone (see [Callbacks.touchBlocked]).
 * Taps still reach the dock and other children normally.
 */
class HomeRoot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    interface Callbacks {
        fun isDrawerOpen(): Boolean
        fun drawerCanScrollUp(): Boolean
        fun onDragStart()
        fun onDrag(dy: Float)
        fun onDragEnd(velocityY: Float)
        fun onPullDownOnHome()
        fun onLongPressHome()
        /** Two quick taps on empty space (Lembas locks the phone, if you turned that on). */
        fun onDoubleTapHome() {}
        /** One tap on empty space: the scene answers (sparkles, embers, a puff of cloud). */
        fun onTapHome(x: Float, y: Float) {}
        /** Drawing a rune on home: it started, moved, and ended with these points (x, y, x, y…). */
        fun onRuneStart(x: Float, y: Float) {}
        fun onRuneMove(x: Float, y: Float) {}
        fun onRuneEnd(points: FloatArray) {}
        /** True for touches that belong to something else: the letter strips, or a dock icon (swipe-up = shortcut). */
        fun touchBlocked(x: Float, y: Float): Boolean
    }

    var callbacks: Callbacks? = null

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val maxFling = config.scaledMaximumFlingVelocity.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var mode = NONE
    private var tracker: VelocityTracker? = null
    private var runeArmed = false
    private val rune = ArrayList<Float>()
    private val longPress = Runnable {
        if (mode == NONE) {
            mode = IGNORE
            Haptics.feel(this, Haptics.Feel.HOLD)
            callbacks?.onLongPressHome()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev, empty = false)

    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Reaches here when the touch landed on empty space (no child took it).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && callbacks?.isDrawerOpen() == false) {
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
        val tap = ev.actionMasked == MotionEvent.ACTION_UP && mode == NONE && callbacks?.isDrawerOpen() == false &&
            ev.eventTime - ev.downTime < ViewConfiguration.getTapTimeout() * 3
        track(ev, empty = true)
        if (tap) {
            val quick = ev.eventTime - lastTapAt < ViewConfiguration.getDoubleTapTimeout() &&
                abs(ev.x - lastTapX) < slop * 4 && abs(ev.y - lastTapY) < slop * 4
            if (quick) { lastTapAt = 0L; callbacks?.onDoubleTapHome() }
            else { lastTapAt = ev.eventTime; lastTapX = ev.x; lastTapY = ev.y; callbacks?.onTapHome(ev.x, ev.y) }
        }
        return true
    }

    private fun track(ev: MotionEvent, empty: Boolean): Boolean {
        val cb = callbacks ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                mode = if (cb.touchBlocked(ev.x, ev.y)) IGNORE else NONE
                // a touch right after a tap on empty home can become a rune
                runeArmed = mode == NONE && !cb.isDrawerOpen() && ev.eventTime - lastTapAt < 600L &&
                    abs(ev.x - lastTapX) < slop * 12 && abs(ev.y - lastTapY) < slop * 12
                if (android.util.Log.isLoggable("LembasTouch", android.util.Log.DEBUG)) android.util.Log.d("LembasTouch", "down ${ev.x},${ev.y} mode=$mode")
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (mode == NONE && (abs(dx) > slop || abs(dy) > slop)) {
                    removeCallbacks(longPress)
                    if (runeArmed || (empty && !cb.isDrawerOpen() && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f)) {
                        mode = RUNE
                        lastTapAt = 0L
                        parent?.requestDisallowInterceptTouchEvent(true)
                        rune.clear(); rune.add(startX); rune.add(startY)
                        cb.onRuneStart(startX, startY)
                    } else if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        val open = cb.isDrawerOpen()
                        mode = when {
                            !open && dy < 0 -> DRAG
                            !open && dy > 0 -> SHADE
                            open && dy > 0 && !cb.drawerCanScrollUp() -> DRAG
                            else -> IGNORE
                        }
                        when (mode) {
                            DRAG -> {
                                startY = ev.y
                                parent?.requestDisallowInterceptTouchEvent(true)
                                cb.onDragStart()
                            }
                            SHADE -> cb.onPullDownOnHome()
                        }
                    } else {
                        mode = IGNORE
                    }
                }
                if (android.util.Log.isLoggable("LembasTouch", android.util.Log.DEBUG)) android.util.Log.d("LembasTouch", "move ${ev.y} mode=$mode")
                if (mode == DRAG) cb.onDrag(ev.y - startY)
                if (mode == RUNE) {
                    for (h in 0 until ev.historySize) { rune.add(ev.getHistoricalX(h)); rune.add(ev.getHistoricalY(h)) }
                    rune.add(ev.x); rune.add(ev.y)
                    cb.onRuneMove(ev.x, ev.y)
                }
                return mode == DRAG || mode == SHADE || mode == RUNE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val t = tracker
                if (mode == DRAG && t != null) {
                    t.addMovement(ev)
                    t.computeCurrentVelocity(1000, maxFling)
                    cb.onDragEnd(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) 0f else t.yVelocity)
                }
                if (mode == RUNE) cb.onRuneEnd(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) FloatArray(0) else rune.toFloatArray())
                runeArmed = false
                mode = NONE
                tracker?.recycle()
                tracker = null
                return false
            }
        }
        return false
    }

    private companion object {
        const val NONE = 0
        const val DRAG = 1
        const val SHADE = 2
        const val IGNORE = 3
        const val RUNE = 4
    }
}
