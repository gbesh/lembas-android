package com.middleearth.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The settings sheet: slides up from the bottom in the look's own colours, with a preview of
 * each look (painted from the real scenes) to pick from, then the settings in groups. Tapping a
 * setting changes it in place.
 */
class SettingsSheet(
    private val activity: Activity,
    private val look: Look,
    private val choice: String,
    private val lookTimes: Map<String, String>,
    private val onPickLook: (String) -> Unit,
) {
    class Row(val title: String, val value: String, val action: () -> Boolean)  // action returns true to keep the sheet open
    class Section(val title: String, val rows: List<Row>)

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()
    private val dark = !look.lightBars
    private val ink = if (dark) 0xFFF2EEE4.toInt() else 0xFF1E1A14.toInt()
    private val soft = if (dark) 0xB3F2EEE4.toInt() else 0x991E1A14.toInt()
    private val surface = if (dark) 0x1FFFFFFF else 0x14000000
    private val accent = look.accent
    private val sans: Typeface = Fonts.get(activity.assets, "AlegreyaSans-Medium.ttf", 500)
    private val serif: Typeface = Fonts.variable(activity.assets, "Fraunces.ttf", "'wght' 600, 'opsz' 72, 'SOFT' 50")

    fun show(sections: List<Section>, rebuild: () -> Unit) {
        val dialog = Dialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(10f), px(20f), px(28f))
        }
        // the grab handle
        content.addView(View(activity).apply {
            background = GradientDrawable().apply { cornerRadius = 99f; setColor(soft) }
            layoutParams = LinearLayout.LayoutParams(px(36f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(14f) }
        })
        content.addView(heading("Look"))
        content.addView(previews(dialog))
        for (s in sections) {
            content.addView(heading(s.title))
            val group = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(surface) }
            }
            s.rows.forEachIndexed { i, r ->
                if (i > 0) group.addView(View(activity).apply {
                    setBackgroundColor(if (dark) 0x14FFFFFF else 0x10000000)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = px(16f) }
                })
                group.addView(row(r) {
                    Haptics.feel(content, Haptics.Feel.TICK)
                    val keep = r.action()
                    dialog.dismiss()
                    if (keep) rebuild()
                })
            }
            content.addView(group)
        }
        val scroll = ScrollView(activity).apply { addView(content); isVerticalScrollBarEnabled = false }
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(look.drawerTop, look.drawerBottom)).apply {
                cornerRadii = floatArrayOf(28 * dp, 28 * dp, 28 * dp, 28 * dp, 0f, 0f, 0f, 0f)
            }
            addView(scroll)
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82f).roundToInt())
            setWindowAnimations(android.R.style.Animation_InputMethod)   // slides up and back down
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        dialog.show()
        backPreview(dialog, frame)
    }

    /** Android 14+: the sheet sinks and shrinks as you swipe back, and rises again if you change your mind. */
    private fun backPreview(dialog: Dialog, frame: View) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val cb = object : android.window.OnBackAnimationCallback {
            override fun onBackProgressed(e: android.window.BackEvent) {
                val t = android.view.animation.DecelerateInterpolator().getInterpolation(e.progress)
                frame.translationY = t * 72 * dp
                frame.scaleX = 1f - 0.06f * t; frame.scaleY = frame.scaleX
            }
            override fun onBackCancelled() {
                frame.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            override fun onBackInvoked() { dialog.dismiss() }
        }
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
    }

    private fun heading(t: String) = TextView(activity).apply {
        text = t.uppercase()
        typeface = sans
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.12f
        setTextColor(accent)
        setPadding(px(6f), px(18f), 0, px(8f))
    }

    private fun row(r: Row, onTap: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(56f)
        setPadding(px(16f), px(10f), px(16f), px(10f))
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        foreground = activity.getDrawable(tv.resourceId)
        setOnClickListener { onTap() }
        contentDescription = "${r.title}, ${r.value}"
        addView(TextView(activity).apply {
            text = r.title
            typeface = sans
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ink)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (r.value.isNotEmpty()) addView(TextView(activity).apply {
            text = r.value
            typeface = sans
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(soft)
            gravity = Gravity.END
            setPadding(px(12f), 0, 0, 0)
        })
    }

    /** One card per look, painted from the real scene (in the background, so the sheet opens at once). */
    private fun previews(dialog: Dialog): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val ids = listOf(Look.ADAPTIVE, Look.SHIRE.id, Look.REDBOOK.id, Look.STARLIT.id, Look.ELVEN.id)
        val w = px(88f)
        val h = px(150f)
        val images = HashMap<String, ImageView>()
        for (id in ids) {
            val selected = id == choice
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(4f), 0, px(4f), 0)
                isClickable = true
                setOnClickListener { Haptics.feel(it, Haptics.Feel.CONFIRM); dialog.dismiss(); onPickLook(id) }
                contentDescription = if (id == Look.ADAPTIVE) "Follow the sun" else Look.byId(id).title
            }
            val img = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(surface)
                    if (selected) setStroke(px(2.5f), accent)
                }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                setPadding(if (selected) px(3f) else 0, if (selected) px(3f) else 0, if (selected) px(3f) else 0, if (selected) px(3f) else 0)
                layoutParams = LinearLayout.LayoutParams(w, h)
            }
            images[id] = img
            card.addView(img)
            card.addView(TextView(activity).apply {
                text = if (id == Look.ADAPTIVE) "Follow the sun" else Look.byId(id).title
                typeface = if (selected) serif else sans
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (selected) accent else ink)
                gravity = Gravity.CENTER
                setPadding(0, px(6f), 0, 0)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            lookTimes[id]?.let { t ->
                card.addView(TextView(activity).apply {
                    text = t
                    typeface = sans
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(soft)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            row.addView(card)
        }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val painter = Painter(dp * 0.45f)
            for (id in ids) {
                val l = if (id == Look.ADAPTIVE) Themes.adaptive() else Look.byId(id)
                val bmp: Bitmap = try { painter.paint(l, w, h) } catch (_: Throwable) { continue }
                main.post { images[id]?.setImageBitmap(bmp) }
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }
}
