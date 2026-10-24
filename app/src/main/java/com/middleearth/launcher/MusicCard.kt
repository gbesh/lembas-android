package com.middleearth.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The music player on the home screen: what's playing in any app, with play/pause and next.
 * It appears while something is playing (or paused, for a while), and uses the same notification
 * access as the notification dots. Tap the title to open the app that's playing.
 */
class MusicCard(ctx: Context) : LinearLayout(ctx) {
    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()
    private val art = ImageView(ctx)
    private val title = TextView(ctx)
    private val artist = TextView(ctx)
    private val play = ImageView(ctx)
    private val next = ImageView(ctx)
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var accent = 0xFFD9BC7A.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8f), px(8f), px(8f), px(8f))
        visibility = GONE
        art.layoutParams = LayoutParams(px(40f), px(40f))
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.clipToOutline = true
        art.background = GradientDrawable().apply { cornerRadius = 10 * dp }
        art.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) }
            addView(title); addView(artist)
        }
        for (t in listOf(title, artist)) { t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        for ((b, d) in listOf(play to "Play or pause", next to "Next")) {
            b.layoutParams = LayoutParams(px(44f), px(44f))
            b.setPadding(px(10f), px(10f), px(10f), px(10f))
            b.contentDescription = d
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            b.setBackgroundResource(tv.resourceId)
        }
        next.setImageResource(R.drawable.g_next)
        addView(art); addView(col); addView(play); addView(next)
        play.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        next.setOnClickListener { controller?.transportControls?.skipToNext() }
        col.setOnClickListener { controller?.sessionActivity?.let { try { it.send() } catch (_: Exception) { } } }
    }

    fun style(text: Int, soft: Int, accent: Int, fill: Int, font: Typeface, sans: Typeface) {
        this.accent = accent
        title.setTextColor(text); title.typeface = font
        artist.setTextColor(soft); artist.typeface = sans
        play.imageTintList = ColorStateList.valueOf(accent)
        next.imageTintList = ColorStateList.valueOf(accent)
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(fill) }
        (art.background as GradientDrawable).setColor((accent and 0x00FFFFFF) or 0x33000000)
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = show()
        override fun onPlaybackStateChanged(state: PlaybackState?) = show()
        override fun onSessionDestroyed() { controller = null; show() }
    }

    /** Start following music (needs notification access; silently does nothing without it). */
    fun start() {
        if (!Dots.accessGranted(context)) { visibility = GONE; return }
        try {
            val m = context.getSystemService(MediaSessionManager::class.java) ?: return
            sessions = m
            m.addOnActiveSessionsChangedListener(sessionsChanged, Dots.component(context), main)
            pick(m.getActiveSessions(Dots.component(context)))
        } catch (_: SecurityException) { visibility = GONE }
    }

    fun stop() {
        try { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Throwable) { }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) { show(); return }
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        show()
    }

    private fun show() {
        val c = controller
        val md = c?.metadata
        val state = c?.playbackState?.state
        val name = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
        if (c == null || name.isNullOrEmpty() || !active) { visibility = GONE; return }
        title.text = name
        artist.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) { art.setImageBitmap(bmp); art.imageTintList = null; art.setPadding(0, 0, 0, 0) }
        else { art.setImageResource(R.drawable.g_music); art.imageTintList = ColorStateList.valueOf(accent); art.setPadding(px(9f), px(9f), px(9f), px(9f)) }
        play.setImageResource(if (state == PlaybackState.STATE_PLAYING) R.drawable.g_pause else R.drawable.g_play)
        visibility = VISIBLE
    }
}
