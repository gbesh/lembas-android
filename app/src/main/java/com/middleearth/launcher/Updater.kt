package com.middleearth.launcher

import android.app.Activity
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Keeps Lembas up to date from its public GitHub releases, with no store and no libraries.
 *
 * Once a day (on any network, never while the battery is low) it asks GitHub for the latest
 * release. Release tags are "v<versionCode>"; if that is newer than this build, it downloads
 * lembas-launcher.apk and hands it to Android's PackageInstaller. From Android 12 on, an app
 * that installed itself last time may update itself again without a prompt, so after the first
 * confirmation updates arrive silently. Otherwise Android asks once, the next time the home
 * screen is shown.
 */
object Updater {
    private const val API = "https://api.github.com/repos/gbesh/lembas-android/releases/latest"
    private const val ASSET = "lembas-launcher.apk"
    private const val JOB_ID = 0x1e3ba5
    const val PREF_AUTO = "auto_update"
    private const val PREF_LAST = "update_last_check"

    /** A confirmation Android asked for while we were in the background; shown on next resume. */
    @Volatile var pendingConfirm: Intent? = null

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(PREF_AUTO, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(PREF_AUTO, on).apply()
        schedule(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)

    /** Called on every launch; cheap if the job is already scheduled. Never throws. */
    fun schedule(ctx: Context) {
        try { scheduleInner(ctx) } catch (_: Throwable) { }
    }

    private fun scheduleInner(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(ctx)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPeriodic(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(6))
            .setPersisted(true)
            .build())
    }

    /** Shows a confirmation Android asked for, if any. Call from a visible activity. */
    fun showPending(activity: Activity) {
        val i = pendingConfirm ?: return
        pendingConfirm = null
        try { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    enum class Result { UP_TO_DATE, INSTALLING, FAILED }

    /** Blocking: check, download and start installing. Run off the main thread. */
    fun run(ctx: Context): Result = try {
        prefs(ctx).edit().putLong(PREF_LAST, System.currentTimeMillis()).apply()
        val rel = JSONObject(get(API).bufferedReader().use { it.readText() })
        val latest = rel.optString("tag_name").removePrefix("v").toLongOrNull() ?: 0L
        val mine = currentVersion(ctx)
        if (latest <= mine) Result.UP_TO_DATE
        else {
            val assets = rel.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
            }
            if (url == null) Result.FAILED
            else {
                val apk = File(ctx.cacheDir, "update.apk")
                get(url).use { input -> apk.outputStream().use { input.copyTo(it) } }
                install(ctx, apk)
                Result.INSTALLING
            }
        }
    } catch (_: Throwable) {
        Result.FAILED
    }

    private fun currentVersion(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun get(url: String): java.io.InputStream {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "application/octet-stream")
            c.setRequestProperty("User-Agent", "Lembas-Launcher")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location"))
                c.disconnect()
            } else if (code == 200) {
                return c.inputStream
            } else {
                c.disconnect()
                throw java.io.IOException("HTTP $code")
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 34) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("lembas.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, InstallResult::class.java), flags)
            s.commit(status.intentSender)
        }
    }

    class InstallResult : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                pendingConfirm = confirm
                // The home screen is usually what's showing; if so, ask right away.
                try { ctx.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); pendingConfirm = null }
                catch (_: Exception) { }
            }
            File(ctx.cacheDir, "update.apk").delete()
        }
    }

    class UpdateJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            Thread {
                try { run(applicationContext) } catch (_: Throwable) { }
                jobFinished(params, false)
            }.start()
            return true
        }
        override fun onStopJob(params: JobParameters) = true
    }
}
