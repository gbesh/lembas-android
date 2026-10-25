package com.middleearth.launcher

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Frosted glass behind Lembas's sheets and menus (Android 12+), the way Android's own panels
 * look. Only when the phone allows it: battery saver and the "reduce blur" setting turn it off.
 */
object Blur {
    fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
        ctx.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

    /** Blur what's behind a window (a dialog or popup); returns true if it did. */
    fun behind(ctx: Context, lp: WindowManager.LayoutParams, radiusPx: Int): Boolean {
        if (!available(ctx)) return false
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = radiusPx
        return true
    }
}
