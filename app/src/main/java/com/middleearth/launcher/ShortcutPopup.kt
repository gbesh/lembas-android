package com.middleearth.launcher

import android.app.Activity
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/** Reads an app's shortcuts (New tab, New incognito tab, Take a selfie…) from the system. */
object Shortcuts {
    fun forApp(apps: LauncherApps, e: AppEntry): List<ShortcutInfo> = try {
        if (!apps.hasShortcutHostPermission()) emptyList() else {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(e.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            (apps.getShortcuts(q, e.user) ?: emptyList())
                .filter { it.isEnabled && (it.activity == null || it.activity == e.component) }
                .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
                .take(4)
        }
    } catch (t: Throwable) {
        emptyList() // Not the default home app yet, or the work profile is locked.
    }

    fun start(apps: LauncherApps, s: ShortcutInfo, from: View?): Boolean = try {
        val bounds = from?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        apps.startShortcut(s, bounds, null)
        true
    } catch (t: Throwable) {
        false
    }
}

/** The themed menu that appears when you long-press an app. */
class ShortcutPopup(
    private val activity: Activity,
    private val look: Look,
    private val apps: LauncherApps,
) {
    class Action(val label: String, val icon: Int, val run: () -> Unit)

    private val dp = activity.resources.displayMetrics.density
    private var window: PopupWindow? = null
    /** Called once when the menu closes (the drawer un-highlights the app's row). */
    var onClosed: (() -> Unit)? = null

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    fun show(anchor: View, e: AppEntry, actions: List<Action>, onShortcut: () -> Unit) {
        dismiss()
        val shortcuts = Shortcuts.forApp(apps, e)
        val labelFont = Fonts.get(activity.assets, look.labelFont, 500)
        val bodyFont = Fonts.get(activity.assets, look.displayFont, 400)
        val ripple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(look.popupBg)
                setStroke(dp.roundToInt().coerceAtLeast(1), look.popupStroke)
            }
            clipToOutline = true
            elevation = 12 * dp
        }

        box.addView(TextView(activity).apply {
            text = e.label.uppercase()
            typeface = labelFont
            letterSpacing = 0.22f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(look.popupMuted)
            setSingleLine()
            setPadding(px(18), px(14), px(18), px(6))
        })

        // Waiting notifications first: tap one to open it, × to clear it
        val notes = Dots.notesFor(e.packageName).take(3)
        for (n in notes) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), px(6), px(6), px(6))
                setBackgroundResource(ripple)
                contentDescription = "${n.title}: ${n.text}"
                setOnClickListener { dismiss(); Dots.open(n); onShortcut() }
            }
            val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            if (n.title.isNotEmpty()) words.addView(TextView(activity).apply {
                text = n.title
                typeface = labelFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(look.popupText)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (n.text.isNotEmpty()) words.addView(TextView(activity).apply {
                text = n.text
                typeface = bodyFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(look.popupMuted)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.g_close)
                imageTintList = ColorStateList.valueOf(look.popupMuted)
                setPadding(px(10), px(10), px(10), px(10))
                contentDescription = "Clear"
                val br = TypedValue().also { activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }.resourceId
                setBackgroundResource(br)
                setOnClickListener { Haptics.feel(it, Haptics.Feel.REJECT); Dots.clear(n); row.visibility = View.GONE }
            }, LinearLayout.LayoutParams(px(40), px(40)))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (notes.isNotEmpty()) {
            box.addView(View(activity).apply { setBackgroundColor(look.popupStroke) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { topMargin = px(4); bottomMargin = px(4) })
        }

        for (s in shortcuts) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), 0, px(18), 0)
                setBackgroundResource(ripple)
                minimumHeight = px(48)
                contentDescription = s.shortLabel
                setOnClickListener {
                    Haptics.feel(anchor, Haptics.Feel.LIGHT)
                    dismiss()
                    if (Shortcuts.start(apps, s, anchor)) onShortcut()
                }
            }
            val icon = ImageView(activity)
            try {
                icon.setImageDrawable(apps.getShortcutIconDrawable(s, activity.resources.displayMetrics.densityDpi))
            } catch (t: Throwable) {
                icon.setImageResource(R.drawable.g_star)
                icon.imageTintList = ColorStateList.valueOf(look.accent)
            }
            row.addView(icon, LinearLayout.LayoutParams(px(22), px(22)))
            row.addView(TextView(activity).apply {
                text = s.shortLabel ?: s.longLabel ?: ""
                typeface = bodyFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(look.popupText)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)))
        }

        if (shortcuts.isNotEmpty()) {
            box.addView(View(activity).apply { setBackgroundColor(look.popupStroke) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                    topMargin = px(6); bottomMargin = px(2)
                })
        }

        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(2), px(6), px(6))
        }
        for (a in actions) {
            val b = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple)
                contentDescription = a.label
                setOnClickListener { dismiss(); a.run() }
            }
            b.addView(ImageView(activity).apply {
                setImageResource(a.icon)
                imageTintList = ColorStateList.valueOf(look.popupMuted)
            }, LinearLayout.LayoutParams(px(18), px(18)))
            b.addView(TextView(activity).apply {
                // one short word each, so five fit side by side
                text = SHORT[a.label] ?: a.label
                typeface = bodyFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (actions.size > 4) 12f else 13f)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(look.popupMuted)
                setSingleLine()
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(3)
            })
            bottom.addView(b, LinearLayout.LayoutParams(0, px(56), 1f))
        }
        box.addView(bottom)

        // Measure, then place above the icon (or below it if there's no room).
        val width = if (notes.isNotEmpty() || actions.size > 4) px(296) else px(256)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = box.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = activity.window.decorView.width
        val x = (loc[0] + anchor.width / 2 - width / 2).coerceIn(px(12), (screenW - width - px(12)).coerceAtLeast(px(12)))
        var y = loc[1] - height - px(10)
        var pivotY = height.toFloat()
        if (y < px(56)) {
            y = loc[1] + anchor.height + px(10)
            pivotY = 0f
        }

        val pw = PopupWindow(box, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 12 * dp
            animationStyle = 0
            val closed = onClosed   // this menu's own callback (a newer menu sets its own)
            onClosed = null
            setOnDismissListener { closed?.invoke() }
        }
        window = pw
        pw.showAtLocation(activity.window.decorView, Gravity.NO_GRAVITY, x, y)

        // Dim the screen behind the menu a little.
        try {
            val root = box.rootView
            val lp = root.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            lp.dimAmount = if (look.darkScene) 0.45f else 0.25f
            if (Blur.behind(activity, lp, px(20))) lp.dimAmount *= 0.6f
            activity.windowManager.updateViewLayout(root, lp)
        } catch (t: Throwable) { }

        box.pivotX = (loc[0] + anchor.width / 2 - x).toFloat()
        box.pivotY = pivotY
        box.alpha = 0f
        box.scaleX = 0.94f
        box.scaleY = 0.94f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun px(v: Int) = (v * dp).roundToInt()

    private companion object {
        val SHORT = mapOf("App info" to "Info", "Add to dock" to "Pin", "Hide from the list" to "Hide", "Show in the list" to "Show")
    }
}
