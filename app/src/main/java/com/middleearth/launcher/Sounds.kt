package com.middleearth.launcher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

/**
 * Lembas sounds, the same family as Lembas for Windows: a harp pluck and two bells for
 * notifications, and a horn call for alarms. "Add Lembas sounds" puts them in your phone's own
 * sound lists (Settings > Sound > Default notification sound, or any app's notification settings),
 * so you can pick them like any other sound. Nothing is changed until you choose one.
 */
object Sounds {
    private class S(val res: Int, val title: String, val alarm: Boolean)
    private val ALL = listOf(
        S(R.raw.lembas_harp, "Lembas – Harp", false),
        S(R.raw.lembas_bell, "Lembas – Bell", false),
        S(R.raw.lembas_bells, "Lembas – Two bells", false),
        S(R.raw.lembas_horn_call, "Lembas – Horn call", true),
    )

    fun install(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(ctx, "Lembas sounds need Android 10 or later", Toast.LENGTH_SHORT).show(); return
        }
        var added = 0
        for (s in ALL) try {
            val dir = if (s.alarm) "Alarms/Lembas" else "Notifications/Lembas"
            val name = s.title.replace(" – ", " - ") + ".ogg"
            val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // already there?
            ctx.contentResolver.query(coll, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(name), null)?.use {
                if (it.moveToFirst()) { added++; return@use }
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.TITLE, s.title)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, dir)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, !s.alarm)
                    put(MediaStore.Audio.Media.IS_ALARM, s.alarm)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(coll, values) ?: return@use
                ctx.contentResolver.openOutputStream(uri)?.use { out -> ctx.resources.openRawResource(s.res).use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                added++
            }
        } catch (_: Exception) { }
        Toast.makeText(ctx, if (added > 0) "Lembas sounds added: choose one in Sound settings" else "Couldn't add the sounds", Toast.LENGTH_LONG).show()
        try { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}
