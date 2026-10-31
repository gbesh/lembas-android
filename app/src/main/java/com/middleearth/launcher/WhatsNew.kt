package com.middleearth.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build

/** After an update, once: a short card of what changed. Never on a fresh install. */
object WhatsNew {
    private const val PREF = "whats_new_seen"
    /** Bump with each release that has something worth telling. */
    private const val EDITION = 7000

    private val NOTES = listOf(
        "Sync with your PC and watch, through your own Google account (settings → Sync): change the look on one and the others follow.",
        "The scenes live a little more: the light changes through the day, sunrise and sunset glow, the wind moves the clouds and smoke, and the moon rises and sets when it really does.",
        "Tap the scene for sparkles, embers or a puff of cloud. Keep an eye out for visitors now and then.",
        "Hold the clock for today at a glance: the sun, the next look, your alarm and next event.",
        "Folders in the dock, recent searches, Lembas sounds, a widget for other home apps, and a quick-settings tile to hold the scene still.",
        "Everything is a little less yellow.",
    )

    fun maybeShow(a: Activity, theme: Int) {
        val p = a.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val seen = p.getInt(PREF, 0)
        if (seen >= EDITION) return
        p.edit().putInt(PREF, EDITION).apply()
        // a fresh install: nothing is "new" yet
        val info = try { a.packageManager.getPackageInfo(a.packageName, 0) } catch (_: Exception) { return }
        if (info.firstInstallTime == info.lastUpdateTime) return
        if (Build.VERSION.SDK_INT >= 17 && (a.isFinishing || a.isDestroyed)) return
        AlertDialog.Builder(a, theme)
            .setTitle("New in Lembas")
            .setMessage(NOTES.joinToString("\n\n") { "•  $it" })
            .setPositiveButton("Lovely", null)
            .show()
    }
}
