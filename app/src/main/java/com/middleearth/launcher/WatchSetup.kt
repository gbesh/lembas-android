package com.middleearth.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Puts Lembas on your Wear OS watch from the phone: no computer needed.
 *
 * On the watch you open Wireless debugging and "Pair new device"; on the phone you type the code
 * the watch shows. Lembas finds the watch on the Wi-Fi, pairs with it, downloads the newest watch
 * app and watch face from gbesh/lembas-wear, installs both, and lets the watch app keep them up to
 * date from then on. Running it again later reinstalls or updates.
 */
object WatchSetup {
    private const val RELEASE = "https://api.github.com/repos/gbesh/lembas-wear/releases/latest"
    private const val WATCH_APP = "com.middleearth.launcher"

    fun start(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            AlertDialog.Builder(activity).setTitle("Lembas on your watch")
                .setMessage("Installing on the watch from the phone needs Android 11 or later. Use the Windows installer from github.com/gbesh/lembas-wear instead.")
                .setPositiveButton("OK", null).show()
            return
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(TextView(activity).apply {
            text = "On the watch (same Wi-Fi as this phone):\n\n" +
                "1. Settings → System → About → Versions: tap Build number seven times.\n" +
                "2. Settings → Developer options: turn on ADB debugging and Wireless debugging.\n" +
                "3. Wireless debugging → Pair new device.\n\n" +
                "Then type the six-digit code the watch shows:"
        })
        val code = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "Pairing code" }
        box.addView(code)
        AlertDialog.Builder(activity).setTitle("Lembas on your watch").setView(box)
            .setPositiveButton("Install") { _, _ -> run(activity, code.text.toString().filter { it.isDigit() }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(activity: Activity, code: String) {
        val progress = AlertDialog.Builder(activity).setTitle("Lembas on your watch").setMessage("Looking for the watch…")
            .setCancelable(false).show()
        val main = Handler(Looper.getMainLooper())
        fun say(t: String) = main.post { progress.setMessage(t) }
        Thread {
            val result = try { install(activity, code, ::say) } catch (e: Throwable) { "Something went wrong: ${e.message}" }
            main.post {
                progress.dismiss()
                AlertDialog.Builder(activity).setTitle(if (result == null) "Lembas is on your watch" else "Lembas on your watch")
                    .setMessage(result ?: "Add the face: touch and hold the watch face, swipe to the end, tap +, and choose Lembas.\n" +
                        "Add the tile: swipe left from the face and add the Lembas tile.\n\n" +
                        "The watch keeps Lembas up to date by itself. You can turn Wireless debugging off on the watch now.")
                    .setPositiveButton("OK", null).show()
            }
        }.start()
    }

    /** Returns null when it worked, or what went wrong. */
    private fun install(activity: Activity, code: String, say: (String) -> Unit): String? {
        val ctx = activity.applicationContext
        if (code.length < 6) return "Type the six-digit code the watch shows under Pair new device."
        val ownIps = AdbLink.ownAddresses()
        val pairing = AdbLink.discoverAll(ctx, AdbLink.PAIRING, 8000).firstOrNull { it.first.hostAddress !in ownIps }
            ?: return "Couldn't find the watch. Keep Pair new device open on the watch, with the watch and phone on the same Wi-Fi, and try again."
        val host = pairing.first.hostAddress ?: return "Couldn't find the watch's address."
        val link = AdbLink.watch(ctx)
        say("Pairing with the watch…")
        try { link.pair(host, pairing.second, code) } catch (e: Exception) {
            return "The watch didn't accept that code. A new code appears each time you open Pair new device."
        }
        say("Connecting…")
        val connect = AdbLink.discoverAll(ctx, AdbLink.CONNECT, 8000).firstOrNull { it.first.hostAddress == host }
            ?: return "Paired, but couldn't connect. Keep Wireless debugging on and try again."
        try { if (!link.connect(host, connect.second)) return "Paired, but couldn't connect. Keep Wireless debugging on and try again." }
        catch (e: Exception) { return "Paired, but couldn't connect: ${e.message}" }
        try {
            say("Downloading Lembas for the watch…")
            val rel = JSONObject(fetch(RELEASE).readText())
            val assets = rel.getJSONArray("assets")
            fun url(name: String): String? {
                for (i in 0 until assets.length()) if (assets.getJSONObject(i).optString("name") == name) return assets.getJSONObject(i).optString("browser_download_url")
                return null
            }
            val face = File(ctx.cacheDir, "lembas-watchface.apk")
            val app = File(ctx.cacheDir, "lembas-wear.apk")
            download(url("lembas-watchface.apk") ?: return "The newest release has no watch face.", face)
            download(url("lembas-wear.apk") ?: return "The newest release has no watch app.", app)
            say("Installing the watch face…")
            val r1 = link.install(face)
            say("Installing the watch app…")
            val r2 = link.install(app)
            link.shell("appops set $WATCH_APP REQUEST_INSTALL_PACKAGES allow")
            face.delete(); app.delete()
            if (!r1.contains("Success") || !r2.contains("Success")) return "The watch said: ${(r1 + " " + r2).trim().take(200)}"
            return null
        } finally {
            try { link.disconnect() } catch (_: Exception) { }
        }
    }

    private fun fetch(url: String): java.io.InputStream {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Lembas")
            when (c.responseCode) {
                in 300..399 -> { u = URL(u, c.getHeaderField("Location")); c.disconnect() }
                200 -> return c.inputStream
                else -> { val code = c.responseCode; c.disconnect(); throw java.io.IOException("HTTP $code") }
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun java.io.InputStream.readText() = bufferedReader().use { it.readText() }

    private fun download(url: String, to: File) = fetch(url).use { input -> to.outputStream().use { input.copyTo(it) } }
}
