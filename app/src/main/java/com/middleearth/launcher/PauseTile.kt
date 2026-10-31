package com.middleearth.launcher

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A quick settings tile, "Hold the scene still": the home screen and the wallpaper stop moving for
 * an hour (for a meeting, a film, or to save battery). Tap again to let them move.
 */
class PauseTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val p = getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val until = if (Themes.paused(this)) 0L else System.currentTimeMillis() + 60 * 60_000L
        p.edit().putLong(Themes.PREF_PAUSE_UNTIL, until).apply()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = Themes.paused(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Hold the scene still"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "For an hour" else "Moving"
        t.updateTile()
    }
}
