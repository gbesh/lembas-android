package com.middleearth.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), HomeRoot.Callbacks, AppRepository.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var look: Look
    private lateinit var repo: AppRepository
    private lateinit var icons: IconCache
    private lateinit var adapter: AppAdapter
    private lateinit var dockSpec: IconCache.Spec
    private lateinit var drawerSpec: IconCache.Spec
    private lateinit var latelySpec: IconCache.Spec
    private lateinit var popup: ShortcutPopup

    private lateinit var root: HomeRoot
    private lateinit var scene: FrameLayout
    private lateinit var backdrop: BackdropView
    private lateinit var ambient: AmbientView
    private lateinit var home: LinearLayout
    private lateinit var topSpace: Space
    private lateinit var label: TextView
    private lateinit var clock: TextView
    private lateinit var shire: TextView
    private lateinit var date: TextView
    private lateinit var ornament: ImageView
    private lateinit var meal: TextView
    private lateinit var hint: ImageView
    private lateinit var dock: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerTitle: TextView
    private lateinit var drawerCount: TextView
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var search: EditText
    private lateinit var latelyBlock: LinearLayout
    private lateinit var latelyLabel: TextView
    private lateinit var lately: LinearLayout
    private lateinit var rule: View
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var railLeft: LetterRail
    private lateinit var railRight: LetterRail

    private val dp: Float get() = resources.displayMetrics.density
    private var progress = 0f
    private var dragBase = 0f
    private var drawerOpen = false
    private var animator: ValueAnimator? = null
    private var insetTop = 0
    private var tickRegistered = false
    private var resumed = false
    private var visibleNow = false
    private var entranceArmed = false
    private val baseClockSpacing = -0.01f

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateTexts()
    }

    /** Screen off → get ready; unlocked → play the entrance. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> if (visibleNow && progress == 0f && animationsOn()) {
                    entranceArmed = true
                    prepareEntrance()
                }
                Intent.ACTION_USER_PRESENT -> if (entranceArmed) playEntrance()
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(KeyguardManager::class.java)
                    if (entranceArmed && km?.isKeyguardLocked != true) playEntrance()
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Themes.PREFS, MODE_PRIVATE)
        Updater.schedule(this)
        look = Themes.current(this)
        setupWindow()
        setContentView(R.layout.activity_main)
        bindViews()
        applyBarAppearance()

        repo = AppRepository.get(this)
        icons = IconCache.get(this)
        popup = ShortcutPopup(this, look, repo.launcherApps)
        val themed = prefs.getBoolean(PREF_THEMED, true)
        dockSpec = IconCache.Spec.dock(look, (56 * dp).roundToInt(), themed)
        drawerSpec = IconCache.Spec.drawer(look, (38 * dp).roundToInt(), themed)
        latelySpec = IconCache.Spec.drawer(look, (52 * dp).roundToInt(), themed)

        applyLook()
        setupDrawer()

        root.callbacks = this
        root.setOnApplyWindowInsetsListener { _, insets -> handleInsets(insets) }
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                updateTopSpace()
                applyProgress(progress)
            }
        }

        clock.setOnClickListener { openAlarms() }
        clock.setOnLongClickListener { showSettings(); true }
        shire.setOnClickListener { openCalendar() }
        shire.setOnLongClickListener { showSettings(); true }
        drawerTitle.setOnLongClickListener { showSettings(); true }

        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, sf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, sf)

        repo.addListener(this)
        if (repo.loaded) onAppsChanged()
    }

    override fun onStart() {
        super.onStart()
        visibleNow = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!tickRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(tick, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(tick, filter)
            tickRegistered = true
        }
        updateTexts()
        updateAmbient()
        Updater.showPending(this)
        if (entranceArmed) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked != true) root.post { if (entranceArmed) playEntrance() }
            // In case the unlock broadcast never comes, never stay hidden.
            else root.postDelayed({ if (entranceArmed) playEntrance() }, 1200)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        popup.dismiss()
        updateAmbient()
        if (tickRegistered) {
            unregisterReceiver(tick)
            tickRegistered = false
        }
    }

    override fun onStop() {
        super.onStop()
        visibleNow = false
        // Coming back home should always show the home screen, not a half-open drawer.
        animator?.cancel()
        animator = null
        if (progress > 0f) settle(0f)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already home.
        popup.dismiss()
        if (progress > 0f) animateTo(0f)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (progress > 0f) animateTo(0f)
        // On the home screen, Back does nothing: this is home.
    }

    override fun onDestroy() {
        repo.removeListener(this)
        animator?.cancel()
        popup.dismiss()
        try { unregisterReceiver(screen) } catch (t: Throwable) { }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ apps

    override fun onAppsChanged() {
        adapter.setApps(repo.apps)
        val letters = adapter.presentLetters()
        railLeft.setPresent(letters)
        railRight.setPresent(letters)
        updateEmpty()
        drawerCount.text = "${repo.apps.size} apps"
        refreshDock()
        refreshLately()
        icons.preload(repo.apps, drawerSpec)
    }

    override fun onPackageChanged(packageName: String) = icons.evictPackage(packageName)

    private fun launch(e: AppEntry, from: View?) {
        val opts = from?.let {
            ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        val bounds = from?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        try {
            repo.launcherApps.startMainActivity(e.component, e.user, bounds, opts)
            recordLaunch(e)
        } catch (t: Exception) {
            Toast.makeText(this, "Couldn't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Long-press menu: the app's own shortcuts, then App info / dock / uninstall. */
    private fun showAppMenu(e: AppEntry, anchor: View) {
        val pinned = e.key in pinnedKeys()
        val actions = ArrayList<ShortcutPopup.Action>()
        actions.add(ShortcutPopup.Action("App info", R.drawable.g_info) {
            try { repo.launcherApps.startAppDetailsActivity(e.component, e.user, null, null) } catch (t: Exception) { }
        })
        actions.add(ShortcutPopup.Action(if (pinned) "Unpin" else "Add to dock", if (pinned) R.drawable.g_close else R.drawable.g_pin) {
            togglePin(e)
        })
        if (e.isMainUser) actions.add(ShortcutPopup.Action("Uninstall", R.drawable.g_trash) {
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName, null)))
            } catch (t: Exception) { }
        })
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        popup.show(anchor, e, actions) { recordLaunch(e) }
    }

    /** Swipe up on an icon (dock or Lately) → open its first shortcut, e.g. the browser's New tab. */
    private inner class SwipeUp(private val e: AppEntry) : View.OnTouchListener {
        private var downY = 0f
        private var downAt = 0L
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downAt = ev.eventTime }
                MotionEvent.ACTION_UP -> {
                    if (downY - ev.rawY > 36 * dp && ev.eventTime - downAt < 600) {
                        v.isPressed = false
                        v.cancelLongPress()
                        val first = Shortcuts.forApp(repo.launcherApps, e).firstOrNull()
                        if (first != null && Shortcuts.start(repo.launcherApps, first, v)) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            recordLaunch(e)
                        } else launch(e, v)
                        return true
                    }
                }
            }
            return false
        }
    }

    // ------------------------------------------------------------------ dock & Lately

    private fun pinnedKeys(): MutableList<String> =
        (prefs.getString(PREF_DOCK, "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()

    private fun savePinned(keys: List<String>) {
        prefs.edit().putString(PREF_DOCK, keys.joinToString("\n")).apply()
    }

    private fun togglePin(e: AppEntry) {
        val keys = pinnedKeys()
        if (e.key in keys) keys.remove(e.key)
        else if (keys.size >= MAX_DOCK) {
            Toast.makeText(this, "The dock holds $MAX_DOCK apps", Toast.LENGTH_SHORT).show()
            return
        } else keys.add(e.key)
        savePinned(keys)
        refreshDock()
    }

    /** First run: fill the dock with phone, messages, browser, camera and music if they exist. */
    private fun seedDock(): MutableList<String> {
        val wanted = intArrayOf(R.drawable.g_phone, R.drawable.g_message, R.drawable.g_compass, R.drawable.g_camera, R.drawable.g_music)
        val keys = ArrayList<String>()
        for (g in wanted) {
            repo.apps.firstOrNull { it.glyph == g && it.isMainUser }?.let { keys.add(it.key) }
        }
        return keys
    }

    private fun ripple(borderless: Boolean): Int = TypedValue().also {
        theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground, it, true,
        )
    }.resourceId

    private fun refreshDock() {
        dock.removeAllViews()
        if (!repo.loaded) return
        var keys = pinnedKeys()
        if (keys.isEmpty() && !prefs.getBoolean(PREF_DOCK_SEEDED, false)) {
            keys = seedDock()
            savePinned(keys)
            prefs.edit().putBoolean(PREF_DOCK_SEEDED, true).apply()
        }
        val entries = keys.mapNotNull { repo.byKey[it] }
        if (entries.isEmpty()) {
            dock.addView(TextView(this).apply {
                text = "Long-press an app in the Red Book to keep it here"
                setTextColor(look.dateColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Fonts.get(assets, look.displayItalicFont, 400, true)
                gravity = Gravity.CENTER
            })
            return
        }
        val size = (56 * dp).roundToInt()
        val margin = (6 * dp).roundToInt()
        val bg = ripple(true)
        for (e in entries) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
                setBackgroundResource(bg)
                contentDescription = e.label
                setOnClickListener { launch(e, it) }
                setOnLongClickListener { showAppMenu(e, it); true }
                setOnTouchListener(SwipeUp(e))
            }
            icons.bind(e, dockSpec, iv)
            dock.addView(iv)
        }
    }

    private fun recordLaunch(e: AppEntry) {
        val keys = (prefs.getString(PREF_RECENT, "") ?: "").split('\n').filter { it.isNotEmpty() && it != e.key }.toMutableList()
        keys.add(0, e.key)
        prefs.edit().putString(PREF_RECENT, keys.take(12).joinToString("\n")).apply()
        // Rebuilt when the drawer next opens, so nothing moves under your finger now.
        latelyStale = true
    }

    private var latelyStale = false

    private fun refreshLately() {
        latelyStale = false
        lately.removeAllViews()
        val entries = (prefs.getString(PREF_RECENT, "") ?: "").split('\n')
            .mapNotNull { repo.byKey[it] }.take(4)
        latelyBlock.visibility = if (entries.isEmpty() || adapter.isSearching) View.GONE else View.VISIBLE
        val size = (52 * dp).roundToInt()
        val bg = ripple(false)
        val small = Fonts.get(assets, look.labelFont, 500)
        for (e in entries) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(bg)
                contentDescription = e.label
                setOnClickListener { launch(e, it) }
                setOnLongClickListener { showAppMenu(e, it); true }
                setOnTouchListener(SwipeUp(e))
            }
            val iv = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            cell.addView(iv, LinearLayout.LayoutParams(size, size))
            cell.addView(TextView(this).apply {
                text = e.label
                typeface = small
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(look.rowText)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).roundToInt()
            })
            icons.bind(e, latelySpec, iv)
            lately.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Keep the four columns even when there are fewer recent apps.
        repeat(4 - entries.size) {
            lately.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    // ------------------------------------------------------------------ home text

    private fun updateTexts() {
        val now = LocalDateTime.now()
        // Adaptive: hand over to the next theme when its time comes (never mid-gesture).
        val want = Themes.current(this)
        if (want.id != look.id && progress == 0f && animator == null) {
            recreate()
            return
        }
        val is24 = DateFormat.is24HourFormat(this)
        val time = now.format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm"))
        val sb = SpannableStringBuilder(time)
        look.clockFirstDigitColor?.let { sb.setSpan(ForegroundColorSpan(it), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val suffix = look.clockSuffixColor
        if (suffix != null && !is24) {
            val start = sb.length
            sb.append(if (now.hour < 12) " am" else " pm")
            sb.setSpan(RelativeSizeSpan(0.28f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(suffix), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        clock.text = sb
        val t = Lore.homeText(look, now, prefs.getBoolean(PREF_MEALS, true))
        label.text = t.label
        shire.text = t.shire
        date.text = t.date
        meal.text = t.meal
        meal.visibility = if (t.meal.isEmpty()) View.GONE else View.VISIBLE
        backdrop.refresh() // repaints only when the day changed (new moon phase)
    }

    // ------------------------------------------------------------------ unlock entrance

    private val easeOut = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val sceneEase = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)

    private fun entranceViews(): List<Pair<View, Long>> = listOf(
        label to 120L, clock to 160L, shire to 240L, date to 280L,
        ornament to 300L, meal to 320L, hint to 300L, dock to 300L,
    )

    private fun prepareEntrance() {
        scene.animate().cancel()
        scene.alpha = 0f
        scene.scaleX = 1.08f
        scene.scaleY = 1.08f
        val rise = 16 * dp
        for ((v, _) in entranceViews()) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = rise
        }
    }

    private fun playEntrance() {
        entranceArmed = false
        if (scene.alpha == 1f && clock.alpha == 1f) prepareEntrance()
        scene.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550).setInterpolator(sceneEase).withLayer().start()
        for ((v, delay) in entranceViews()) {
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(450).setInterpolator(easeOut).start()
        }
        ValueAnimator.ofFloat(0.08f, baseClockSpacing).apply {
            startDelay = 160
            duration = 500
            interpolator = easeOut
            addUpdateListener { clock.letterSpacing = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ look

    private fun bindViews() {
        root = findViewById(R.id.root)
        scene = findViewById(R.id.scene)
        backdrop = findViewById(R.id.backdrop)
        ambient = findViewById(R.id.ambient)
        home = findViewById(R.id.home)
        topSpace = findViewById(R.id.topSpace)
        label = findViewById(R.id.label)
        clock = findViewById(R.id.clock)
        shire = findViewById(R.id.shire)
        date = findViewById(R.id.date)
        ornament = findViewById(R.id.ornament)
        meal = findViewById(R.id.meal)
        hint = findViewById(R.id.hint)
        dock = findViewById(R.id.dock)
        drawer = findViewById(R.id.drawer)
        drawerTitle = findViewById(R.id.drawerTitle)
        drawerCount = findViewById(R.id.drawerCount)
        searchBox = findViewById(R.id.searchBox)
        searchIcon = findViewById(R.id.searchIcon)
        search = findViewById(R.id.search)
        latelyBlock = findViewById(R.id.latelyBlock)
        latelyLabel = findViewById(R.id.latelyLabel)
        lately = findViewById(R.id.lately)
        rule = findViewById(R.id.rule)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        railLeft = findViewById(R.id.railLeft)
        railRight = findViewById(R.id.railRight)
    }

    private fun setupWindow() {
        window.setBackgroundDrawable(ColorDrawable(look.windowColor))
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    }

    @Suppress("DEPRECATION")
    private fun applyBarAppearance() {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (look.lightBars) mask else 0, mask)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (look.lightBars) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun handleInsets(insets: WindowInsets): WindowInsets {
        val top: Int
        val bottom: Int
        val ime: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = bars.top
            bottom = bars.bottom
            ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
            ime = bottom
        }
        insetTop = top
        home.setPadding(home.paddingLeft, top, home.paddingRight, (28 * dp).roundToInt() + bottom)
        drawer.setPadding(0, top, 0, max(bottom, ime))
        updateTopSpace()
        return insets
    }

    /** Places the clock block at the same height as in the design, whatever the screen. */
    private fun updateTopSpace() {
        val h = root.height
        if (h == 0) return
        val want = ((h * look.contentTopFraction) - insetTop).roundToInt().coerceAtLeast(0)
        val lp = topSpace.layoutParams
        if (lp.height != want) {
            lp.height = want
            topSpace.layoutParams = lp
        }
    }

    private fun applyLook() {
        backdrop.look = look
        ambient.look = look
        ambient.animationsOn = animationsOn()
        val display = Fonts.get(assets, look.displayFont, look.clockWeight)
        val displayRegular = Fonts.get(assets, look.displayFont, 400)
        val displayMedium = Fonts.get(assets, look.displayFont, 500)
        val italic = Fonts.get(assets, look.displayItalicFont, 400, true)
        val labelFont = Fonts.get(assets, look.labelFont, 500)

        fun TextView.style(tf: android.graphics.Typeface, sizeSp: Float, color: Int, spacing: Float = 0f) {
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            letterSpacing = spacing
            if (look.textShadow) setShadowLayer(14 * dp, 0f, 2 * dp, 0x99000000.toInt())
        }

        // Centre or left-align the clock block.
        val g = if (look.alignStart) Gravity.START else Gravity.CENTER_HORIZONTAL
        home.gravity = g
        for (v in listOf(label, clock, shire, date, meal, ornament)) {
            (v.layoutParams as LinearLayout.LayoutParams).gravity = g
            if (v is TextView) v.gravity = if (look.alignStart) Gravity.START else Gravity.CENTER
        }

        label.style(labelFont, look.labelSizeSp, look.labelColor, look.labelSpacing)
        clock.style(display, look.clockSizeSp, look.clockColor, baseClockSpacing)
        shire.style(italic, look.shireSizeSp, look.shireColor)
        if (look.dateUsesLabelFont) date.style(labelFont, look.dateSizeSp, look.dateColor, look.dateSpacing)
        else date.style(displayRegular, look.dateSizeSp, look.dateColor, look.dateSpacing)
        meal.style(italic, look.mealSizeSp, look.mealColor)
        (meal.layoutParams as LinearLayout.LayoutParams).topMargin = (look.mealTopDp * dp).roundToInt()
        if (look.mealPill != 0) {
            meal.background = GradientDrawable().apply { cornerRadius = 999f; setColor(look.mealPill) }
            meal.setPadding((10 * dp).roundToInt(), (6 * dp).roundToInt(), (14 * dp).roundToInt(), (6 * dp).roundToInt())
            val cup = getDrawable(R.drawable.g_tea)!!.mutate()
            cup.setTint(look.mealColor)
            val s = (18 * dp).roundToInt()
            cup.setBounds(0, 0, s, s)
            meal.setCompoundDrawablesRelative(cup, null, null, null)
            meal.compoundDrawablePadding = (8 * dp).roundToInt()
        }

        if (look.ornamentRes != 0) {
            ornament.visibility = View.VISIBLE
            ornament.setImageResource(look.ornamentRes)
        } else ornament.visibility = View.GONE

        hint.setImageResource(look.hintRes)
        hint.imageTintList = ColorStateList.valueOf(look.hintColor)

        // Drawer — same layout for every theme
        drawer.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(look.drawerTop, look.drawerBottom))
        drawerTitle.text = look.drawerTitle
        drawerTitle.typeface = if (look.titleUsesLabelFont) labelFont else displayMedium
        drawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.titleSizeSp)
        drawerTitle.setTextColor(look.titleColor)
        drawerTitle.letterSpacing = look.titleSpacing
        drawerCount.typeface = labelFont
        drawerCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        drawerCount.letterSpacing = 0.08f
        drawerCount.setTextColor(look.muted)

        val pad = (16 * dp).roundToInt()
        when (look.searchStyle) {
            SearchStyle.PILL -> {
                searchBox.background = GradientDrawable().apply {
                    cornerRadius = 23 * dp
                    setColor(look.searchFill)
                    setStroke(dp.roundToInt().coerceAtLeast(1), look.searchStroke)
                }
                searchBox.setPadding(pad, 0, pad, 0)
            }
            SearchStyle.FILLED -> {
                searchBox.background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(look.searchFill)
                }
                searchBox.setPadding(pad, 0, pad, 0)
            }
            SearchStyle.UNDERLINE -> {
                val line = LayerDrawable(arrayOf(ColorDrawable(look.searchStroke)))
                line.setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                line.setLayerHeight(0, dp.roundToInt().coerceAtLeast(1))
                searchBox.background = line
                searchBox.setPadding(0, 0, 0, 0)
            }
        }
        searchIcon.setImageResource(R.drawable.g_search)
        searchIcon.imageTintList = ColorStateList.valueOf(look.accent)
        search.typeface = italic
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        search.setTextColor(look.searchText)
        search.setHintTextColor(look.searchHint)

        latelyLabel.typeface = labelFont
        latelyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        latelyLabel.letterSpacing = 0.2f
        latelyLabel.setTextColor(look.muted)
        rule.setBackgroundColor(look.ruleColor)

        railLeft.onRight = false
        railRight.onRight = true
        railLeft.style(look, labelFont)
        railRight.style(look, labelFont)

        empty.typeface = italic
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        empty.setTextColor(look.searchHint)
    }

    // ------------------------------------------------------------------ drawer

    private fun setupDrawer() {
        adapter = AppAdapter(this, look, icons, drawerSpec)
        list.adapter = adapter
        list.setOnItemClickListener { _, v, pos, _ -> launch(adapter.getItem(pos), v.findViewById(R.id.icon)) }
        list.setOnItemLongClickListener { _, v, pos, _ -> showAppMenu(adapter.getItem(pos), v.findViewById(R.id.icon)); true }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (total == 0 || adapter.isSearching) return
                val c = adapter.letterAt(first)
                railLeft.setCurrent(c)
                railRight.setCurrent(c)
            }
        })

        val jump = LetterRail.Listener { c ->
            hideKeyboard()
            list.setSelection(adapter.positionForLetter(c))
            railLeft.setCurrent(c)
            railRight.setCurrent(c)
        }
        railLeft.listener = jump
        railRight.listener = jump

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setQuery(s?.toString() ?: "")
                list.setSelection(0)
                val searching = adapter.isSearching
                railLeft.visibility = if (searching) View.GONE else View.VISIBLE
                railRight.visibility = if (searching) View.GONE else View.VISIBLE
                latelyBlock.visibility = if (searching || lately.childCount == 0 || lately.getChildAt(0) is Space) View.GONE else View.VISIBLE
                updateEmpty()
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) adapter.first()?.let { launch(it, null) }
            enter
        }
    }

    private fun updateEmpty() {
        empty.visibility = if (repo.loaded && adapter.count == 0) View.VISIBLE else View.GONE
    }

    override fun isDrawerOpen() = drawerOpen

    override fun drawerCanScrollUp() = list.canScrollVertically(-1)

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val rl = IntArray(2)
        root.getLocationInWindow(rl)
        val vx = loc[0] - rl[0]
        val vy = loc[1] - rl[1]
        return x >= vx && x < vx + v.width && y >= vy && y < vy + v.height
    }

    override fun touchBlocked(x: Float, y: Float): Boolean {
        if (progress > 0f) return hit(railLeft, x, y) || hit(railRight, x, y)
        for (i in 0 until dock.childCount) if (hit(dock.getChildAt(i), x, y) && dock.getChildAt(i) is ImageView) return true
        return false
    }

    override fun onDragStart() {
        animator?.cancel()
        animator = null
        dragBase = progress
        if (latelyStale) refreshLately()
        setLayers(true)
        hideKeyboard()
        popup.dismiss()
    }

    override fun onDrag(dy: Float) {
        val h = root.height.takeIf { it > 0 } ?: return
        applyProgress((dragBase - dy / h).coerceIn(0f, 1f))
    }

    override fun onDragEnd(velocityY: Float) {
        val fling = 900 * dp
        val open = when {
            velocityY < -fling -> true
            velocityY > fling -> false
            else -> progress > 0.5f
        }
        animateTo(if (open) 1f else 0f, velocityY)
    }

    override fun onPullDownOnHome() {
        try {
            @Suppress("WrongConstant")
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (t: Throwable) {
            // Some phones don't allow it; nothing else to do.
        }
    }

    override fun onLongPressHome() = showSettings()

    private fun applyProgress(p: Float) {
        progress = p
        val h = root.height.toFloat()
        drawer.translationY = (1f - p) * h
        drawer.visibility = if (p > 0f) View.VISIBLE else View.INVISIBLE
        home.alpha = 1f - p
        home.translationY = -p * h * 0.06f
    }

    private fun setLayers(on: Boolean) {
        val type = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (drawer.layerType != type) drawer.setLayerType(type, null)
        if (home.layerType != type) home.setLayerType(type, null)
    }

    private fun animateTo(target: Float, velocity: Float = 0f) {
        animator?.cancel()
        val from = progress
        if (from == target) {
            settle(target)
            return
        }
        if (target > 0f && latelyStale) refreshLately()
        setLayers(true)
        val dist = abs(target - from)
        var duration = (110 + 220 * dist).toLong()
        if (abs(velocity) > 1f) {
            val px = dist * root.height
            duration = min(duration, max(120L, (px / abs(velocity) * 1000f * 1.8f).toLong()))
        }
        animator = ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        animator = null
                        settle(target)
                    }
                }
            })
            start()
        }
    }

    private fun settle(target: Float) {
        applyProgress(target)
        setLayers(false)
        drawerOpen = target >= 1f
        updateAmbient()
        if (drawerOpen) {
            if (prefs.getBoolean(PREF_KEYBOARD, false)) showKeyboard()
        } else {
            hideKeyboard()
            if (search.text.isNotEmpty()) search.setText("")
            list.setSelection(0)
        }
    }

    /** Animate the scene only while the home screen is actually showing. */
    private fun updateAmbient() {
        ambient.active = resumed && !drawerOpen
    }

    private fun showKeyboard() {
        search.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ------------------------------------------------------------------ settings

    private fun animationsOn() = prefs.getBoolean(Themes.PREF_ANIM, true)

    private fun dialogTheme() = if (look.lightBars) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    else android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun showSettings() {
        val choice = Themes.choice(this)
        val themeName = if (choice == Look.ADAPTIVE) "Adaptive (now ${look.title})" else Look.byId(choice).title
        val themed = prefs.getBoolean(PREF_THEMED, true)
        val meals = prefs.getBoolean(PREF_MEALS, true)
        val kbd = prefs.getBoolean(PREF_KEYBOARD, false)
        val anim = animationsOn()
        val items = arrayOf(
            "Theme: $themeName",
            "Animations: " + if (anim) "On" else "Off",
            "App icons: " + if (themed) "Themed line art" else "Original",
            "Hobbit mealtimes: " + if (meals) "On" else "Off",
            "Keyboard when opening the Red Book: " + if (kbd) "On" else "Off",
            "Match my phone to Lembas (live wallpaper)",
            "Make Lembas your home app",
            "Automatic updates: " + if (Updater.enabled(this)) "On" else "Off",
            "Check for updates now (version ${versionName()})",
        )
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Lembas")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemePicker()
                    1 -> {
                        prefs.edit().putBoolean(Themes.PREF_ANIM, !anim).apply()
                        ambient.animationsOn = !anim
                    }
                    2 -> {
                        prefs.edit().putBoolean(PREF_THEMED, !themed).apply()
                        recreate()
                    }
                    3 -> {
                        prefs.edit().putBoolean(PREF_MEALS, !meals).apply()
                        updateTexts()
                    }
                    4 -> prefs.edit().putBoolean(PREF_KEYBOARD, !kbd).apply()
                    5 -> setLiveWallpaper()
                    6 -> try {
                        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    } catch (t: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                    7 -> Updater.setEnabled(this, !Updater.enabled(this))
                    8 -> checkForUpdate()
                }
            }
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun checkForUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Lembas to install updates, then try again", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
            return
        }
        Toast.makeText(this, "Checking for a new Lembas...", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val r = Updater.run(app)
            root.post {
                val msg = when (r) {
                    Updater.Result.UP_TO_DATE -> "Lembas is up to date"
                    Updater.Result.INSTALLING -> "Installing the new Lembas..."
                    Updater.Result.FAILED -> "Couldn't reach GitHub; Lembas will try again tomorrow"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun setLiveWallpaper() {
        val component = ComponentName(this, LembasWallpaper::class.java)
        val info = WallpaperManager.getInstance(this).wallpaperInfo
        if (info != null && info.component == component) {
            Toast.makeText(this, "Lembas is already your wallpaper", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
        } catch (t: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (t2: Exception) {
                Toast.makeText(this, "This phone doesn't support live wallpapers", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThemePicker() {
        val ids = arrayOf(Look.ADAPTIVE, Look.SHIRE.id, Look.REDBOOK.id, Look.STARLIT.id, Look.ELVEN.id)
        val day = Sun.today()
        fun hm(m: Int): String {
            val t = java.time.LocalTime.of(m / 60, m % 60)
            return t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"))
        }
        val names = arrayOf(
            "Adaptive — follows the sun: Shire Morning ${hm(day.sunrise)}–${hm(day.noon)}, Red Book until ${hm(day.sunset)}, Starlit Night until 11pm, Elven Silver until 4am",
            "Shire Morning · morning light",
            "Red Book · afternoon",
            "Starlit Night · evening",
            "Elven Silver · deep night",
        )
        val current = ids.indexOf(Themes.choice(this)).coerceAtLeast(0)
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Theme")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.edit().putString(Themes.PREF_THEME, ids[which]).apply()
                dialog.dismiss()
                if (Themes.current(this).id != look.id) recreate()
            }
            .show()
    }

    private fun openAlarms() {
        try { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) } catch (t: Exception) { }
    }

    private fun openCalendar() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        } catch (t: Exception) { }
    }

    private companion object {
        const val PREF_THEMED = "themed_icons"
        const val PREF_MEALS = "meals"
        const val PREF_KEYBOARD = "keyboard"
        const val PREF_DOCK = "dock"
        const val PREF_DOCK_SEEDED = "dock_seeded"
        const val PREF_RECENT = "recent"
        const val MAX_DOCK = 5
    }
}
