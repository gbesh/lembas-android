package com.middleearth.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class AppEntry(val info: LauncherActivityInfo, val user: UserHandle, serial: Long) {
    val component: ComponentName = info.componentName
    val packageName: String get() = component.packageName
    val label: String = info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: component.packageName
    val key: String = component.flattenToString() + "|" + serial
    val norm: String = normalize(label)
    val isMainUser: Boolean = user == Process.myUserHandle()
    val glyph: Int = Glyphs.forApp(component.packageName, label)

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}

/**
 * Keeps the list of launchable apps (including work-profile apps) and refreshes it
 * when apps are installed, updated or removed. Loading happens off the main thread.
 * One instance for the whole process, so switching themes doesn't reload anything.
 */
class AppRepository private constructor(private val context: Context) {

    val launcherApps: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager: UserManager = context.getSystemService(UserManager::class.java)
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }.apply { name = "lembas-apps"; isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private val listeners = ArrayList<Listener>()

    var apps: List<AppEntry> = emptyList()
        private set
    var byKey: Map<String, AppEntry> = emptyMap()
        private set
    var loaded = false
        private set

    interface Listener {
        fun onAppsChanged()
        fun onPackageChanged(packageName: String) {}
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            changed(*packageNames)
    }

    private fun changed(vararg packages: String) {
        for (p in packages) for (l in listeners.toList()) l.onPackageChanged(p)
        reload()
    }

    fun reload() {
        val gen = ++generation
        exec.execute {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            val list = ArrayList<AppEntry>()
            for (user in userManager.userProfiles) {
                try {
                    val serial = userManager.getSerialNumberForUser(user)
                    for (info in launcherApps.getActivityList(null, user)) {
                        if (info.componentName.packageName == context.packageName) continue
                        list.add(AppEntry(info, user, serial))
                    }
                } catch (t: Throwable) {
                    // A locked or paused work profile can throw; just skip it.
                }
            }
            list.sortWith { a, b -> collator.compare(a.label, b.label) }
            main.post {
                if (gen != generation) return@post
                apps = list
                byKey = list.associateBy { it.key }
                loaded = true
                for (l in listeners.toList()) l.onAppsChanged()
            }
        }
    }

    companion object {
        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository = instance ?: synchronized(this) {
            instance ?: AppRepository(context.applicationContext).also {
                instance = it
                it.launcherApps.registerCallback(it.callback, it.main)
                it.reload()
            }
        }
    }
}
