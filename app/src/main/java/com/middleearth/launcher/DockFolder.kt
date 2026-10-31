package com.middleearth.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.ImageView

/**
 * A folder in the dock: a small pouch holding a few apps, shown as their icons in miniature.
 * Stored in the dock's list as "folder:<name>|key|key|…".
 */
object DockFolder {
    class Folder(val name: String, val keys: List<String>)

    fun isFolder(key: String) = key.startsWith("folder:")
    fun make(name: String, keys: List<String>) = "folder:" + name.replace("|", "/").replace("\n", " ") + "|" + keys.joinToString("|")
    fun parse(key: String): Folder {
        val parts = key.removePrefix("folder:").split('|')
        return Folder(parts.first(), parts.drop(1).filter { it.isNotEmpty() })
    }

    /** The pouch: the look's dock colour with up to four of its apps' icons, drawn as they arrive. */
    fun icon(iv: ImageView, apps: List<AppEntry>, spec: IconCache.Spec, icons: IconCache, size: Int, look: Look) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (look.dockFillTop != 0) look.dockFillTop else if (look.darkScene) 0x33FFFFFF else 0x40FFFFFF }
        val r = size * .28f
        c.drawRoundRect(RectF(size * .04f, size * .04f, size * .96f, size * .96f), r, r, p)
        if (look.dockStroke != 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = size * .03f; p.color = look.dockStroke
            c.drawRoundRect(RectF(size * .06f, size * .06f, size * .94f, size * .94f), r, r, p)
        }
        iv.setImageBitmap(bmp)
        val cell = size * .36f
        val pad = size * .12f
        apps.take(4).forEachIndexed { i, e ->
            val x = pad + (i % 2) * (cell + size * .04f); val y = pad + (i / 2) * (cell + size * .04f)
            icons.render(e, spec) { small ->
                c.drawBitmap(small, null, RectF(x, y, x + cell, y + cell), Paint(Paint.FILTER_BITMAP_FLAG))
                iv.invalidate()
            }
        }
    }
}
