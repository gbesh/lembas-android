package com.middleearth.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * The one-time setup that lets Lembas theme the whole phone, done entirely on the phone:
 *
 *  1. Developer options on (Settings → About phone → tap Build number seven times).
 *  2. Lembas opens Wireless debugging; you tap "Pair device with pairing code".
 *  3. You type the six-digit code into the Lembas notification (without leaving that screen).
 *  4. Lembas pairs with the phone's own adb, grants itself "write secure settings", and
 *     immediately sets the colours and dark theme for the current look.
 *
 * After that, Wireless debugging can be turned off again; the permission stays.
 */
object ThemeSetup {
    private const val CHANNEL = "setup"
    private const val NOTE_ID = 0x1e5
    const val KEY_CODE = "code"
    private const val GRANT = "pm grant com.middleearth.launcher android.permission.WRITE_SECURE_SETTINGS"

    fun developerOptionsOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** From the settings sheet. */
    fun start(activity: Activity) {
        if (SystemTheme.permitted(activity)) {
            SystemTheme.setEnabled(activity, true)
            toast(activity, "Lembas already themes the whole phone")
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            AlertDialog.Builder(activity).setTitle("Theme the whole phone")
                .setMessage("This needs Android 11 or later on the phone itself. On older Android, connect the phone to a computer once and run:\n\nadb shell $GRANT")
                .setPositiveButton("OK", null).show()
            return
        }
        if (!developerOptionsOn(activity)) {
            AlertDialog.Builder(activity).setTitle("Theme the whole phone")
                .setMessage("First, turn on Developer options: in About phone, tap Build number seven times (it asks for your PIN). Then come back and choose this again.")
                .setPositiveButton("Open About phone") { _, _ -> open(activity, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                .setNegativeButton("Not now", null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x1e5)
        }
        AlertDialog.Builder(activity).setTitle("Theme the whole phone")
            .setMessage("Lembas will open Developer options.\n\n" +
                "1. Turn on Wireless debugging (on Wi-Fi) and open it.\n" +
                "2. Tap \"Pair device with pairing code\".\n" +
                "3. Pull down the notification from Lembas and type the six-digit code into it.\n\n" +
                "That's all: the colours and dark theme follow the looks from then on, even with the animated wallpaper. " +
                "Afterwards you can turn Wireless debugging off again.")
            .setPositiveButton("Start") { _, _ ->
                notifyAsk(activity, null)
                open(activity, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
            }
            .setNegativeButton("Not now", null).show()
    }

    private fun open(activity: Activity, intent: Intent) {
        try { activity.startActivity(intent) } catch (_: Exception) {
            try { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun toast(ctx: Context, text: String) =
        android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lembas setup", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    /** The notification you type the pairing code into. */
    fun notifyAsk(ctx: Context, problem: String?) {
        val nm = channel(ctx)
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, CodeReceiver::class.java).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(null, "Enter pairing code", pi).addRemoteInput(input).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lembas)
            .setContentTitle(problem ?: "Lembas: enter the pairing code")
            .setContentText("In Wireless debugging, tap \"Pair device with pairing code\", then type the code here.")
            .setStyle(Notification.BigTextStyle().bigText(
                (problem?.let { "$it\n\n" } ?: "") +
                    "In Wireless debugging, tap \"Pair device with pairing code\", then type the six-digit code here."))
            .addAction(action)
            .setOngoing(true)
            .build()
        nm.notify(NOTE_ID, n)
    }

    private fun notifyDone(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lembas)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        channel(ctx).notify(NOTE_ID, n)
    }

    /** Pair with the phone's own adb, grant the permission, and theme the phone. Runs off the main thread. */
    fun pairAndGrant(ctx: Context, code: String): String? {
        val link = AdbLink.phone(ctx)
        val service = AdbLink.discover(ctx, AdbLink.PAIRING, 8000)
            ?: return "Couldn't find the pairing screen. Keep \"Pair device with pairing code\" open, on Wi-Fi, and try again."
        try {
            link.pair(service.first.hostAddress ?: "127.0.0.1", service.second, code.trim())
        } catch (e: Exception) {
            return "That code didn't work. Check it and try again (a new code appears if you reopen the pairing screen)."
        }
        try {
            if (!link.connectTls(ctx, 10_000)) return "Paired, but couldn't connect. Keep Wireless debugging on and try again."
            link.shell(GRANT)
        } catch (e: Exception) {
            return "Paired, but couldn't connect. Keep Wireless debugging on and try again."
        } finally {
            try { link.disconnect() } catch (_: Exception) { }
        }
        if (!SystemTheme.permitted(ctx)) return "Android didn't accept the permission. Try again, or use a computer: adb shell $GRANT"
        SystemTheme.setEnabled(ctx, true)
        LookWallpaper.syncAsync(ctx)
        return null
    }

    /** The code typed into the notification. */
    class CodeReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() }
            if (code.isNullOrEmpty()) { notifyAsk(ctx, "Type the six-digit pairing code"); return }
            val app = ctx.applicationContext
            notifyDone(app, "Lembas: pairing…", "Connecting to this phone's Wireless debugging.")
            val result = goAsync()
            Thread {
                val problem = try { pairAndGrant(app, code) } catch (e: Throwable) { "Something went wrong: ${e.message}" }
                if (problem == null) notifyDone(app, "Lembas now themes the whole phone",
                    "The colours and light or dark theme follow the looks from now on. You can turn Wireless debugging off again.")
                else notifyAsk(app, problem)
                result.finish()
            }.start()
        }
    }
}
