package com.middleearth.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Double-tap to lock. Android lets an app lock the screen (keeping fingerprint and face unlock
 * working, unlike the old device-admin way) only through an accessibility service, so this is a
 * tiny one that does nothing else: it reads no screen content and listens to no events.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val PREF_ON = "double_tap_lock"
        @Volatile private var instance: LockService? = null

        fun component(ctx: Context) = ComponentName(ctx, LockService::class.java)

        fun serviceOn(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component(ctx) } == true

        fun enabled(ctx: Context) = Build.VERSION.SDK_INT >= 28 &&
            ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, false) && serviceOn(ctx)

        /** Returns false if you need to switch it on in Settings > Accessibility yourself. */
        fun turnOn(ctx: Context): Boolean {
            ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, true).apply()
            if (serviceOn(ctx)) return true
            if (SystemTheme.permitted(ctx)) try {
                val cr = ctx.contentResolver
                val now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val me = component(ctx).flattenToString()
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (now.isEmpty()) me else "$now:$me")
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                return true
            } catch (_: Throwable) { }
            return false
        }

        fun turnOff(ctx: Context) {
            ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, false).apply()
            if (SystemTheme.permitted(ctx)) try {
                val cr = ctx.contentResolver
                val me = component(ctx)
                val rest = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    .split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }.joinToString(":")
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest)
            } catch (_: Throwable) { }
        }

        fun lock(): Boolean = Build.VERSION.SDK_INT >= 28 && instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
    }
}
