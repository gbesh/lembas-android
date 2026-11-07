package com.middleearth.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * The Horn of Gondor: find your phone from the watch. The phone sounds the horn call on the alarm
 * stream (so it's heard even on silent; your alarm volume, raised to at least half), flashes its
 * torch, and leaves a note so you can see it was the horn. Runs for about ten seconds.
 */
object Horn {
    @Volatile private var blowing = false

    /** Blow the horn (blocks for its length; called on the watch listener's own thread). */
    fun blow(ctx: Context) {
        if (blowing) return
        blowing = true
        val am = ctx.getSystemService(AudioManager::class.java)
        val was = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
        try {
            if (am != null && was < max / 2) am.setStreamVolume(AudioManager.STREAM_ALARM, max * 3 / 4, 0)
            note(ctx)
            val cam = ctx.getSystemService(CameraManager::class.java)
            val torch = try { cam?.cameraIdList?.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } catch (_: Exception) { null }
            repeat(2) { round ->
                val mp = MediaPlayer()
                try {
                    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    ctx.resources.openRawResourceFd(R.raw.lembas_horn_call).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    mp.prepare(); mp.start()
                    val end = System.currentTimeMillis() + maxOf(mp.duration, 3000) + 400
                    var on = false
                    while (System.currentTimeMillis() < end) {   // the torch flashing while the horn sounds
                        on = !on
                        try { if (torch != null) cam?.setTorchMode(torch, on) } catch (_: Exception) { }
                        Thread.sleep(if (on) 180 else 260)
                    }
                    try { if (torch != null) cam?.setTorchMode(torch, false) } catch (_: Exception) { }
                } catch (_: Exception) { } finally { mp.release() }
                if (round == 0) Thread.sleep(700)
            }
        } finally {
            if (am != null && was < max / 2) try { am.setStreamVolume(AudioManager.STREAM_ALARM, was, 0) } catch (_: Exception) { }
            blowing = false
        }
    }

    private fun note(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("horn") == null)
            nm.createNotificationChannel(NotificationChannel("horn", "Horn of Gondor", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val n = Notification.Builder(ctx, "horn")
            .setSmallIcon(R.drawable.g_clock)
            .setContentTitle("The Horn of Gondor")
            .setContentText("Sounded from your watch. Here I am!")
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).setTimeoutAfter(5 * 60_000L).build()
        try { nm.notify(7303, n) } catch (_: SecurityException) { }
    }
}
