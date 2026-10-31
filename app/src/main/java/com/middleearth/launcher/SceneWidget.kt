package com.middleearth.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import java.time.LocalDateTime

/**
 * Lembas for any home app: a widget with the look's scene (following the sun), the time and the
 * Shire date. For people who'd rather keep their launcher. Refreshed at each change of look and
 * every half hour; the time is the system's own live clock.
 */
class SceneWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = draw(ctx, mgr, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        draw(ctx, mgr, intArrayOf(id))

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = try { mgr.getAppWidgetIds(ComponentName(ctx, SceneWidget::class.java)) } catch (_: Exception) { return }
            if (ids.isNotEmpty()) draw(ctx, mgr, ids)
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val app = ctx.applicationContext
            val look = Themes.current(app)
            val dm = app.resources.displayMetrics
            val text = Lore.homeText(look, LocalDateTime.now(), false)
            val ink = if (look.darkScene) 0xFFF4F1EA.toInt() else 0xFF22301F.toInt()
            for (id in ids) {
                val o = mgr.getAppWidgetOptions(id)
                val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320).coerceAtLeast(180)
                val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150).coerceAtLeast(90)
                // render at most ~900 px wide (a widget bitmap must stay small)
                val scale = minOf(dm.density, 900f / wDp)
                val bmp = try { SceneArt.still(app, look, (wDp * scale).toInt(), (hDp * scale).toInt(), scale) } catch (_: Throwable) { null }
                val v = RemoteViews(app.packageName, R.layout.widget_scene)
                if (bmp != null) v.setImageViewBitmap(R.id.widget_scene, bmp)
                v.setTextColor(R.id.widget_time, ink)
                v.setTextColor(R.id.widget_shire, ink)
                v.setTextViewText(R.id.widget_shire, text.shire)
                val open = PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName)
                    ?: Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                v.setOnClickPendingIntent(R.id.widget_root, open)
                mgr.updateAppWidget(id, v)
            }
        }
    }
}
