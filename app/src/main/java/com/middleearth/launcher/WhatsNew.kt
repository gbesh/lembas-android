package com.middleearth.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build

/** After an update, once: a short card of what changed. Never on a fresh install. */
object WhatsNew {
    private const val PREF = "whats_new_seen"
    /** Bump with each release that has something worth telling. */
    private const val EDITION = 6000

    private val NOTES = listOf(
        "New scenes, drawn to match Lembas on your PC and watch: the Shire's round door, the map by the fire, the lakeside cabin, and Rivendell's silver trees.",
        "They change with the seasons (blossom, autumn leaves, snow), and can show the weather outside: rain, snow, fog or cloud.",
        "Tilt depth: the scene shifts gently as you tilt the phone.",
        "Search does more: \"timer 10m\", \"alarm 7:30\", \"10 km in miles\", \"100 usd to eur\". It also forgives typos.",
        "Keep a look until the next change, then follow the sun again.",
        "Quiet hours, and backing up your settings to a file, in settings.",
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
