package com.middleearth.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Keeps the look and its settings the same on the phone, Lembas for Windows and the watch,
 * through your own Google account. Lembas keeps one small file (lembas-sync.json) in a hidden
 * folder of your Google Drive that only Lembas can see ("drive.appdata": it can't see any of your
 * other files). The newest change wins. The watch gets it from the phone over the watch link.
 *
 * Synced: the look (or follow the sun), "keep it until", weather in the scene, the clock style,
 * the hobbit mealtimes, and (only with "Weather for where you are" on) your area, rounded to about
 * a kilometre, so the PC's weather is for where you are too.
 */
object GoogleSync {
    const val PREF_ON = "sync_google"
    const val REQUEST_SIGN_IN = 0x5e1c
    private const val PREF_SIG = "sync_sig"
    private const val PREF_REMOTE = "sync_remote_updated"
    private const val PREF_LAST = "sync_last_try"
    private const val FILE = "lembas-sync.json"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    /** The settings that travel (the same names as Lembas for Windows). */
    val KEYS = setOf(Themes.PREF_THEME, Themes.PREF_KEEP_UNTIL, Themes.PREF_WEATHER, "home_style", "meals")

    /** Called on the main thread after another device's settings were taken. */
    var onPulled: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean(PREF_ON, false)

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    // ------------------------------------------------------------------ signing in (Google's own sheet)
    fun signIn(a: Activity) {
        Identity.getAuthorizationClient(a).authorize(request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    try { a.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQUEST_SIGN_IN, null, 0, 0, 0) }
                    catch (_: Exception) { Toast.makeText(a, "Couldn't open Google sign-in", Toast.LENGTH_SHORT).show() }
                } else turnedOn(a)
            }
            .addOnFailureListener { Toast.makeText(a, "Couldn't reach Google sign-in: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /** From onActivityResult. True if it was ours. */
    fun onResult(a: Activity, request: Int, result: Int, data: Intent?): Boolean {
        if (request != REQUEST_SIGN_IN) return false
        if (result == Activity.RESULT_OK) try {
            Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data)
            turnedOn(a)
        } catch (e: Exception) { Toast.makeText(a, "Not signed in", Toast.LENGTH_SHORT).show() }
        return true
    }

    private fun turnedOn(a: Activity) {
        prefs(a).edit().putBoolean(PREF_ON, true).remove(PREF_SIG).remove(PREF_REMOTE).apply()
        Toast.makeText(a, "Sync is on: your PC and watch will follow", Toast.LENGTH_SHORT).show()
        sync(a.applicationContext, force = true)
    }

    fun signOut(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_ON, false).remove(PREF_SIG).remove(PREF_REMOTE).apply()
    }

    // ------------------------------------------------------------------ syncing
    private fun local(ctx: Context): JSONObject {
        val p = prefs(ctx)
        return JSONObject()
            .put("look", p.getString(Themes.PREF_THEME, Look.ADAPTIVE) ?: Look.ADAPTIVE)
            .put("keepUntil", p.getLong(Themes.PREF_KEEP_UNTIL, 0))
            .put("weather", p.getBoolean(Themes.PREF_WEATHER, true))
            .put("clock", p.getString("home_style", "modern") ?: "modern")
            .put("meals", p.getBoolean("meals", true))
            .apply { Here.where(ctx)?.let { (la, lo) -> put("lat", la); put("lon", lo) } }   // only with "Weather for where you are" on
            .apply { if (Journey.started(ctx)) put("walk", JSONObject().put("km", Journey.km(ctx).toInt()).put("line", Journey.line(ctx))) }   // the walk, for the PC's panel
    }
    private fun sig(j: JSONObject) = "${j.optString("look")}|${j.optLong("keepUntil")}|${j.optBoolean("weather", true)}|${j.optString("clock")}|${j.optBoolean("meals", true)}|${j.optDouble("lat")}|${j.optDouble("lon")}|${j.optJSONObject("walk")?.optInt("km")}"

    private fun apply(ctx: Context, r: JSONObject) {
        prefs(ctx).edit()
            .putString(Themes.PREF_THEME, r.optString("look", Look.ADAPTIVE))
            .putLong(Themes.PREF_KEEP_UNTIL, r.optLong("keepUntil", 0))
            .putBoolean(Themes.PREF_WEATHER, r.optBoolean("weather", true))
            .putString("home_style", if (r.optString("clock") == "classic") "classic" else "modern")
            .putBoolean("meals", r.optBoolean("meals", true))
            .commit()
    }

    /** Sync now in the background (at most every two minutes unless [force]). Never asks anything. */
    fun sync(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        tellWatch(app)
        if (!enabled(app) || busy) return
        val p = prefs(app)
        if (!force && System.currentTimeMillis() - p.getLong(PREF_LAST, 0) < 2 * 60_000L) return
        p.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply()
        busy = true
        Thread {
            try { run(app) } catch (_: Exception) { } finally { busy = false }
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-sync" }.start()
    }

    private fun run(ctx: Context) {
        val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 20, TimeUnit.SECONDS)
        if (r.hasResolution()) return     // the sign-in was removed: settings shows "Sign in again"
        val token = r.accessToken ?: return
        val p = prefs(ctx)
        val mine = local(ctx)
        val changedHere = sig(mine) != p.getString(PREF_SIG, "")
        val (id, remote) = findRemote(token)
        val remoteUpdated = remote?.optLong("updated") ?: 0L
        val changedThere = remoteUpdated > p.getLong(PREF_REMOTE, 0)
        val firstTime = p.getString(PREF_SIG, "").isNullOrEmpty()
        if (remote != null && changedThere && (!changedHere || firstTime)) {
            apply(ctx, remote)
            p.edit().putString(PREF_SIG, sig(local(ctx))).putLong(PREF_REMOTE, remoteUpdated).apply()
            tellWatch(ctx)
            LookWallpaper.syncAsync(ctx, interactive = false)
            main.post { onPulled?.invoke() }
        } else if (changedHere || remote == null) {
            val now = System.currentTimeMillis()
            val out = JSONObject(mine.toString()).put("v", 1).put("updated", now).put("by", "phone")
            writeRemote(token, id, out)
            p.edit().putString(PREF_SIG, sig(mine)).putLong(PREF_REMOTE, now).apply()
        }
    }

    private fun http(url: String, token: String, method: String = "GET", type: String? = null, body: ByteArray? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw IllegalStateException("Drive $code")
        return text
    }

    private fun findRemote(token: String): Pair<String?, JSONObject?> {
        val q = URLEncoder.encode("name='$FILE'", "UTF-8")
        val list = JSONObject(http("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)", token))
        val files = list.optJSONArray("files")
        if (files == null || files.length() == 0) return null to null
        val id = files.getJSONObject(0).getString("id")
        return id to try { JSONObject(http("https://www.googleapis.com/drive/v3/files/$id?alt=media", token)) } catch (_: Exception) { null }
    }

    private fun writeRemote(token: String, id: String?, j: JSONObject) {
        val json = j.toString().toByteArray()
        if (id != null) {
            http("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", token, "PATCH", "application/json", json)
        } else {
            val b = "lembas" + System.nanoTime()
            val meta = JSONObject().put("name", FILE).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
            val body = "--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: application/json\r\n\r\n${String(json)}\r\n--$b--"
            http("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token, "POST", "multipart/related; boundary=$b", body.toByteArray())
        }
    }

    // ------------------------------------------------------------------ the watch (over the watch link, no Google sign-in needed)
    const val WATCH_PATH = "/lembas/look"

    /** Hands the look to the watch app (its list, tile and complications follow). */
    fun tellWatch(ctx: Context) {
        try {
            val p = prefs(ctx)
            val req = PutDataMapRequest.create(WATCH_PATH).apply {
                dataMap.putString("look", p.getString(Themes.PREF_THEME, Look.ADAPTIVE) ?: Look.ADAPTIVE)
                dataMap.putLong("keepUntil", p.getLong(Themes.PREF_KEEP_UNTIL, 0))
                dataMap.putString("by", "phone")
                // the sky's moods for the watch face (1 rainbow, 2 beacons, 4 smoke, 8 frost, 16 heat, 32 storm) and the forecast
                val s = Weather.sky(ctx)
                // and the calendar: 64 lanterns (Yule, Lithe), 128 fireworks (Hobbit Day evening), 256 Durin's Day, 512 the toast
                dataMap.putInt("sky", (if (s.rainbow) 1 else 0) or (if (s.beacons) 2 else 0) or (if (s.smoke > .15f) 4 else 0) or
                    (if (s.frost) 8 else 0) or (if (s.heat) 16 else 0) or (if (s.storm) 32 else 0) or
                    (if (Feasts.lanterns()) 64 else 0) or (if (Feasts.fireworks()) 128 else 0) or (if (Feasts.durin()) 256 else 0) or (if (Feasts.toast()) 512 else 0))
                dataMap.putString("day", java.time.LocalDate.now().toString() + "T" + java.time.LocalTime.now().hour)   // changes hourly, so the calendar flags reach the watch on time
                dataMap.putString("forecast", Weather.forecast(ctx))
                // the walk to Mount Doom: how far along the road, and the next place (km -1 when not walking)
                dataMap.putFloat("walk_km", if (Journey.started(ctx)) Journey.km(ctx) else -1f)
                Journey.next(ctx).let { n -> dataMap.putString("walk_next", n?.first?.name ?: ""); dataMap.putInt("walk_next_km", n?.second ?: 0) }
                dataMap.putString("walk_at", Journey.last(ctx).name)
                // and the night watch's bedtime (minutes after midnight, -1 off), so the watch face settles too
                dataMap.putInt("bedtime", p.getInt(Hearth.PREF_WATCH, Hearth.DEFAULT_BEDTIME))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(req)
        } catch (_: Throwable) { }   // no Google Play services, or no watch: nothing to do
    }
}

/** A look chosen on the watch: the phone takes it, and passes it on to the PC through Google. */
class WatchLookListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != GoogleSync.WATCH_PATH) continue
            val m = DataMapItem.fromDataItem(e.dataItem).dataMap
            if (m.getString("by") != "watch") continue
            val look = m.getString("look") ?: continue
            val p = getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
            if (p.getString(Themes.PREF_THEME, Look.ADAPTIVE) == look) continue
            p.edit().putString(Themes.PREF_THEME, look).putLong(Themes.PREF_KEEP_UNTIL, m.getLong("keepUntil", 0)).commit()
            LookWallpaper.syncAsync(this, interactive = false)
            GoogleSync.sync(this, force = true)
            Handler(Looper.getMainLooper()).post { GoogleSync.onPulled?.invoke() }
        }
    }
}
