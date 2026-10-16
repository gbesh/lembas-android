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
    ) {
        val id = "${look.id}#$frame#$fillTop#$fillBottom#$stroke#$sizePx#$glyphFraction#$themed"

        companion object {
            fun dock(look: Look, sizePx: Int, themed: Boolean) =
                Spec(look, look.dockFrame, look.dockFillTop, look.dockFillBottom, look.dockStroke, sizePx, 0.46f, themed)

            fun drawer(look: Look, sizePx: Int, themed: Boolean) =
                Spec(look, look.drawerFrame, look.drawerFillTop, look.drawerFillBottom, look.drawerStroke, sizePx,
                    if (look.drawerFrame == IconFrame.NONE) 0.8f else 0.52f, themed)
        }
    }

    fun bind(entry: AppEntry, spec: Spec, view: ImageView) {
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
            val bmp = try { render(entry, spec) } catch (t: Throwable) { null }
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
            val d = entry.info.getBadgedIcon(dpi)
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
        val color = spec.look.glyphColor

        if (entry.glyph != 0) {
            val d = context.getDrawable(entry.glyph)!!.mutate()
            d.setTint(color)
            d.setBounds(left.toInt(), top.toInt(), (left + g).toInt(), (top + g).toInt())
            d.draw(c)
        } else if (!drawMonochrome(c, entry, size, g, color)) {
            drawLetter(c, entry, spec, size, g, color)
        }
        bmp.prepareToDraw()
        return bmp
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
