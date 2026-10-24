package com.middleearth.launcher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Widgets on the home screen, under the clock: any app's widget (weather, calendar, notes…),
 * stacked one above another. Add them from the settings sheet; touch and hold one to make it
 * taller or shorter, move it, or remove it.
 */
class Widgets(private val activity: Activity, private val container: LinearLayout, private val dialogTheme: Int) {
    private val host = LembasHost(activity, HOST_ID)
    private val manager = AppWidgetManager.getInstance(activity)
    private val prefs = activity.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()
    // a widget being added (kept in prefs, in case the screen is rebuilt while Android asks)
    private var pendingId: Int
        get() = prefs.getInt("widget_pending", -1)
        set(v) { prefs.edit().putInt("widget_pending", v).apply() }
    private var pendingHeight: Int
        get() = prefs.getInt("widget_pending_h", 160)
        set(v) { prefs.edit().putInt("widget_pending_h", v).apply() }

    private class Saved(val id: Int, var heightDp: Int)

    private fun load(): MutableList<Saved> = try {
        val a = JSONArray(prefs.getString(PREF, "[]"))
        MutableList(a.length()) { a.getJSONObject(it).let { o -> Saved(o.getInt("id"), o.getInt("h")) } }
    } catch (_: Exception) { mutableListOf() }

    private fun save(list: List<Saved>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("h", s.heightDp))
        prefs.edit().putString(PREF, a.toString()).apply()
    }

    val count get() = load().size

    fun start() { try { host.startListening() } catch (_: Throwable) { } }
    fun stop() { try { host.stopListening() } catch (_: Throwable) { } }

    /** Put the saved widgets on the home screen. */
    fun show() {
        container.removeAllViews()
        val list = load()
        val kept = ArrayList<Saved>()
        for (s in list) {
            val info = manager.getAppWidgetInfo(s.id)
            if (info == null) { host.deleteAppWidgetId(s.id); continue }   // its app was uninstalled
            kept.add(s)
            val v = host.createView(activity, s.id, info) as LembasHostView
            v.onLongPress = { menu(s.id) }
            container.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(s.heightDp.toFloat())).apply { topMargin = px(10f) })
            sizeFor(v, s.heightDp)
        }
        if (kept.size != list.size) save(kept)
        container.visibility = if (kept.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sizeFor(v: AppWidgetHostView, heightDp: Int) {
        val widthDp = ((activity.resources.displayMetrics.widthPixels - px(64f)) / dp).roundToInt()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ adding one
    /** Lembas's own widget picker: every widget, by app, with its preview. */
    fun pick() {
        val providers = manager.getInstalledProvidersForProfile(Process.myUserHandle())
            .sortedWith(compareBy({ it.loadLabel(activity.packageManager).lowercase() }))
        val pm = activity.packageManager
        val list = ListView(activity).apply { divider = null }
        val dialog = AlertDialog.Builder(activity, dialogTheme).setTitle("Add a widget").setView(list)
            .setNegativeButton("Cancel", null).create()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = providers.size
            override fun getItem(position: Int) = providers[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val p = providers[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(20f), px(10f), px(20f), px(10f))
                    addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(px(72f), px(56f)))
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
                        addView(TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.7f })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16f) })
                }
                val img = row.getChildAt(0) as ImageView
                val words = row.getChildAt(1) as LinearLayout
                img.setImageDrawable(try { p.loadPreviewImage(activity, 0) ?: p.loadIcon(activity, 0) } catch (_: Throwable) { null })
                (words.getChildAt(0) as TextView).text = p.loadLabel(pm)
                val app = try { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() } catch (_: Exception) { "" }
                val cells = "${(p.minWidth / dp / 70).roundToInt().coerceAtLeast(1)} × ${(p.minHeight / dp / 70).roundToInt().coerceAtLeast(1)}"
                (words.getChildAt(1) as TextView).text = if (app.isEmpty()) cells else "$app · $cells"
                return row
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> dialog.dismiss(); add(providers[pos]) }
        dialog.show()
    }

    private fun add(p: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingHeight = (p.minHeight / dp).roundToInt().coerceIn(80, 360)
        if (manager.bindAppWidgetIdIfAllowed(id, p.provider)) configure(id)
        else try {
            // Android asks you once whether Lembas may show widgets
            @Suppress("DEPRECATION")
            activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider), REQUEST_BIND)
        } catch (_: Exception) { cancelPending() }
    }

    private fun configure(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return cancelPending()
        if (info.configure != null && (android.os.Build.VERSION.SDK_INT < 31 ||
                (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0)) {
            try { host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null); return }
            catch (_: Exception) { }
        }
        finishAdd(id)
    }

    private fun finishAdd(id: Int) {
        Haptics.feel(container, Haptics.Feel.CONFIRM)
        val list = load()
        list.add(Saved(id, pendingHeight.takeIf { it > 0 } ?: 160))
        save(list)
        pendingId = -1
        show()
    }

    private fun cancelPending() {
        if (pendingId != -1) host.deleteAppWidgetId(pendingId)
        pendingId = -1
    }

    /** From the activity's onActivityResult. Returns true if it was ours. */
    fun onResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false
        val id = pendingId
        if (id == -1) return true
        if (resultCode != Activity.RESULT_OK) { cancelPending(); return true }
        if (requestCode == REQUEST_BIND) configure(id) else finishAdd(id)
        return true
    }

    // ------------------------------------------------------------------ touch and hold
    private fun menu(id: Int) {
        val list = load()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val label = manager.getAppWidgetInfo(id)?.loadLabel(activity.packageManager) ?: "Widget"
        val options = ArrayList<Pair<String, () -> Unit>>()
        options.add("Taller" to { Haptics.feel(container, Haptics.Feel.TICK); list[i].heightDp = (list[i].heightDp + 40).coerceAtMost(520); save(list); show() })
        options.add("Shorter" to { Haptics.feel(container, Haptics.Feel.TICK); list[i].heightDp = (list[i].heightDp - 40).coerceAtLeast(60); save(list); show() })
        if (i > 0) options.add("Move up" to { list.add(i - 1, list.removeAt(i)); save(list); show() })
        if (i < list.size - 1) options.add("Move down" to { list.add(i + 1, list.removeAt(i)); save(list); show() })
        options.add("Remove" to { Haptics.feel(container, Haptics.Feel.REJECT); list.removeAt(i); save(list); host.deleteAppWidgetId(id); show() })
        AlertDialog.Builder(activity, dialogTheme).setTitle(label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    companion object {
        const val PREF = "widgets"
        const val HOST_ID = 0x1e5ba
        const val REQUEST_BIND = 0x77a
        const val REQUEST_CONFIGURE = 0x77b
    }
}

/** Our widget host, so each widget's view can notice a touch-and-hold. */
class LembasHost(ctx: Context, id: Int) : AppWidgetHost(ctx, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LembasHostView(context)
}

/** A widget that hands a touch-and-hold to Lembas (widgets swallow ordinary long-presses). */
class LembasHostView(ctx: Context) : AppWidgetHostView(ctx) {
    var onLongPress: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val press = Runnable {
        fired = true
        Haptics.feel(this, Haptics.Feel.HOLD)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; fired = false
                postDelayed(press, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(press)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(press)
        }
        return fired   // after a touch-and-hold, the widget doesn't also get a tap
    }
}
