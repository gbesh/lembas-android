package com.middleearth.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Open any app as a floating bubble (Android 11+), using Android's own bubbles (the same
 * bubbles and bubble bar as Messages or WhatsApp).
 *
 * Android only lets an app bubble its own conversations, so Lembas makes a small conversation
 * for the app you pick, whose shortcut opens that app, and asks Android to show it as a bubble,
 * already open. Android shows bubbles only for apps you've allowed; the first time, Lembas takes
 * you to that switch. Apps that insist on opening full screen may leave the bubble when they start.
 */
object Bubbles {
    private const val CHANNEL = "bubbles"

    fun supported() = Build.VERSION.SDK_INT >= 30

    /** Can Lembas show bubbles right now? */
    fun allowed(ctx: Context): Boolean {
        if (!supported()) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return false
        // the phone-wide switch (Settings → Notifications → Bubbles)
        if (Settings.Secure.getInt(ctx.contentResolver, "notification_bubbles", 1) == 0) return false
        return if (Build.VERSION.SDK_INT >= 31) nm.bubblePreference == NotificationManager.BUBBLE_PREFERENCE_ALL
        else @Suppress("DEPRECATION") nm.areBubblesAllowed()
    }

    /** Open [e] in a bubble, or explain what to switch on first. */
    fun open(activity: Activity, e: AppEntry) {
        if (!supported()) return
        val ctx = activity.applicationContext
        // the phone-wide bubbles switch: Lembas can turn it on itself after the whole-phone setup
        if (Settings.Secure.getInt(ctx.contentResolver, "notification_bubbles", 1) == 0) {
            try { Settings.Secure.putInt(ctx.contentResolver, "notification_bubbles", 1) } catch (_: Exception) { }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0xb0b)
            return
        }
        if (!allowed(ctx)) { askToAllow(activity); return }
        try { post(ctx, e) } catch (t: Throwable) {
            android.widget.Toast.makeText(activity, "Couldn't open ${e.label} as a bubble", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun askToAllow(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Allow bubbles")
            .setMessage("To open apps as bubbles, let Lembas show bubbles: choose \"All conversations can bubble\" on the next screen, then come back and try again.")
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun post(ctx: Context, e: AppEntry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Apps in bubbles", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Apps you open as bubbles from Lembas"
                setAllowBubbles(true)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        val id = "bubble_" + e.packageName
        val icon = Icon.createWithAdaptiveBitmap(iconBitmap(ctx, e))
        val app = Person.Builder().setName(e.label).setIcon(icon).setKey(id).setImportant(true).build()
        val launch = (ctx.packageManager.getLaunchIntentForPackage(e.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component))
            .setAction(Intent.ACTION_MAIN)
        // the conversation's shortcut, which is what the bubble opens: the app itself
        val shortcut = ShortcutInfo.Builder(ctx, id)
            .setShortLabel(e.label)
            .setLongLived(true)
            .setIcon(icon)
            .setIntent(launch)
            .setPerson(app)
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
            .build()
        ctx.getSystemService(ShortcutManager::class.java)!!.pushDynamicShortcut(shortcut)

        val open = PendingIntent.getActivity(ctx, id.hashCode(), launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val me = Person.Builder().setName("You").build()
        val bubble = Notification.BubbleMetadata.Builder(id)
            .setDesiredHeight((560 * ctx.resources.displayMetrics.density).toInt())
            .setAutoExpandBubble(true)
            .setSuppressNotification(true)
            .build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lembas)
            .setContentTitle(e.label)
            .setContentText("Open in a bubble")
            .setStyle(Notification.MessagingStyle(me).addMessage("Open in a bubble", System.currentTimeMillis(), app))
            .setShortcutId(id)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setBubbleMetadata(bubble)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(id, 1, n)
    }

    private fun iconBitmap(ctx: Context, e: AppEntry): Bitmap {
        val size = (108 * ctx.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = e.info.getIcon(ctx.resources.displayMetrics.densityDpi)
        // adaptive bitmaps keep their middle two-thirds visible, so draw the icon there
        val inset = size / 6
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(Canvas(bmp))
        return bmp
    }

    /** Bubbles can only hold apps in this profile (not a work profile's). */
    fun canBubble(e: AppEntry) = supported() && e.user == Process.myUserHandle()
}
