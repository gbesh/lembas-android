package com.middleearth.launcher

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants as H
import android.view.View

/**
 * Lembas's haptics: one small vocabulary of feelings used everywhere, so the phone answers your
 * thumb the same way in every corner. Uses Android's own haptic effects (tuned per phone by the
 * maker), with gentler stand-ins on older Android. Follows the phone's "touch feedback" setting,
 * and can be turned off in Lembas's settings.
 */
object Haptics {
    const val PREF = "haptics"

    enum class Feel {
        /** A barely-there tap: opening an app, music controls. */
        LIGHT,
        /** A crisp tick: letters, settings rows, steps such as making a widget taller. */
        TICK,
        /** Something done: copied, pinned, hidden, a look chosen. */
        CONFIRM,
        /** Something removed or refused. */
        REJECT,
        /** The Red Book landing open or shut. */
        SNAP,
        /** Dragging past the point where letting go will open (or close) the Red Book… */
        THRESHOLD_ON,
        /** …and back again. */
        THRESHOLD_OFF,
        /** A switch turned on / off. */
        TOGGLE_ON, TOGGLE_OFF,
        /** Touch and hold. */
        HOLD,
    }

    @Volatile private var enabled: Boolean? = null

    fun enabled(ctx: Context): Boolean = enabled ?: ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF, true).also { enabled = it }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        enabled = on
    }

    fun feel(v: View?, f: Feel) {
        if (v == null || !enabled(v.context)) return
        val sdk = Build.VERSION.SDK_INT
        val c = when (f) {
            Feel.LIGHT -> H.KEYBOARD_TAP
            Feel.TICK -> H.CLOCK_TICK
            Feel.CONFIRM -> if (sdk >= 30) H.CONFIRM else H.VIRTUAL_KEY
            Feel.REJECT -> if (sdk >= 30) H.REJECT else H.LONG_PRESS
            Feel.SNAP -> if (sdk >= 30) H.GESTURE_END else H.VIRTUAL_KEY
            Feel.THRESHOLD_ON -> if (sdk >= 34) H.GESTURE_THRESHOLD_ACTIVATE else H.CLOCK_TICK
            Feel.THRESHOLD_OFF -> if (sdk >= 34) H.GESTURE_THRESHOLD_DEACTIVATE else H.CLOCK_TICK
            Feel.TOGGLE_ON -> if (sdk >= 34) H.TOGGLE_ON else H.CONTEXT_CLICK
            Feel.TOGGLE_OFF -> if (sdk >= 34) H.TOGGLE_OFF else H.CLOCK_TICK
            Feel.HOLD -> H.LONG_PRESS
        }
        v.performHapticFeedback(c)
    }
}
