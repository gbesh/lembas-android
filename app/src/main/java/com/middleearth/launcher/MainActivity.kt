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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
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
    private lateinit var backdrop: SceneView
    private lateinit var home: LinearLayout
    private lateinit var topSpace: Space
    private lateinit var spacer: View
    private lateinit var label: TextView
    private lateinit var clock: TextView
    private lateinit var shire: TextView
    private lateinit var date: TextView
    private lateinit var ornament: ImageView
    private lateinit var meal: TextView
    private lateinit var sunArc: SunArcView
    private lateinit var music: MusicCard
    private lateinit var live: LiveCard
    private lateinit var widgetBox: LinearLayout
    private lateinit var widgets: Widgets
    private lateinit var searchEngine: SearchEngine
    private lateinit var searchAdapter: SearchAdapter
    /** True while there's something typed in search (the list then shows results, not the A-Z). */
    private var searching = false
    private var modern = true
    private var hoursFont: android.graphics.Typeface? = null
    private var minutesFont: android.graphics.Typeface? = null
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
                Intent.ACTION_SCREEN_OFF -> {
                    SystemTheme.applyPending(this@MainActivity)   // a dark-theme switch that waited for the screen to go off
                    if (visibleNow && progress == 0f && animationsOn()) {
                        entranceArmed = true
                        prepareEntrance()
                    }
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
        val pack = if (themed) "" else IconPacks.selected(this)
        dockSpec = IconCache.Spec.dock(look, (56 * dp).roundToInt(), themed, pack)
        val drawerStyle = prefs.getString(DrawerIcons.PREF, DrawerIcons.DEFAULT) ?: DrawerIcons.DEFAULT
        drawerSpec = IconCache.Spec.drawer(look, (38 * dp).roundToInt(), themed, pack, drawerStyle)
        latelySpec = IconCache.Spec.drawer(look, (52 * dp).roundToInt(), themed, pack, drawerStyle)

        applyLook()
        setupDrawer()
        widgets = Widgets(this, widgetBox, dialogTheme())
        widgets.show()

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
        Dots.color = look.accent
        Dots.addListener(dotsChanged)
        if (intent?.action == ACTION_SEARCH) root.post { openSearch(intent.getStringExtra("query")) }
        if (fadeInNext) {
            fadeInNext = false
            entranceArmed = true
            prepareEntrance()
            root.post { if (entranceArmed) playEntrance() }
        }
    }

    override fun onStart() {
        super.onStart()
        visibleNow = true
        if (prefs.getBoolean(PREF_MUSIC, true)) music.start()
        widgets.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        root.postDelayed({ if (resumed) WhatsNew.maybeShow(this, dialogTheme()) }, 900)
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
        LookWallpaper.syncAsync(this, interactive = true)  // cheap when nothing changed
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
        music.stop()
        widgets.stop()
        // Coming back home should always show the home screen, not a half-open drawer.
        animator?.cancel()
        animator = null
        if (progress > 0f) settle(0f)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        popup.dismiss()
        if (intent.action == ACTION_SEARCH) { openSearch(intent.getStringExtra("query")); return }
        // Home button pressed while already home.
        if (progress > 0f) animateTo(0f)
    }

    /** Open the Red Book straight into search (the Search shortcut on Lembas's icon, or another app asking). */
    private fun openSearch(query: String?) {
        if (progress < 1f) animateTo(1f)
        root.postDelayed({
            if (!query.isNullOrEmpty()) { search.setText(query); search.setSelection(query.length) }
            showKeyboard()
        }, 350)
    }

    // ------------------------------------------------------------------ back gesture
    private var backCallback: Any? = null
    private var backRadius = 0f

    /**
     * Android 13+: while the Red Book is open, the back gesture closes it; on Android 14+ the Red
     * Book shrinks and follows your thumb as you swipe, so you can see it going (or change your mind).
     */
    private fun backGesture(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val d = onBackInvokedDispatcher
        (backCallback as? android.window.OnBackInvokedCallback)?.let { d.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (!on) return
        val cb: android.window.OnBackInvokedCallback = if (android.os.Build.VERSION.SDK_INT >= 34) {
            object : android.window.OnBackAnimationCallback {
                override fun onBackStarted(e: android.window.BackEvent) { setLayers(true); backPreview(0f, e.swipeEdge) }
                override fun onBackProgressed(e: android.window.BackEvent) { backPreview(e.progress, e.swipeEdge) }
                override fun onBackCancelled() { backRelease(false) }
                override fun onBackInvoked() { backRelease(true) }
            }
        } else android.window.OnBackInvokedCallback { animateTo(0f) }
        d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    private fun backPreview(p: Float, edge: Int) {
        val t = android.view.animation.DecelerateInterpolator().getInterpolation(p)
        val s = 1f - 0.1f * t
        drawer.scaleX = s; drawer.scaleY = s
        drawer.translationX = (if (edge == 0 /* from the left edge */) 1 else -1) * t * 24 * dp
        backRadius = t * 32 * dp
        if (drawer.outlineProvider !is BackOutline) {
            drawer.outlineProvider = BackOutline()
            drawer.clipToOutline = true
        }
        drawer.invalidateOutline()
        home.alpha = 0.35f * t
    }

    private inner class BackOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, backRadius)
        }
    }

    private fun backRelease(close: Boolean) {
        val sx = drawer.scaleX; val tx = drawer.translationX; val r = backRadius; val ha = home.alpha
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = if (close) 260 else 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val s = 1f - (1f - sx) * f
                drawer.scaleX = s; drawer.scaleY = s
                drawer.translationX = tx * f
                backRadius = r * f
                drawer.invalidateOutline()
                if (!close) home.alpha = ha * f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawer.scaleX = 1f; drawer.scaleY = 1f; drawer.translationX = 0f; backRadius = 0f
                    drawer.clipToOutline = false
                    drawer.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                }
            })
            start()
        }
        if (close) animateTo(0f, 0f)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (progress > 0f) animateTo(0f)
        // On the home screen, Back does nothing: this is home.
    }

    override fun onDestroy() {
        repo.removeListener(this)
        Dots.removeListener(dotsChanged)
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

    /** Notifications came or went: redraw the dots. */
    private val dotsChanged: () -> Unit = {
        if (::live.isInitialized) live.show()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        refreshDock()
        refreshLately()
    }

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
            Haptics.feel(from ?: root, Haptics.Feel.LIGHT)
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
        if (Bubbles.canBubble(e)) actions.add(ShortcutPopup.Action("Bubble", R.drawable.g_chat) {
            Haptics.feel(root, Haptics.Feel.CONFIRM)
            Bubbles.open(this, e)
        })
        actions.add(ShortcutPopup.Action("App info", R.drawable.g_info) {
            try { repo.launcherApps.startAppDetailsActivity(e.component, e.user, null, null) } catch (t: Exception) { }
        })
        actions.add(ShortcutPopup.Action(if (pinned) "Unpin" else "Add to dock", if (pinned) R.drawable.g_close else R.drawable.g_pin) {
            togglePin(e)
        })
        actions.add(ShortcutPopup.Action("Rename", R.drawable.g_notes) { renameApp(e) })
        val hidden = repo.isHidden(e)
        actions.add(ShortcutPopup.Action(if (hidden) "Show in the list" else "Hide from the list", R.drawable.g_shield) {
            repo.setHidden(e, !hidden)
            Toast.makeText(this, if (hidden) "${e.label} is back in the list" else "${e.label} is hidden. Search still finds it.", Toast.LENGTH_SHORT).show()
        })
        if (e.isMainUser) actions.add(ShortcutPopup.Action("Uninstall", R.drawable.g_trash) {
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.packageName, null)))
            } catch (t: Exception) { }
        })
        Haptics.feel(anchor, Haptics.Feel.HOLD)
        popup.show(anchor, e, actions) { recordLaunch(e) }
    }

    /** App icons: Lembas's line art, the apps' own icons, or any installed icon pack. */
    /** How the Red Book draws its icons: tonal squircle, soft disc, no frame or classic. */
    private fun chooseDrawerIcons() {
        val current = prefs.getString(DrawerIcons.PREF, DrawerIcons.DEFAULT)
        val styles = DrawerIcons.styles
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Red Book icons")
            .setSingleChoiceItems(styles.map { it.second }.toTypedArray(), styles.indexOfFirst { it.first == current }.coerceAtLeast(0)) { d, which ->
                d.dismiss()
                prefs.edit().putString(DrawerIcons.PREF, styles[which].first).apply()
                recreate()
            }
            .show()
    }

    private fun chooseIcons() {
        val packs = IconPacks.installed(this)
        val names = listOf("Line art (Lembas)", "Original icons") + packs.map { it.label } + "Get icon packs from the Play Store"
        val themed = prefs.getBoolean(PREF_THEMED, true)
        val current = when {
            themed -> 0
            IconPacks.selected(this).isEmpty() -> 1
            else -> 2 + packs.indexOfFirst { it.pkg == IconPacks.selected(this) }.coerceAtLeast(0)
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("App icons")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                d.dismiss()
                when {
                    which == 0 -> { prefs.edit().putBoolean(PREF_THEMED, true).apply(); IconPacks.select(this, "") }
                    which == 1 -> { prefs.edit().putBoolean(PREF_THEMED, false).apply(); IconPacks.select(this, "") }
                    which == names.size - 1 -> { searchEngine.playStore("icon pack"); return@setSingleChoiceItems }
                    else -> { prefs.edit().putBoolean(PREF_THEMED, false).apply(); IconPacks.select(this, packs[which - 2].pkg) }
                }
                recreate()
            }
            .show()
    }

    /** Hidden apps: tap one to put it back in the list. */
    private fun showHiddenApps() {
        val hidden = repo.hidden
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hidden apps. Touch and hold an app to hide it.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Hidden apps")
            .setItems(hidden.map { "Show ${it.label}" }.toTypedArray()) { _, which -> repo.setHidden(hidden[which], false) }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun renameApp(e: AppEntry) {
        val field = EditText(this).apply {
            setText(e.label)
            setSelection(text.length)
            hint = e.originalLabel
            setSingleLine()
        }
        val pad = (20 * dp).roundToInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(field) }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Rename ${e.originalLabel}")
            .setView(box)
            .setPositiveButton("Rename") { _, _ -> repo.rename(e, field.text.toString()) }
            .setNeutralButton("Original name") { _, _ -> repo.rename(e, "") }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    /** Swipe up on an icon (dock or Lately) → open its first shortcut, e.g. the browser's New tab. */
    private inner class SwipeUp(private val e: AppEntry) : View.OnTouchListener {
        private fun release(icon: View) {
            if (icon.scaleX == 1f) return
            icon.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }
        private var downY = 0f
        private var downAt = 0L
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            // the icon presses in under your finger and springs back when you let go
            val icon = (v as? ViewGroup)?.getChildAt(0) ?: v
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY; downAt = ev.eventTime
                    icon.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_CANCEL -> release(icon)
                MotionEvent.ACTION_MOVE -> if (ev.x < -v.width * 0.2f || ev.x > v.width * 1.2f || ev.y > v.height * 1.2f) release(icon)
                MotionEvent.ACTION_UP -> {
                    release(icon)
                    if (downY - ev.rawY > 36 * dp && ev.eventTime - downAt < 600) {
                        v.isPressed = false
                        v.cancelLongPress()
                        val first = Shortcuts.forApp(repo.launcherApps, e).firstOrNull()
                        if (first != null && Shortcuts.start(repo.launcherApps, first, v)) {
                            Haptics.feel(v, Haptics.Feel.CONFIRM)
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
        Haptics.feel(root, Haptics.Feel.CONFIRM)
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
        latelyBlock.visibility = if (entries.isEmpty() || searching) View.GONE else View.VISIBLE
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
            changeLook()
            return
        }
        val is24 = DateFormat.is24HourFormat(this)
        if (modern) { updateModernTexts(now, is24); return }
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
        backdrop.weather = Weather.code(this)
        backdrop.animationsOn = animationsOn() && !Quiet.now(this)
        backdrop.refresh() // rebuilds only when the season, size or look changed
    }

    // ------------------------------------------------------------------ unlock entrance

    private val easeOut = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val sceneEase = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)

    private fun entranceViews(): List<Pair<View, Long>> = listOf(
        label to 120L, clock to 160L, shire to 240L, date to 280L,
        ornament to 300L, meal to 320L, sunArc to 340L, music to 360L, widgetBox to 380L, hint to 300L, dock to 300L,
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
        home = findViewById(R.id.home)
        topSpace = findViewById(R.id.topSpace)
        spacer = findViewById(R.id.spacer)
        label = findViewById(R.id.label)
        clock = findViewById(R.id.clock)
        shire = findViewById(R.id.shire)
        date = findViewById(R.id.date)
        ornament = findViewById(R.id.ornament)
        meal = findViewById(R.id.meal)
        sunArc = findViewById(R.id.sunArc)
        // the music player sits under the sun arc
        music = MusicCard(this)
        home.addView(music, home.indexOfChild(sunArc) + 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).roundToInt() })
        // Live Updates (a call, directions, a delivery on its way) above the music player
        live = LiveCard(this)
        home.addView(live, home.indexOfChild(music), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).roundToInt() })
        // and widgets under that
        widgetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        home.addView(widgetBox, home.indexOfChild(music) + 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).roundToInt() })
        hint = findViewById(R.id.hint)
        arrangeForShape()
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

    // ------------------------------------------------------------------ screen shape
    private var sideways: LinearLayout? = null
    private var sidewaysClock: FrameLayout? = null
    private var hintWas = View.VISIBLE

    private fun clockBlock(): List<View> = listOf(label, clock, shire, date, ornament, meal, sunArc)
    private fun cardBlock(): List<View> = listOf(live, music, widgetBox)

    /**
     * Turned sideways (landscape), the clock stays centred (over the
     * Shire's sky) and the cards (Live Updates, music, widgets) move to a column on the right.
     * The clock shrinks if the screen is too short for it. Upright, everything goes back.
     */
    private fun arrangeForShape() {
        val wide = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (wide == (sideways != null)) return
        val clocks = clockBlock()
        val cards = cardBlock()
        if (wide) {
            for (v in clocks + cards) (v.parent as? ViewGroup)?.removeView(v)
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            clocks.forEach { mid.addView(it) }
            val holder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
            holder.addView(mid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            cards.forEach { right.addView(it) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            row.addView(Space(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(right) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = (24 * dp).roundToInt() })
            home.clipChildren = false
            home.addView(row, home.indexOfChild(topSpace) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            spacer.visibility = View.GONE
            hintWas = hint.visibility
            hint.visibility = View.GONE
            // shrink the clock to fit the height that's left
            // (the clock keeps its full natural height, so nothing is cut off, and is scaled
            // down as a whole to the height that's left)
            holder.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                mid.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val need = mid.measuredHeight
                val have = (bottom - top).toFloat()
                val sc = if (need > 0 && have > 0) (have / need).coerceAtMost(1f) else 1f
                mid.pivotX = mid.measuredWidth / 2f; mid.pivotY = 0f
                if (mid.scaleX != sc) { mid.scaleX = sc; mid.scaleY = sc }
                val lp = mid.layoutParams
                if (need > 0 && lp.height != need) { lp.height = need; holder.post { mid.layoutParams = lp } }
            }
            sideways = row
            sidewaysClock = holder
        } else {
            val row = sideways ?: return
            for (v in clocks + cards) (v.parent as? ViewGroup)?.removeView(v)
            home.removeView(row)
            var at = home.indexOfChild(topSpace) + 1
            for (v in clocks + cards) home.addView(v, at++)
            spacer.visibility = View.VISIBLE
            hint.visibility = hintWas
            sideways = null
            sidewaysClock = null
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeForShape()
        root.post { updateTopSpace() }
    }

    /** Places the clock block at the same height as in the design, whatever the screen. */
    private fun updateTopSpace() {
        val h = root.height
        if (h == 0) return
        val top = h * look.contentTopFraction
        val want = (top - insetTop).roundToInt().coerceAtLeast(0)
        val lp = topSpace.layoutParams
        if (lp.height != want) {
            lp.height = want
            topSpace.layoutParams = lp
        }
    }

    /**
     * The modern home clock, in the watch face's type: the date and the Shire date above, large
     * stacked numerals (heavy hours in the look's colour, light minutes), the mealtime, and the
     * sun arc. Shown over the look's animated scene.
     */
    private fun modernHoursColor(): Int = when (look.id) {
        "shire" -> 0xFF3E6B2E.toInt()
        "redbook" -> 0xFF9A2A1C.toInt()
        "starlit" -> 0xFFE9CC88.toInt()
        else -> 0xFFC6DDE8.toInt()
    }

    /** A new look arrives with the same entrance as unlocking (the scene settles, the words rise) rather than cutting. */
    private fun changeLook() {
        fadeInNext = visibleNow && animationsOn()
        recreate()
    }

    /** The glance line: your next alarm, and your next calendar event if Lembas may read the calendar. */
    private fun glanceText(now: LocalDateTime, is24: Boolean): String {
        val parts = ArrayList<String>()
        val fmt = DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm a")
        val zone = java.time.ZoneId.systemDefault()
        try {
            getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.let { a ->
                val t = java.time.Instant.ofEpochMilli(a.triggerTime).atZone(zone).toLocalDateTime()
                if (java.time.Duration.between(now, t).toHours() < 20) parts.add("Alarm " + t.format(fmt))
            }
        } catch (_: Throwable) { }
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) try {
            val start = System.currentTimeMillis()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(start.toString()).appendPath((start + 12 * 3_600_000L).toString()).build()
            contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY), "${android.provider.CalendarContract.Instances.ALL_DAY} = 0", null,
                "${android.provider.CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(1)
                    if (begin < start - 10 * 60_000L) continue
                    val t = java.time.Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
                    parts.add((c.getString(0) ?: "Event").take(28) + " " + t.format(fmt))
                    break
                }
            }
        } catch (_: Throwable) { }
        return parts.joinToString("  ·  ")
    }

    private fun updateModernTexts(now: LocalDateTime, is24: Boolean) {
        val hours = now.format(DateTimeFormatter.ofPattern(if (is24) "HH" else "hh"))
        val minutes = now.format(DateTimeFormatter.ofPattern("mm"))
        // hours and minutes side by side, as in the watch app (the watch face stacks them)
        val sb = SpannableStringBuilder(hours).append("\u2009").append(minutes)
        sb.setSpan(ForegroundColorSpan(modernHoursColor()), 0, hours.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        minutesFont?.let { sb.setSpan(TypefaceCompatSpan(it), hours.length + 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        clock.text = sb
        clock.contentDescription = now.format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm a"))
        val loc = java.util.Locale.getDefault()
        label.text = now.format(DateTimeFormatter.ofPattern("EEE d MMM", loc))
        val sd = Lore.shireDate(now.toLocalDate())
        shire.text = Lore.shireWeekday(now.dayOfWeek) + " · " + (if (sd.isSpecial) sd.name else "${sd.day} ${sd.name}")
        val m = if (prefs.getBoolean(PREF_MEALS, true)) Lore.meal(now.hour) else ""
        meal.text = m
        meal.visibility = if (m.isEmpty()) View.GONE else View.VISIBLE
        val glance = glanceText(now, is24)
        date.text = glance
        date.visibility = if (glance.isEmpty()) View.GONE else View.VISIBLE
        sunArc.update(now, is24)
        backdrop.refresh()
    }

    private fun applyModern() {
        hoursFont = Fonts.variable(assets, "Fraunces.ttf", "'wght' 760, 'opsz' 144, 'SOFT' 50, 'WONK' 0")
        minutesFont = Fonts.variable(assets, "Fraunces.ttf", "'wght' 280, 'opsz' 144, 'SOFT' 50, 'WONK' 0")
        val sans = Fonts.get(assets, "AlegreyaSans-Medium.ttf", 500)
        val lore = Fonts.variable(assets, "Fraunces-Italic.ttf", "'wght' 420, 'opsz' 24, 'SOFT' 50", italic = true)
        fun TextView.style(tf: android.graphics.Typeface?, sizeSp: Float, color: Int, spacing: Float = 0f) {
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            letterSpacing = spacing
            // night scenes: a soft halo in the sky's own colour keeps the words clear over trees and stars
            when {
                look.textShadow -> setShadowLayer(14 * dp, 0f, 2 * dp, 0x99000000.toInt())
                look.darkScene -> setShadowLayer(10 * dp, 0f, 0f, (look.windowColor and 0xFFFFFF) or 0xE6000000.toInt())
                else -> setShadowLayer(0f, 0f, 0f, 0)
            }
        }
        home.gravity = Gravity.CENTER_HORIZONTAL
        for (v in listOf(label, clock, shire, date, meal, ornament)) {
            (v.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            if (v is TextView) v.gravity = Gravity.CENTER
        }
        label.style(sans, 17f, look.clockColor, 0.02f)
        shire.style(lore, 17f, look.shireColor)
        clock.style(hoursFont, 112f, look.clockColor, -0.02f)
        meal.style(lore, 16f, look.mealColor)
        meal.background = null
        meal.setPadding(0, 0, 0, 0)
        meal.setCompoundDrawablesRelative(null, null, null, null)
        (meal.layoutParams as LinearLayout.LayoutParams).topMargin = (6 * dp).roundToInt()
        (shire.layoutParams as LinearLayout.LayoutParams).topMargin = (2 * dp).roundToInt()
        date.style(sans, 14f, look.clockColor, 0.02f)
        date.alpha = 0.85f
        (date.layoutParams as LinearLayout.LayoutParams).topMargin = (10 * dp).roundToInt()
        ornament.visibility = View.GONE
        sunArc.visibility = View.VISIBLE
        sunArc.setStyle(look.clockColor, modernHoursColor(), sans)
    }

    private fun applyLook() {
        modern = prefs.getString(PREF_HOME_STYLE, "modern") == "modern"
        backdrop.look = look
        backdrop.animationsOn = animationsOn() && !Quiet.now(this)
        backdrop.parallax = prefs.getBoolean(Themes.PREF_TILT, true)
        backdrop.weather = Weather.code(this)
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
        music.style(look.clockColor, (look.clockColor and 0x00FFFFFF) or 0xA0000000.toInt(), look.accent,
            if (look.lightBars) 0x1A000000 else 0x24FFFFFF, Fonts.get(assets, look.displayItalicFont, 400, true),
            Fonts.get(assets, "AlegreyaSans-Medium.ttf", 500))
        live.style(look.clockColor, (look.clockColor and 0x00FFFFFF) or 0xA0000000.toInt(), look.accent,
            if (look.lightBars) 0x1A000000 else 0x24FFFFFF, Fonts.get(assets, look.displayItalicFont, 400, true),
            Fonts.get(assets, "AlegreyaSans-Medium.ttf", 500))
        if (modern) applyModern() else { date.visibility = View.VISIBLE; sunArc.visibility = View.GONE }

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
        searchEngine = SearchEngine(this, repo)
        searchAdapter = SearchAdapter(this, look, icons, drawerSpec, searchEngine)
        list.adapter = adapter
        // the list stretches a little when you scroll past either end (Android 12+)
        if (android.os.Build.VERSION.SDK_INT >= 31) list.overScrollMode = View.OVER_SCROLL_ALWAYS
        list.setOnItemClickListener { _, v, pos, _ ->
            if (searching) openHit(searchAdapter.getItem(pos), v)
            else launch(adapter.getItem(pos), v.findViewById(R.id.icon))
        }
        // light up a row the instant a finger lands on it (Android itself waits a moment to tell
        // a tap from a scroll); scrolling away or lifting lets it fade
        var hot: PressRow? = null
        var downX = 0f
        var downY = 0f
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        list.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    val i = list.pointToPosition(ev.x.toInt(), ev.y.toInt())
                    hot = if (i == AdapterView.INVALID_POSITION) null else PressRow.of(list.getChildAt(i - list.firstVisiblePosition))
                    hot?.touching = true
                }
                MotionEvent.ACTION_MOVE -> if (hot != null && (kotlin.math.abs(ev.y - downY) > slop || kotlin.math.abs(ev.x - downX) > slop)) {
                    hot?.touching = false; hot = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hot?.touching = false; hot = null }
            }
            false
        }
        list.setOnItemLongClickListener { _, v, pos, _ ->
            PressRow.of(v)?.let { row -> row.isActivated = true; popup.onClosed = { row.isActivated = false } }
            if (searching) {
                val h = searchAdapter.getItem(pos)
                if (h is Hit.App) { showAppMenu(h.e, (v as ViewGroup).getChildAt(0)); true } else false
            } else { showAppMenu(adapter.getItem(pos), v.findViewById(R.id.icon)); true }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (total == 0 || searching) return
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
                runSearch(s?.toString() ?: "")
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (enter) searchAdapter.first()?.let { openHit(it, null) }
            enter
        }
    }

    /** Typing in search: the list switches from A-Z to results (apps, shortcuts, contacts, a sum, the web). */
    private fun runSearch(text: String) {
        val now = text.trim().isNotEmpty()
        if (now != searching) {
            searching = now
            list.adapter = if (now) searchAdapter else adapter
            railLeft.visibility = if (now) View.GONE else View.VISIBLE
            railRight.visibility = if (now) View.GONE else View.VISIBLE
            latelyBlock.visibility = if (now || lately.childCount == 0 || lately.getChildAt(0) is Space) View.GONE else View.VISIBLE
        }
        if (now) searchEngine.search(text) { hits ->
            if (searching && search.text.toString() == text) { searchAdapter.hits = hits; list.setSelection(0) }
        }
        updateEmpty()
    }

    private fun openHit(h: Hit, v: View?) {
        when (h) {
            is Hit.App -> launch(h.e, (v as? ViewGroup)?.getChildAt(0))
            is Hit.Shortcut -> { Shortcuts.start(repo.launcherApps, h.s, v); h.app?.let { recordLaunch(it) } }
            is Hit.Contact -> searchEngine.openContact(h)
            is Hit.Calc -> searchEngine.copy(h.result.replace(",", ""))
            is Hit.Action -> h.run(v)
            is Hit.Header -> Unit
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (widgets.onResult(requestCode, resultCode)) return
        if (Backup.onResult(this, requestCode, resultCode, data) { LookWallpaper.syncAsync(this, interactive = true); recreate() }) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SearchEngine.REQUEST_CONTACTS && searching) runSearch(search.text.toString())
    }

    private fun updateEmpty() {
        empty.visibility = if (!searching && repo.loaded && adapter.count == 0) View.VISIBLE else View.GONE
    }

    override fun isDrawerOpen() = drawerOpen

    override fun drawerCanScrollUp() = list.canScrollVertically(-1)

    /** Is there something under (x, y) inside [v] that scrolls up or down? */
    private fun scrollsAt(v: View, x: Float, y: Float): Boolean {
        if (!hit(v, x, y)) return false
        if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (scrollsAt(v.getChildAt(i), x, y)) return true
        return false
    }

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
        // widgets keep their own swipes (scrolling lists in mail or calendar widgets)
        // (only where the widget actually scrolls: a swipe on a clock or weather widget still
        // opens the Red Book)
        for (i in 0 until widgetBox.childCount) if (scrollsAt(widgetBox.getChildAt(i), x, y)) return true
        return false
    }

    override fun onDragStart() {
        animator?.cancel()
        animator = null
        dragBase = progress
        dragPastHalf = progress > 0.5f
        if (latelyStale) refreshLately()
        setLayers(true)
        hideKeyboard()
        popup.dismiss()
    }

    override fun onDrag(dy: Float) {
        val h = root.height.takeIf { it > 0 } ?: return
        applyProgress((dragBase - dy / h).coerceIn(0f, 1f))
        // a tick as you cross the point where letting go flips the result, and back again
        val past = progress > 0.5f
        if (past != dragPastHalf) {
            dragPastHalf = past
            Haptics.feel(root, if (past != (dragBase > 0.5f)) Haptics.Feel.THRESHOLD_ON else Haptics.Feel.THRESHOLD_OFF)
        }
    }

    private var dragPastHalf = false

    override fun onDragEnd(velocityY: Float) {
        if (android.util.Log.isLoggable("LembasTouch", android.util.Log.DEBUG)) android.util.Log.d("LembasTouch", "end v=$velocityY p=$progress")
        val fling = 900 * dp
        val open = when {
            velocityY < -fling -> true
            velocityY > fling -> false
            else -> progress > 0.5f
        }
        animateTo(if (open) 1f else 0f, velocityY)
    }

    override fun onPullDownOnHome() {
        Haptics.feel(root, Haptics.Feel.LIGHT)
        try {
            @Suppress("WrongConstant")
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (t: Throwable) {
            // Some phones don't allow it; nothing else to do.
        }
    }

    override fun onLongPressHome() = showSettings()

    override fun onDoubleTapHome() {
        if (LockService.enabled(this)) { Haptics.feel(root, Haptics.Feel.CONFIRM); LockService.lock() }
    }

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

    /**
     * Move the Red Book open or shut on a spring: it carries the speed of your swipe and settles
     * without a fixed-length slide, so a quick flick is quick and a slow drag eases home.
     */
    private fun animateTo(target: Float, velocity: Float = 0f) {
        if (android.util.Log.isLoggable("LembasTouch", android.util.Log.DEBUG)) android.util.Log.d("LembasTouch", "animateTo $target from $progress anim=${ValueAnimator.areAnimatorsEnabled()}")
        animator?.cancel()
        val from = progress
        if (from == target) {
            settle(target)
            return
        }
        if (target > 0f && latelyStale) refreshLately()
        // the keyboard rises with the Red Book, not after it
        if (target >= 1f && prefs.getBoolean(PREF_KEYBOARD, false)) showKeyboard()
        // with the phone's animations off ("Remove animations"), Android never runs the spring: jump there
        if (!ValueAnimator.areAnimatorsEnabled()) { settle(target); return }
        setLayers(true)
        val h = root.height.toFloat().coerceAtLeast(1f)
        var x = from
        var v = -velocity / h               // progress per second (a swipe up opens: progress rises)
        val stiffness = 520f
        val damping = 2f * kotlin.math.sqrt(stiffness) * 1.0f   // critically damped: no bounce past the edge
        animator = android.animation.TimeAnimator().apply {
            setTimeListener { anim, _, deltaMs ->
                var dt = (deltaMs.coerceIn(0L, 48L)) / 1000f
                while (dt > 0f) {                     // small steps keep the spring steady on slow frames
                    val step = min(dt, 0.004f)
                    v += (-stiffness * (x - target) - damping * v) * step
                    x += v * step
                    dt -= step
                }
                if (abs(x - target) < 0.0015f && abs(v) < 0.05f) {
                    anim.cancel()
                    animator = null
                    settle(target)
                } else applyProgress(x.coerceIn(0f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                // ended without reaching the target (Android stopped it): finish the move anyway
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && animator === animation) { animator = null; settle(target) }
                }
            })
            start()
        }
    }

    private fun settle(target: Float) {
        if (android.util.Log.isLoggable("LembasTouch", android.util.Log.DEBUG)) android.util.Log.d("LembasTouch", "settle $target")
        applyProgress(target)
        setLayers(false)
        val was = drawerOpen
        drawerOpen = target >= 1f
        if (was != drawerOpen) Haptics.feel(root, Haptics.Feel.SNAP)
        backGesture(drawerOpen)
        updateAmbient()
        if (drawerOpen) {
            searchEngine.warmUp()
            if (prefs.getBoolean(PREF_KEYBOARD, false) && !search.hasFocus()) showKeyboard()
        } else {
            hideKeyboard()
            if (search.text.isNotEmpty()) search.setText("")
            list.setSelection(0)
        }
    }

    /** Animate the scene only while the home screen is actually showing. */
    private fun updateAmbient() {
        backdrop.active = resumed && !drawerOpen
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
        val themed = prefs.getBoolean(PREF_THEMED, true)
        val meals = prefs.getBoolean(PREF_MEALS, true)
        val kbd = prefs.getBoolean(PREF_KEYBOARD, false)
        val anim = animationsOn()
        val calendar = checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        fun onOff(b: Boolean) = if (b) "On" else "Off"
        val day = Sun.today()
        fun hm(m: Int) = java.time.LocalTime.of(m / 60, m % 60).format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"))
        val times = mapOf(
            Look.ADAPTIVE to "now ${Themes.adaptive().title}",
            Look.SHIRE.id to "${hm(day.sunrise)}–${hm(day.evening)}",
            Look.REDBOOK.id to "${hm(day.evening)}–${hm(day.sunset)}",
            Look.STARLIT.id to "${hm(day.sunset)}–11pm",
            Look.ELVEN.id to "11pm–4am",
        )
        val sections = listOf(
            SettingsSheet.Section("Home screen", listOf(
                SettingsSheet.Row("Clock", if (modern) "Modern" else "Classic") {
                    prefs.edit().putString(PREF_HOME_STYLE, if (modern) "classic" else "modern").apply(); recreate(); false },
                SettingsSheet.Row("Animations", onOff(anim)) {
                    prefs.edit().putBoolean(Themes.PREF_ANIM, !anim).apply(); backdrop.animationsOn = !anim && !Quiet.now(this); true },
                SettingsSheet.Row("Tilt depth", onOff(prefs.getBoolean(Themes.PREF_TILT, true))) {
                    val on = !prefs.getBoolean(Themes.PREF_TILT, true)
                    prefs.edit().putBoolean(Themes.PREF_TILT, on).apply(); backdrop.parallax = on; true },
                SettingsSheet.Row("Quiet hours, 11pm–7am", if (Quiet.enabled(this)) "No vibrations, still scene" else "Off") {
                    prefs.edit().putBoolean(Quiet.PREF, !Quiet.enabled(this)).apply(); backdrop.animationsOn = animationsOn() && !Quiet.now(this); true },
                SettingsSheet.Row("Weather in the scene", onOff(Weather.on(this))) {
                    val on = !Weather.on(this)
                    prefs.edit().putBoolean(Themes.PREF_WEATHER, on).apply(); backdrop.weather = Weather.code(this); true },
                SettingsSheet.Row("App icons", when {
                    themed -> "Line art"
                    IconPacks.selected(this).isNotEmpty() -> IconPacks.installed(this).firstOrNull { it.pkg == IconPacks.selected(this) }?.label ?: "Icon pack"
                    else -> "Original"
                }) { chooseIcons(); false },
                SettingsSheet.Row("Hobbit mealtimes", onOff(meals)) {
                    prefs.edit().putBoolean(PREF_MEALS, !meals).apply(); updateTexts(); true },
                SettingsSheet.Row("Next calendar event", onOff(calendar)) {
                    if (calendar) {
                        Toast.makeText(this, "Turn off calendar access in Lembas's app permissions", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) { }
                    } else requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 0xca1)
                    false },
                SettingsSheet.Row("Notification dots and previews", onOff(Dots.enabled(this))) {
                    if (Dots.enabled(this)) { Dots.turnOff(this); true }
                    else if (Dots.turnOn(this)) true
                    else {
                        Toast.makeText(this, "Allow Lembas under Notification access (the previews stay on your phone; nothing is sent anywhere)", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
                        false
                    } },
                SettingsSheet.Row("Music player", onOff(prefs.getBoolean(PREF_MUSIC, true) && Dots.accessGranted(this))) {
                    val on = prefs.getBoolean(PREF_MUSIC, true) && Dots.accessGranted(this)
                    prefs.edit().putBoolean(PREF_MUSIC, !on).apply()
                    if (on) { music.stop(); music.visibility = View.GONE; true }
                    else if (Dots.accessGranted(this) || Dots.turnOn(this)) { music.start(); true }
                    else {
                        Toast.makeText(this, "Allow Lembas under Notification access to show what's playing", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
                        false
                    } },
                SettingsSheet.Row("Live Updates", onOff(Live.enabled(this) && Dots.accessGranted(this))) {
                    val on = Live.enabled(this) && Dots.accessGranted(this)
                    prefs.edit().putBoolean(Live.PREF, !on).apply()
                    if (on) { Live.refresh(this); live.show(); true }
                    else if (Dots.accessGranted(this) || Dots.turnOn(this)) { Live.refresh(this); live.show(); true }
                    else {
                        Toast.makeText(this, "Allow Lembas under Notification access to show calls, directions and deliveries", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
                        false
                    } },
                SettingsSheet.Row("Double-tap to lock", onOff(LockService.enabled(this))) {
                    if (LockService.enabled(this)) { LockService.turnOff(this); true }
                    else if (android.os.Build.VERSION.SDK_INT < 28) { Toast.makeText(this, "Needs Android 9 or later", Toast.LENGTH_SHORT).show(); true }
                    else if (LockService.turnOn(this)) true
                    else {
                        Toast.makeText(this, "Turn on \"Lembas double-tap to lock\" in Accessibility (it only locks the screen)", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { }
                        false
                    } },
                SettingsSheet.Row("Haptics", onOff(Haptics.enabled(this))) {
                    val on = !Haptics.enabled(this)
                    Haptics.setEnabled(this, on)
                    if (on) Haptics.feel(root, Haptics.Feel.TOGGLE_ON)
                    true },
                SettingsSheet.Row("Keyboard when opening the Red Book", onOff(kbd)) {
                    prefs.edit().putBoolean(PREF_KEYBOARD, !kbd).apply(); true },
            )),
            SettingsSheet.Section("Widgets", listOf(
                SettingsSheet.Row("Add a widget", if (widgets.count == 0) "" else "${widgets.count} on the home screen") { widgets.pick(); false },
            )),
            SettingsSheet.Section("Apps and search", listOf(
                SettingsSheet.Row("Red Book icons", DrawerIcons.title(prefs.getString(DrawerIcons.PREF, DrawerIcons.DEFAULT) ?: "")) { chooseDrawerIcons(); false },
                SettingsSheet.Row("Hidden apps", if (repo.hidden.isEmpty()) "None" else "${repo.hidden.size}") { showHiddenApps(); false },
                SettingsSheet.Row("Contacts in search", onOff(searchEngine.contactsAllowed)) {
                    if (searchEngine.contactsAllowed) {
                        Toast.makeText(this, "Turn off contacts access in Lembas's app permissions", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) { }
                    } else requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), SearchEngine.REQUEST_CONTACTS)
                    false },
            )),
            SettingsSheet.Section("The whole phone", listOf(
                SettingsSheet.Row("Theme the whole phone", when {
                    SystemTheme.enabled(this) -> "On"
                    SystemTheme.permitted(this) -> "Off"
                    else -> "Set up once"
                }) {
                    when {
                        SystemTheme.enabled(this) -> { SystemTheme.setEnabled(this, false); SystemTheme.restore(this); true }
                        SystemTheme.permitted(this) -> { SystemTheme.setEnabled(this, true); true }
                        else -> { ThemeSetup.start(this); false }
                    } },
                SettingsSheet.Row("Animated wallpaper (home and lock screen)", if (LookWallpaper.liveWallpaperInUse(this)) "On" else "Set") {
                    setLiveWallpaper(); false },
                SettingsSheet.Row("Still wallpaper follows the look", onOff(LookWallpaper.enabled(this))) {
                    LookWallpaper.setEnabled(this, !LookWallpaper.enabled(this)); true },
                SettingsSheet.Row("Make Lembas your home app", "") {
                    try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (t: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    false },
            )),
            SettingsSheet.Section("Watch", listOf(
                SettingsSheet.Row("Put Lembas on your watch", "") { WatchSetup.start(this); false },
            )),
            SettingsSheet.Section("Backup", listOf(
                SettingsSheet.Row("Back up settings", "to a file you keep") { Backup.save(this); false },
                SettingsSheet.Row("Restore settings", "from a backup file") { Backup.open(this); false },
            )),
            SettingsSheet.Section("Updates", listOf(
                SettingsSheet.Row("Automatic updates", onOff(Updater.enabled(this))) {
                    Updater.setEnabled(this, !Updater.enabled(this)); true },
                SettingsSheet.Row("Check for updates now", "version ${versionName()}") { checkForUpdate(); false },
            )),
        )
        SettingsSheet(this, look, Themes.choice(this), times) { id -> pickLook(id) }.show(sections) { showSettings() }
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
            "Adaptive — follows the sun: Shire Morning ${hm(day.sunrise)}–${hm(day.evening)}, Red Book until ${hm(day.sunset)}, Starlit Night until 11pm, Elven Silver until 4am",
            "Shire Morning · morning light",
            "Red Book · afternoon",
            "Starlit Night · evening",
            "Elven Silver · deep night",
        )
        val current = ids.indexOf(Themes.choice(this)).coerceAtLeast(0)
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Theme")
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                pickLook(ids[which])
            }
            .show()
    }

    /** A look chosen by hand: keep it until Adaptive's next change, or for good. */
    private fun pickLook(id: String) {
        fun apply(until: Long) {
            Themes.choose(this, id, until)
            LookWallpaper.syncAsync(this, interactive = true)
            if (Themes.current(this).id != look.id) changeLook()
        }
        if (id == Look.ADAPTIVE) { apply(0); return }
        val next = Themes.nextChange()
        val at = next.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"))
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Keep ${Look.byId(id).title}")
            .setItems(arrayOf("Until $at, then follow the sun again", "Until I change it")) { _, which ->
                apply(if (which == 0) next.toInstant().toEpochMilli() else 0)
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
        const val PREF_HOME_STYLE = "home_style"
        const val PREF_MUSIC = "music"
        const val ACTION_SEARCH = "com.middleearth.launcher.SEARCH"
        /** Set just before a look change rebuilds the screen, so the new look fades in. */
        @JvmStatic var fadeInNext = false
        const val PREF_KEYBOARD = "keyboard"
        const val PREF_DOCK = "dock"
        const val PREF_DOCK_SEEDED = "dock_seeded"
        const val PREF_RECENT = "recent"
        const val MAX_DOCK = 5
    }
}
