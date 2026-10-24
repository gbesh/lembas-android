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

class AppEntry(val info: LauncherActivityInfo, val user: UserHandle, serial: Long, renamed: String? = null) {
    val component: ComponentName = info.componentName
    val packageName: String get() = component.packageName
    /** The app's own name, before any renaming. */
    val originalLabel: String = info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: component.packageName
    val label: String = renamed?.takeIf { it.isNotBlank() } ?: originalLabel
    val key: String = component.flattenToString() + "|" + serial
    /** Words to search on: the shown name and, if renamed, the original one too. */
    val searchNorm: String get() = if (label == originalLabel) norm else norm + " " + normalize(originalLabel)
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

    /** The apps shown in the list (hidden ones left out). */
    var apps: List<AppEntry> = emptyList()
        private set
    /** Everything, hidden apps included (search finds those too). */
    var allApps: List<AppEntry> = emptyList()
        private set
    var hidden: List<AppEntry> = emptyList()
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
        val prefs = context.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val hiddenKeys = prefs.getStringSet(PREF_HIDDEN, emptySet()) ?: emptySet()
        val renames = HashMap<String, String>()
        try {
            val j = org.json.JSONObject(prefs.getString(PREF_RENAMED, "{}") ?: "{}")
            for (k in j.keys()) renames[k] = j.getString(k)
        } catch (_: Exception) { }
        exec.execute {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            val list = ArrayList<AppEntry>()
            for (user in userManager.userProfiles) {
                try {
                    val serial = userManager.getSerialNumberForUser(user)
                    for (info in launcherApps.getActivityList(null, user)) {
                        if (info.componentName.packageName == context.packageName) continue
                        val key = info.componentName.flattenToString() + "|" + serial
                        list.add(AppEntry(info, user, serial, renames[key]))
                    }
                } catch (t: Throwable) {
                    // A locked or paused work profile can throw; just skip it.
                }
            }
            list.sortWith { a, b -> collator.compare(a.label, b.label) }
            main.post {
                if (gen != generation) return@post
                allApps = list
                apps = list.filter { it.key !in hiddenKeys }
                hidden = list.filter { it.key in hiddenKeys }
                byKey = list.associateBy { it.key }
                loaded = true
                for (l in listeners.toList()) l.onAppsChanged()
            }
        }
    }

    fun isHidden(e: AppEntry) = hidden.any { it.key == e.key }

    /** Hide an app from the list (or show it again). It still turns up in search. */
    fun setHidden(e: AppEntry, hide: Boolean) {
        val prefs = context.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet(PREF_HIDDEN, emptySet()) ?: emptySet())
        if (hide) set.add(e.key) else set.remove(e.key)
        prefs.edit().putStringSet(PREF_HIDDEN, set).apply()
        reload()
    }

    /** Give an app your own name (blank puts its own name back). */
    fun rename(e: AppEntry, name: String) {
        val prefs = context.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val j = try { org.json.JSONObject(prefs.getString(PREF_RENAMED, "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        if (name.isBlank() || name.trim() == e.originalLabel) j.remove(e.key) else j.put(e.key, name.trim())
        prefs.edit().putString(PREF_RENAMED, j.toString()).apply()
        reload()
    }

    companion object {
        const val PREF_HIDDEN = "hidden_apps"
        const val PREF_RENAMED = "renamed_apps"
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
