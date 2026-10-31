package com.middleearth.launcher

import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Live Updates on the home screen: whatever's happening right now (a call, directions, a ride or
 * delivery on its way, a timer), as a card under the clock with its progress and time. Uses the
 * Android 16+ "Live Update" notifications, and on any Android also ongoing calls, navigation and
 * progress. Tap it to go back to the app. Needs the same notification access as the dots.
 */
object Live {
    const val PREF = "live"

    class Update(
        val key: String, val pkg: String, val title: String, val text: String,
        /** Android 16's short "critical" text (e.g. "4 min"), if the app gave one. */
        val chip: String,
        val progress: Int, val max: Int, val indeterminate: Boolean,
        /** Wall-clock time a call or timer counts from (0 = none). */
        val countFrom: Long, val countDown: Boolean,
        val icon: android.graphics.drawable.Icon?, val open: android.app.PendingIntent?,
        val rank: Int,
    )

    @Volatile var current: Update? = null
        private set

    /** Re-read the phone's notifications now (after the setting changes). */
    fun refresh(ctx: Context) {
        val svc = Dots.service
        set(ctx, if (svc != null) try { svc.activeNotifications } catch (_: Throwable) { null } else null)
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, true)

    /** Pick the update worth showing from the phone's notifications. Returns true if it changed. */
    internal fun set(ctx: Context, active: Array<StatusBarNotification>?): Boolean {
        val best = if (active == null || !enabled(ctx)) null else active.asSequence()
            .filter { it.packageName != ctx.packageName }
            .mapNotNull { read(ctx, it) }
            .sortedWith(compareBy<Update> { it.rank }.thenByDescending { it.key })
            .firstOrNull()
        val old = current
        current = best
        fun sig(u: Update?) = u?.let { "${it.key}|${it.title}|${it.text}|${it.chip}|${it.progress}/${it.max}|${it.countFrom}" }
        return sig(old) != sig(best)
    }

    private fun read(ctx: Context, sbn: StatusBarNotification): Update? {
        val n = sbn.notification
        val ex = n.extras
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null   // the music player shows those
        val promoted = FLAG_PROMOTED_ONGOING != 0 && (n.flags and FLAG_PROMOTED_ONGOING) != 0
        val ongoing = sbn.isOngoing
        val rank = when {
            n.category == Notification.CATEGORY_CALL && ongoing -> 0
            n.category == Notification.CATEGORY_NAVIGATION && ongoing -> 1
            promoted -> 2
            ongoing && n.category == Notification.CATEGORY_STOPWATCH -> 3
            ongoing && n.category == Notification.CATEGORY_PROGRESS && ex.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 -> 4
            else -> return null
        }
        var progress = ex.getInt(Notification.EXTRA_PROGRESS)
        var max = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
        // Android 16's ProgressStyle (rides, deliveries) keeps its progress in the style
        if (android.os.Build.VERSION.SDK_INT >= 36 && max == 0) try {
            val style = Notification.Builder.recoverBuilder(ctx, n).style
            if (style != null && style.javaClass.simpleName == "ProgressStyle") {
                progress = style.javaClass.getMethod("getProgress").invoke(style) as Int
                max = style.javaClass.getMethod("getProgressMax").invoke(style) as Int
            }
        } catch (_: Throwable) { }
        val chip = try {
            (n.javaClass.getMethod("getShortCriticalText").invoke(n) as? CharSequence)?.toString().orEmpty()
        } catch (_: Throwable) { "" }
        val counts = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: "").toString()
        if (title.isEmpty() && text.isEmpty()) return null
        return Update(sbn.key, sbn.packageName, title, text, chip, progress, max,
            ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            if (counts) n.`when` else 0L, ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            n.smallIcon, n.contentIntent, rank)
    }

    /** Notification.FLAG_PROMOTED_ONGOING (Android 16), looked up so older phones skip it. */
    private val FLAG_PROMOTED_ONGOING: Int by lazy {
        try { Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null) } catch (_: Throwable) { 0 }
    }
}

/** The Live Update card under the clock. */
class LiveCard(ctx: Context) : LinearLayout(ctx) {
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()
    private val icon = ImageView(ctx)
    private val title = TextView(ctx)
    private val text = TextView(ctx)
    private val chip = TextView(ctx)
    private val timer = Chronometer(ctx)
    private val bar = Bar()
    private var accent = 0xFFC4B48F.toInt()
    private var shown: Live.Update? = null

    init {
        orientation = VERTICAL
        setPadding(px(12f), px(10f), px(14f), px(10f))
        visibility = GONE
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        icon.layoutParams = LayoutParams(px(36f), px(36f))
        icon.setPadding(px(8f), px(8f), px(8f), px(8f))
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) }
            addView(title); addView(text)
        }
        for (t in listOf(title, text)) { t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        for (c in listOf<TextView>(chip, timer)) {
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            c.setPadding(px(10f), px(4f), px(10f), px(4f))
            c.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) }
        }
        row.addView(icon); row.addView(col); row.addView(chip); row.addView(timer)
        addView(row)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, px(4f)).apply { topMargin = px(10f) })
        setOnClickListener {
            Haptics.feel(it, Haptics.Feel.LIGHT)
            try { shown?.open?.send() } catch (_: Exception) { }
        }
    }

    fun style(ink: Int, soft: Int, accent: Int, fill: Int, font: Typeface, sans: Typeface) {
        this.accent = accent
        title.setTextColor(ink); title.typeface = font
        text.setTextColor(soft); text.typeface = sans
        for (c in listOf<TextView>(chip, timer)) {
            c.setTextColor(accent); c.typeface = sans
            c.background = GradientDrawable().apply { cornerRadius = 99f; setColor((accent and 0x00FFFFFF) or 0x26000000) }
        }
        icon.imageTintList = ColorStateList.valueOf(accent)
        icon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((accent and 0x00FFFFFF) or 0x26000000) }
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(fill) }
        bar.invalidate()
        show()
    }

    /** Show the current update (or hide). */
    fun show() {
        val u = Live.current
        shown = u
        if (u == null) { timer.stop(); visibility = GONE; return }
        title.text = u.title.ifEmpty { u.text }
        text.text = if (u.title.isEmpty()) "" else u.text
        text.visibility = if (text.text.isEmpty()) GONE else VISIBLE
        icon.setImageDrawable(try { u.icon?.loadDrawable(context) } catch (_: Exception) { null })
        chip.text = u.chip
        chip.visibility = if (u.chip.isNotEmpty()) VISIBLE else GONE
        if (u.countFrom > 0 && u.chip.isEmpty()) {
            timer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - u.countFrom)
            timer.isCountDown = u.countDown
            timer.visibility = VISIBLE
            timer.start()
        } else { timer.stop(); timer.visibility = GONE }
        bar.visibility = if (u.max > 0 || u.indeterminate) VISIBLE else GONE
        bar.invalidate()
        contentDescription = listOf(u.title, u.text, u.chip).filter { it.isNotEmpty() }.joinToString(", ")
        visibility = VISIBLE
    }

    /** A thin rounded progress line in the look's colour. */
    private inner class Bar : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val u = shown ?: return
            val h = height.toFloat()
            p.color = (accent and 0x00FFFFFF) or 0x33000000
            r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, p)
            val f = if (u.indeterminate || u.max <= 0) 0.35f else (u.progress.toFloat() / u.max).coerceIn(0f, 1f)
            p.color = accent
            r.set(0f, 0f, width * f, h); c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}
