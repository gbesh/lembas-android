package com.middleearth.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Renders app icons once, in the background, into small bitmaps and keeps them in memory.
 * Scrolling the drawer then only draws ready-made bitmaps, which is as cheap as it gets.
 *
 * Themed icons: our own line-art glyph if we know the app, else the app's monochrome
 * (Android 13 themed-icon) layer, else its initial in the theme's typeface — all tinted
 * to the theme and placed in the theme's frame. "Original" mode draws the app's real icon.
 */
class IconCache private constructor(private val context: Context) {

    private val dpi = context.resources.displayMetrics.densityDpi
    private val dp = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Newest request first, so icons you're looking at right now come before preloading. */
    private class LifoQueue : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val exec = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LifoQueue()) { r ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }.apply { name = "lembas-icons"; isDaemon = true }
    }
    private val waiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    class Spec(
        val look: Look,
        val frame: IconFrame,
        val fillTop: Int,
        val fillBottom: Int,
        val stroke: Int,
        val sizePx: Int,
        val glyphFraction: Float,
        val themed: Boolean,
        /** An icon pack's package ("" for none): with [themed] off, its drawings replace the apps' own icons. */
        val pack: String = "",
        /** The glyphs' colour. */
        val ink: Int = look.glyphColor,
        /** Which drawing of the line-art glyphs: "g" (the originals), "gb" (bolder) or "gd" (bolder, two-tone). */
        val glyphSet: String = "g",
    ) {
        val id = "${look.id}#$frame#$fillTop#$fillBottom#$stroke#$sizePx#$glyphFraction#$themed#$pack#$ink#$glyphSet"

        companion object {
            fun dock(look: Look, sizePx: Int, themed: Boolean, pack: String = "") =
                Spec(look, look.dockFrame, look.dockFillTop, look.dockFillBottom, look.dockStroke, sizePx, 0.46f, themed, pack)

            /** The Red Book's icons, in one of [DrawerIcons]' styles. */
            fun drawer(look: Look, sizePx: Int, themed: Boolean, pack: String = "", style: String = DrawerIcons.DEFAULT) = when (style) {
                DrawerIcons.TONAL -> Spec(look, IconFrame.SQUIRCLE, look.drawerTone, look.drawerTone, 0, sizePx, 0.6f, themed, pack, look.drawerInk, "gb")
                DrawerIcons.DISC -> Spec(look, IconFrame.CIRCLE, look.drawerTone, look.drawerTone, 0, sizePx, 0.6f, themed, pack, look.drawerInk, "gd")
                DrawerIcons.BARE -> Spec(look, IconFrame.NONE, 0, 0, 0, sizePx, 0.8f, themed, pack, look.drawerInk, "g")
                else -> Spec(look, look.drawerFrame, look.drawerFillTop, look.drawerFillBottom, look.drawerStroke, sizePx,
                    if (look.drawerFrame == IconFrame.NONE) 0.8f else 0.52f, themed, pack)
            }
        }
    }

    fun bind(entry: AppEntry, spec: Spec, view: ImageView) {
        Dots.apply(view, entry.packageName)
        val key = entry.key + "#" + spec.id
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        request(entry, spec, key) { bmp -> if (view.tag == key) view.setImageBitmap(bmp) }
    }

    fun preload(entries: List<AppEntry>, spec: Spec) {
        for (e in entries) {
            val key = e.key + "#" + spec.id
            if (cache.get(key) == null) request(e, spec, key, null)
        }
    }

    fun evictPackage(pkg: String) {
        val prefix = "$pkg/"
        for (k in cache.snapshot().keys) if (k.startsWith(prefix)) cache.remove(k)
        exec.execute { dir.listFiles()?.forEach { if (it.name.startsWith("${pkg}_")) it.delete() } }
    }

    // ---------------------------------------------------------------- disk cache
    // Rendered icons are kept on disk too, so a cold start (after a reboot, or Android reclaiming
    // memory) shows the drawer at once instead of rendering every icon again. A file is named for
    // the app, the look and style, and when the app was last updated, so updates redraw by themselves.

    // one folder per Lembas version, so a Lembas update (new glyphs, new styles) starts afresh
    private val dir: java.io.File = run {
        val self = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        val name = "icons-" + self.toString(36)
        context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("icons") && it.name != name) it.deleteRecursively() }
        java.io.File(context.cacheDir, name).apply { mkdirs() }
    }

    private fun diskFile(entry: AppEntry, key: String): java.io.File {
        val stamp = try { java.io.File(entry.info.applicationInfo.sourceDir).lastModified() } catch (_: Throwable) { 0L }
        val h = (key + "#" + stamp).hashCode().toUInt().toString(36) + (key + stamp).length.toString(36)
        return java.io.File(dir, "${entry.packageName}_$h.png")
    }

    private fun loadDisk(f: java.io.File): Bitmap? = try {
        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path)?.also { it.prepareToDraw() } else null
    } catch (_: Throwable) { null }

    private fun saveDisk(f: java.io.File, b: Bitmap) {
        try {
            val tmp = java.io.File(f.path + ".tmp")
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
            trimDisk()
        } catch (_: Throwable) { }
    }

    private var saves = 0
    /** Keeps the folder under ~8 MB, dropping the oldest icons first. */
    private fun trimDisk() {
        if (++saves % 50 != 0) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 8L shl 20) break; total -= f.length(); f.delete() }
    }

    private fun request(entry: AppEntry, spec: Spec, key: String, cb: ((Bitmap) -> Unit)?) {
        val list = waiting[key]
        if (list != null) {
            if (cb != null) list.add(cb)
            return
        }
        val newList = ArrayList<(Bitmap) -> Unit>()
        if (cb != null) newList.add(cb)
        waiting[key] = newList
        exec.execute {
            val file = diskFile(entry, key)
            val bmp = loadDisk(file) ?: try { render(entry, spec).also { saveDisk(file, it) } } catch (t: Throwable) { null }
            main.post {
                val cbs = waiting.remove(key)
                if (bmp != null) {
                    cache.put(key, bmp)
                    cbs?.forEach { it(bmp) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render(entry: AppEntry, spec: Spec): Bitmap {
        val size = spec.sizePx
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        if (!spec.themed) {
            val fromPack = if (spec.pack.isEmpty()) null else IconPacks.drawable(context, spec.pack, entry.component, dpi)
                ?.let { context.packageManager.getUserBadgedIcon(it, entry.user) }
            val d = fromPack ?: entry.info.getBadgedIcon(dpi)
            val inset = if (spec.frame == IconFrame.NONE) 0 else (size * 0.08f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
            bmp.prepareToDraw()
            return bmp
        }

        drawFrame(c, spec)
        val g = size * spec.glyphFraction
        val left = (size - g) / 2f
        val top = (size - g) / 2f - if (spec.frame == IconFrame.ROUND_TILE) 1.25f * dp else 0f
        val color = spec.ink

        if (entry.glyph != 0) {
            val d = context.getDrawable(variant(entry.glyph, spec.glyphSet))!!.mutate()
            d.setTint(color)
            d.setBounds(left.toInt(), top.toInt(), (left + g).toInt(), (top + g).toInt())
            d.draw(c)
        } else if (!drawMonochrome(c, entry, size, g, color)) {
            drawLetter(c, entry, spec, size, g, color)
        }
        bmp.prepareToDraw()
        return bmp
    }

    private val variants = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The bolder (gb_) or two-tone (gd_) drawing of a glyph, falling back to the original. */
    private fun variant(glyph: Int, set: String): Int {
        if (set == "g") return glyph
        return variants.getOrPut("$set#$glyph") {
            val name = try { context.resources.getResourceEntryName(glyph) } catch (_: Exception) { return glyph }
            context.resources.getIdentifier(set + name.removePrefix("g"), "drawable", context.packageName).takeIf { it != 0 } ?: glyph
        }
    }

    private fun drawFrame(c: Canvas, spec: Spec) {
        val s = spec.sizePx.toFloat()
        val strokeW = dp.coerceAtLeast(1f)
        val half = strokeW / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (spec.frame) {
            IconFrame.NONE -> Unit
            IconFrame.TILE -> {
                val r = RectF(half, half, s - half, s - half)
                val radius = s * 0.28f
                p.shader = LinearGradient(0f, 0f, s * 0.4f, s, spec.fillTop, spec.fillBottom, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, radius, radius, p)
                p.shader = null
                if (spec.stroke != 0) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = strokeW
                    p.color = spec.stroke
                    c.drawRoundRect(r, radius, radius, p)
                }
            }
            IconFrame.ROUND_TILE -> {
                // Soft "pressed into the page" tile with a little shadow underneath.
                val lift = 2.5f * dp
                val radius = s * 0.34f
                if (spec.stroke != 0) {
                    p.color = spec.stroke
                    c.drawRoundRect(RectF(0f, lift, s, s), radius, radius, p)
                }
                p.color = spec.fillTop
                c.drawRoundRect(RectF(0f, 0f, s, s - lift), radius, radius, p)
            }
            IconFrame.SQUIRCLE -> {
                // a squircle: flat sides that round off smoothly, like Android's own themed icons
                val k = s / 38f
                val path = android.graphics.Path().apply {
                    moveTo(19 * k, 0f)
                    cubicTo(33 * k, 0f, 38 * k, 5 * k, 38 * k, 19 * k)
                    cubicTo(38 * k, 33 * k, 33 * k, 38 * k, 19 * k, 38 * k)
                    cubicTo(5 * k, 38 * k, 0f, 33 * k, 0f, 19 * k)
                    cubicTo(0f, 5 * k, 5 * k, 0f, 19 * k, 0f)
                    close()
                }
                p.color = spec.fillTop
                c.drawPath(path, p)
            }
            IconFrame.CIRCLE -> {
                if (spec.fillTop != 0) {
                    p.color = spec.fillTop
                    c.drawCircle(s / 2, s / 2, s / 2 - half, p)
                }
                if (spec.stroke != 0) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = strokeW
                    p.color = spec.stroke
                    c.drawCircle(s / 2, s / 2, s / 2 - half, p)
                }
            }
        }
    }

    /** Android 13+ apps can ship a one-colour icon layer for themed icons; use it when there is one. */
    private fun drawMonochrome(c: Canvas, entry: AppEntry, size: Int, g: Float, color: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val icon = entry.info.getIcon(dpi) as? AdaptiveIconDrawable ?: return false
        val mono: Drawable = icon.monochrome ?: return false
        // Adaptive layers are 108 units with the visible part in the middle 72.
        val full = g * 1.5f
        val l = (size - full) / 2f
        val d = mono.mutate()
        d.setTint(color)
        d.setBounds(l.toInt(), l.toInt(), (l + full).toInt(), (l + full).toInt())
        d.draw(c)
        return true
    }

    private fun drawLetter(c: Canvas, entry: AppEntry, spec: Spec, size: Int, g: Float, color: Int) {
        val ch = entry.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•'
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        p.color = color
        p.typeface = Fonts.get(context.assets, spec.look.displayFont)
        p.textSize = g * 1.05f
        p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(ch.toString(), size / 2f, baseline, p)
    }

    companion object {
        @Volatile private var instance: IconCache? = null
        fun get(context: Context): IconCache = instance ?: synchronized(this) {
            instance ?: IconCache(context.applicationContext).also { instance = it }
        }
    }
}
