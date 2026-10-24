package com.middleearth.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Icon packs: the standard format most launchers share (Nova, ADW, Niagara…), so any icon pack
 * from the Play Store works. The pack's appfilter maps each app to one of its drawings; apps the
 * pack doesn't cover keep their own icon.
 */
object IconPacks {
    const val PREF = "icon_pack"
    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme", "com.anddoes.launcher.THEME", "app.lawnchair.icons.THEMED_ICON")

    class Pack(val pkg: String, val label: String)

    /** Icon packs installed on the phone. */
    fun installed(ctx: Context): List<Pack> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, Pack>()
        for (a in ACTIONS) try {
            for (ri in pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA)) {
                val p = ri.activityInfo.packageName
                if (p !in found) found[p] = Pack(p, ri.activityInfo.applicationInfo.loadLabel(pm).toString())
            }
        } catch (_: Throwable) { }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun selected(ctx: Context): String = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getString(PREF, "") ?: ""

    fun select(ctx: Context, pkg: String) {
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, pkg).apply()
        synchronized(this) { loaded = null }
    }

    private class Loaded(val pkg: String, val res: Resources, val map: Map<String, String>)
    @Volatile private var loaded: Loaded? = null

    private fun load(ctx: Context, pkg: String): Loaded? {
        loaded?.let { if (it.pkg == pkg) return it }
        synchronized(this) {
            loaded?.let { if (it.pkg == pkg) return it }
            val res = try { ctx.packageManager.getResourcesForApplication(pkg) } catch (_: Exception) { return null }
            val map = HashMap<String, String>()
            try {
                val id = res.getIdentifier("appfilter", "xml", pkg)
                val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
                    XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") }
                }
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && drawable != null) {
                            // "ComponentInfo{com.app/com.app.Main}" -> "com.app/com.app.Main"
                            val c = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                            map.putIfAbsent(c, drawable)
                        }
                    }
                    ev = parser.next()
                }
            } catch (_: Throwable) { }
            return Loaded(pkg, res, map).also { loaded = it }
        }
    }

    /** The pack's drawing for this app, or null if the pack doesn't have one. */
    fun drawable(ctx: Context, pkg: String, component: ComponentName, dpi: Int): Drawable? {
        val l = load(ctx, pkg) ?: return null
        val name = l.map[component.flattenToString()]
            ?: l.map[component.packageName + "/" + component.className.removePrefix(component.packageName)]
            ?: return null
        val id = l.res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: l.res.getIdentifier(name, "mipmap", pkg)
        if (id == 0) return null
        return try { l.res.getDrawableForDensity(id, dpi, null) } catch (_: Throwable) { null }
    }
}
