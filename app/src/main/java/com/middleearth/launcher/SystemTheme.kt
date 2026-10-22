package com.middleearth.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * Themes the whole phone, not just Lembas, once Lembas has the "write secure settings" permission
 * (granted once, by Lembas itself over Wireless debugging, or from a computer with adb).
 *
 *  - Material You: Lembas hands Android each look's own colours (a neutral and an accent seed and a
 *    palette style) instead of letting Android guess them from the wallpaper. Quick settings,
 *    notifications, Settings, the keyboard and every Material You app take them, while the
 *    wallpaper itself can stay the animated Lembas live wallpaper.
 *  - Dark theme: light for Shire Morning and the Red Book, dark for Starlit Night and Elven Silver,
 *    switching at the same minute as the look.
 *
 * Without the permission nothing here runs, and the colours come from the wallpaper as before.
 */
object SystemTheme {
    const val PREF_ON = "system_theme"
    private const val PREF_PENDING_DARK = "system_theme_pending_dark"
    private const val KEY_OVERLAYS = "theme_customization_overlay_packages"
    private const val KEY_NIGHT = "ui_night_mode"

    /** Each look's colours for Android: the neutral (backgrounds, surfaces) and the accent seed, and the palette style. */
    private class Palette(val neutral: String, val accent: String, val style: String, val dark: Boolean)

    private val PALETTES = mapOf(
        Look.SHIRE.id to Palette("7E8F62", "6E9752", "TONAL_SPOT", dark = false),     // sage and green ink
        Look.REDBOOK.id to Palette("8A6A44", "9A2A1C", "TONAL_SPOT", dark = false),   // parchment and red ink
        Look.STARLIT.id to Palette("26335C", "C9A452", "TONAL_SPOT", dark = true),    // midnight blue and gold
        Look.ELVEN.id to Palette("56626A", "8FA6B2", "SPRITZ", dark = true),          // black-blue and silver
    )

    fun permitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** On by default once the permission is there; the settings sheet can turn it off. */
    fun enabled(ctx: Context): Boolean =
        permitted(ctx) && ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, on).apply()
        if (on) apply(ctx, Themes.current(ctx), interactive = true)
    }

    /**
     * Give Android the look's colours and light/dark theme. The palette changes straight away
     * (SystemUI watches the setting). Switching dark theme makes every app redraw, so if the
     * phone is in use and [interactive] is false, that waits for the next time the screen is off
     * or Lembas is opened, so it never flashes under you mid-task.
     */
    fun apply(ctx: Context, look: Look, interactive: Boolean) {
        if (!enabled(ctx) || Build.VERSION.SDK_INT < 31) return
        val p = PALETTES[look.id] ?: return
        try { setPalette(ctx, p) } catch (_: Throwable) { }
        val prefs = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (interactive || !screenOn) {
            try { setDark(ctx, p.dark) } catch (_: Throwable) { }
            prefs.edit().remove(PREF_PENDING_DARK).apply()
        } else {
            prefs.edit().putBoolean(PREF_PENDING_DARK, p.dark).apply()
        }
    }

    /** A dark-theme switch that waited for a good moment (screen off, or Lembas opened). */
    fun applyPending(ctx: Context) {
        val prefs = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_PENDING_DARK) || !enabled(ctx)) return
        val dark = prefs.getBoolean(PREF_PENDING_DARK, false)
        prefs.edit().remove(PREF_PENDING_DARK).apply()
        try { setDark(ctx, dark) } catch (_: Throwable) { }
    }

    private fun setPalette(ctx: Context, p: Palette) {
        val cr = ctx.contentResolver
        val current = Settings.Secure.getString(cr, KEY_OVERLAYS)
        val json = try { JSONObject(current ?: "{}") } catch (_: Exception) { JSONObject() }
        val same = json.optString("android.theme.customization.color_source") == "preset" &&
            json.optString("android.theme.customization.system_palette").equals(p.neutral, true) &&
            json.optString("android.theme.customization.accent_color").equals(p.accent, true) &&
            json.optString("android.theme.customization.theme_style") == p.style
        if (same) return
        // Keep anything else in there (font, icon shape) and replace only the colour choices.
        json.put("android.theme.customization.color_source", "preset")
        json.put("android.theme.customization.system_palette", p.neutral)
        json.put("android.theme.customization.accent_color", p.accent)
        json.put("android.theme.customization.theme_style", p.style)
        json.put("android.theme.customization.color_both", "1")
        json.remove("android.theme.customization.color_index")
        json.put("_applied_timestamp", System.currentTimeMillis())
        Settings.Secure.putString(cr, KEY_OVERLAYS, json.toString())
    }

    @SuppressLint("WrongConstant")
    private fun setDark(ctx: Context, dark: Boolean) {
        val ui = ctx.getSystemService(UiModeManager::class.java) ?: return
        val want = if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        if (ui.nightMode == want) return
        Settings.Secure.putInt(ctx.contentResolver, KEY_NIGHT, want)
        // The setting alone isn't picked up until the UI mode is refreshed: stepping into and
        // straight out of car mode makes Android re-read it, without leaving anything behind.
        try { ui.enableCarMode(0); ui.disableCarMode(0) } catch (_: Throwable) { }
    }

    /** Hand the phone back to Android: colours from the wallpaper again; the light/dark choice stays as it is. */
    fun restore(ctx: Context) {
        if (!permitted(ctx)) return
        try {
            val cr = ctx.contentResolver
            val json = JSONObject(Settings.Secure.getString(cr, KEY_OVERLAYS) ?: "{}")
            json.put("android.theme.customization.color_source", "home_wallpaper")
            json.put("_applied_timestamp", System.currentTimeMillis())
            Settings.Secure.putString(cr, KEY_OVERLAYS, json.toString())
        } catch (_: Throwable) { }
    }
}
