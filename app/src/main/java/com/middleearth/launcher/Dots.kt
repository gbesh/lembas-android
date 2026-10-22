package com.middleearth.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification dots: a small dot in the look's colour on an app's icon while it has
 * notifications waiting (dock, Lately and the Red Book list). Needs notification access, which
 * you turn on from the settings sheet; Lembas only counts which apps have notifications and
 * never reads what they say.
 */
object Dots {
    const val PREF_ON = "dots"
    private val packages = ConcurrentHashMap.newKeySet<String>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var color = 0xFFD9BC7A.toInt()

    fun component(ctx: Context) = ComponentName(ctx, DotsService::class.java)

    fun accessGranted(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(component(ctx).flattenToString()) == true

    fun enabled(ctx: Context) = accessGranted(ctx) && ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, true)

    /**
     * Turn dots on. With the whole-phone permission Lembas can switch on its own notification
     * access (you asked for it by turning dots on); otherwise this opens the system screen.
     */
    fun turnOn(ctx: Context): Boolean {
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, true).apply()
        if (accessGranted(ctx)) return true
        if (SystemTheme.permitted(ctx)) try {
            val cr = ctx.contentResolver
            val now = Settings.Secure.getString(cr, "enabled_notification_listeners").orEmpty()
            val me = component(ctx).flattenToString()
            Settings.Secure.putString(cr, "enabled_notification_listeners", if (now.isEmpty()) me else "$now:$me")
            return true
        } catch (_: Throwable) { }
        return false
    }

    fun turnOff(ctx: Context) {
        ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, false).apply()
        packages.clear()
        notifyChanged()
    }

    fun has(pkg: String) = pkg in packages

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    internal fun set(ctx: Context, active: Array<StatusBarNotification>?) {
        val on = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, true)
        val now = if (!on || active == null) emptySet() else active.asSequence()
            .filter { !it.isOngoing && it.isClearable && it.packageName != ctx.packageName }
            .filter { (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 || it.notification.group == null }
            .map { it.packageName }.toSet()
        if (now == packages) return
        packages.clear(); packages.addAll(now)
        notifyChanged()
    }

    private fun notifyChanged() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Put (or clear) the dot on an icon view. */
    fun apply(view: ImageView, pkg: String) {
        val want = has(pkg)
        val d = view.foreground as? DotDrawable
        if (want && d == null) view.foreground = DotDrawable(view.resources.displayMetrics.density)
        else if (!want && d != null) view.foreground = null
        (view.foreground as? DotDrawable)?.let { if (it.color != color) { it.color = color; it.invalidateSelf() } }
    }

    class DotDrawable(private val dp: Float) : Drawable() {
        var color = Dots.color
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCC000000.toInt() }
        override fun draw(c: Canvas) {
            val b = bounds
            val r = (b.width() * 0.11f).coerceIn(3.5f * dp, 6f * dp)
            val cx = b.right - r * 1.5f
            val cy = b.top + r * 1.5f
            fill.color = color
            ring.strokeWidth = 1.5f * dp
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, ring)
        }
        override fun setAlpha(alpha: Int) { fill.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

class DotsService : NotificationListenerService() {
    private fun refresh() = try { Dots.set(this, activeNotifications) } catch (_: Throwable) { }
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() { Dots.set(this, null) }
}
