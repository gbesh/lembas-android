package com.middleearth.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Back up and restore Lembas's settings (look, clock style, dock and pinned apps, hidden and
 * renamed apps, icon choices, everything in settings) to a small file you keep anywhere: your
 * phone's storage, Drive, an email to yourself. Restoring on a new phone puts it all back.
 * Nothing leaves the phone unless you send the file somewhere.
 */
object Backup {
    const val REQUEST_SAVE = 0xbac1
    const val REQUEST_OPEN = 0xbac2
    // things that belong to this phone or this moment, never carried over
    private val SKIP = setOf("weather_code", "weather_at", "fx_rates", "fx_at", "last_painted", "keep_until", "wallpaper_last")
    private fun skip(k: String) = k in SKIP || k.startsWith("last_") || k.startsWith("adb_") || k.startsWith("update_")

    fun save(a: Activity) = try {
        a.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "Lembas settings.json"), REQUEST_SAVE)
    } catch (_: Exception) { Toast.makeText(a, "No file picker on this phone", Toast.LENGTH_SHORT).show() }

    fun open(a: Activity) = try {
        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"), REQUEST_OPEN)
    } catch (_: Exception) { Toast.makeText(a, "No file picker on this phone", Toast.LENGTH_SHORT).show() }

    /** From onActivityResult. True if it was ours; [restored] runs after a successful restore. */
    fun onResult(a: Activity, request: Int, result: Int, data: Intent?, restored: () -> Unit): Boolean {
        if (request != REQUEST_SAVE && request != REQUEST_OPEN) return false
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) return true
        if (request == REQUEST_SAVE) write(a, uri) else if (read(a, uri)) restored()
        return true
    }

    private fun write(ctx: Context, uri: Uri) {
        val p = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val vals = JSONObject()
        for ((k, v) in p.all) {
            if (skip(k) || v == null) continue
            vals.put(k, JSONObject().apply {
                when (v) {
                    is Boolean -> { put("t", "b"); put("v", v) }
                    is Int -> { put("t", "i"); put("v", v) }
                    is Long -> { put("t", "l"); put("v", v) }
                    is Float -> { put("t", "f"); put("v", v.toDouble()) }
                    is String -> { put("t", "s"); put("v", v) }
                    is Set<*> -> { put("t", "S"); put("v", JSONArray(v.map { it.toString() })) }
                }
            })
        }
        val json = JSONObject().put("lembas", 1).put("values", vals).toString(1)
        try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { Toast.makeText(ctx, "Couldn't save there", Toast.LENGTH_SHORT).show() }
    }

    private fun read(ctx: Context, uri: Uri): Boolean {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return false
            val root = JSONObject(text)
            if (root.optInt("lembas") != 1) throw IllegalArgumentException()
            val vals = root.getJSONObject("values")
            val e = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit()
            for (k in vals.keys()) {
                if (skip(k)) continue
                val o = vals.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    "S" -> { val a = o.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            e.commit()
            Toast.makeText(ctx, "Settings restored", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(ctx, "That isn't a Lembas settings file", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
