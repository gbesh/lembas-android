package com.middleearth.launcher

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import java.time.LocalDate

/**
 * Lembas as a live wallpaper: the same scene as the home screen, on the lock screen and
 * behind apps. It also tells Android which colours to build the system palette from, so on
 * Pixel phones quick settings, Settings, the keyboard and Google apps follow the theme.
 * Animates only while visible, at ~30 fps; otherwise it costs nothing.
 */
class LembasWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

    inner class SceneEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val main = Handler(Looper.getMainLooper())
        private val prefs = getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        private val power = getSystemService(PowerManager::class.java)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dp = resources.displayMetrics.density
        private val start = SystemClock.uptimeMillis()

        private var look: Look = Themes.current(this@LembasWallpaper)
        private var width = 0
        private var height = 0
        private var bitmap: Bitmap? = null
        private var ambient: Ambient? = null
        private var paintedKey = ""
        private var token = 0
        private var visible = false
        private var running = false
        private var lastFrame = 0L

        private val tick = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = recheck()
        }

        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val now = frameTimeNanos / 1_000_000
                if (now - lastFrame >= 31) {
                    lastFrame = now
                    drawFrame()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(tick, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(tick, filter)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
            try { unregisterReceiver(tick) } catch (t: Throwable) { }
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            if (key == Themes.PREF_THEME || key == Themes.PREF_ANIM) recheck()
        }

        /** Called every minute and on setting changes: switch theme when Adaptive says so. */
        private fun recheck() {
            val want = Themes.current(this@LembasWallpaper)
            if (want.id != look.id) {
                look = want
                ambient = if (width > 0) Ambient(look, width.toFloat(), height.toFloat(), dp) else null
                repaint()
                if (Build.VERSION.SDK_INT >= 27) notifyColorsChanged()
            } else {
                repaint() // only does work when the day (moon phase) changed
            }
            updateRunning()
        }

        override fun onComputeColors(): WallpaperColors {
            val p = Color.valueOf(look.wpPrimary)
            val s = Color.valueOf(look.wpSecondary)
            val t = Color.valueOf(look.wpTertiary)
            return if (Build.VERSION.SDK_INT >= 31) {
                val hints = if (look.darkScene) WallpaperColors.HINT_SUPPORTS_DARK_THEME
                else WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                WallpaperColors(p, s, t, hints)
            } else WallpaperColors(p, s, t)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            ambient = Ambient(look, w.toFloat(), h.toFloat(), dp)
            paintedKey = ""
            repaint()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) recheck()
            updateRunning()
            if (v) drawFrame()
        }

        private fun updateRunning() {
            val should = visible && Themes.animationsOn(this@LembasWallpaper) && power?.isPowerSaveMode != true
            if (should == running) return
            running = should
            if (should) Choreographer.getInstance().postFrameCallback(frame)
            else Choreographer.getInstance().removeFrameCallback(frame)
        }

        private fun repaint() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val key = "${look.id}#$w#$h#${LocalDate.now().toEpochDay()}"
            if (key == paintedKey) return
            paintedKey = key
            val t = ++token
            val lk = look
            Thread {
                val bmp = try { Painter(dp).paint(lk, w, h) } catch (e: Throwable) { null }
                main.post {
                    if (t == token && bmp != null) {
                        bitmap = bmp
                        drawFrame()
                    }
                }
            }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-wallpaper" }.start()
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            if (width <= 0) return
            var c: Canvas? = null
            try {
                c = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                if (c == null) return
                val b = bitmap
                if (b != null && b.width == width && b.height == height) c.drawBitmap(b, 0f, 0f, paint)
                else c.drawColor(look.windowColor)
                ambient?.draw(c, (SystemClock.uptimeMillis() - start) / 1000f)
            } catch (t: Throwable) {
                // Surface went away mid-frame; the next frame will try again.
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c) } catch (t: Throwable) { }
            }
        }
    }
}
