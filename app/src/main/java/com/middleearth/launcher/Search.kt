package com.middleearth.launcher

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** One line in the search results. */
sealed class Hit {
    class Header(val title: String) : Hit()
    class App(val e: AppEntry) : Hit()
    class Shortcut(val s: ShortcutInfo, val app: AppEntry?) : Hit()
    class Contact(val id: Long, val lookup: String, val name: String, val photo: String?, val hasPhone: Boolean) : Hit()
    class Calc(val expr: String, val result: String) : Hit()
    class Action(val title: String, val subtitle: String, val icon: Int, val run: (View?) -> Unit) : Hit()
}

/**
 * Search, as you type, across everything worth finding from the home screen:
 * a quick sum, your apps (hidden ones too; by name, a word in the name, or initials),
 * the apps' own shortcuts (New incognito tab, Take a selfie…), your contacts (with call
 * and message buttons), and then Google and the Play Store for anything else. When no app
 * matches, the Play Store comes first, so installing something is one tap away.
 */
class SearchEngine(private val activity: Activity, private val repo: AppRepository) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "lembas-search").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shortcuts: List<Pair<ShortcutInfo, AppEntry?>> = emptyList()
    @Volatile private var shortcutsLoadedAt = 0L
    private var generation = 0

    val contactsAllowed get() = activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Called when the drawer opens: refresh the apps' shortcuts in the background (cheap, a few times an hour at most). */
    fun warmUp() {
        if (System.currentTimeMillis() - shortcutsLoadedAt < 10 * 60_000L) return
        shortcutsLoadedAt = System.currentTimeMillis()
        val la = repo.launcherApps
        val apps = repo.allApps
        exec.execute {
            val out = ArrayList<Pair<ShortcutInfo, AppEntry?>>()
            try {
                if (la.hasShortcutHostPermission()) for ((pkg, entries) in apps.groupBy { it.packageName to it.user }) {
                    val q = LauncherApps.ShortcutQuery().setPackage(pkg.first)
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                    val list = try { la.getShortcuts(q, pkg.second) } catch (_: Throwable) { null } ?: continue
                    for (s in list) if (s.isEnabled) out.add(s to (entries.firstOrNull { it.component == s.activity } ?: entries.first()))
                }
            } catch (_: Throwable) { }
            shortcuts = out
        }
    }

    /** Work out the results for [query]; [done] gets them on the main thread (twice: at once, and again with contacts). */
    fun search(query: String, done: (List<Hit>) -> Unit) {
        val gen = ++generation
        val q = query.trim()
        if (q.isEmpty()) { done(emptyList()); return }
        val n = AppEntry.normalize(q)
        val apps = matchApps(n)
        val sc = matchShortcuts(n)
        val calc = Calculator.evaluate(q)
        done(assemble(q, calc, apps, sc, null))
        if (contactsAllowed && q.length >= 2) exec.execute {
            val contacts = queryContacts(q)
            main.post { if (gen == generation) done(assemble(q, calc, apps, sc, contacts)) }
        }
    }

    private fun assemble(q: String, calc: String?, apps: List<AppEntry>, sc: List<Pair<ShortcutInfo, AppEntry?>>, contacts: List<Hit.Contact>?): List<Hit> {
        val out = ArrayList<Hit>()
        if (calc != null) out.add(Hit.Calc(q, calc))
        if (apps.isNotEmpty()) { out.add(Hit.Header("Apps")); apps.mapTo(out) { Hit.App(it) } }
        if (sc.isNotEmpty()) { out.add(Hit.Header("Shortcuts")); sc.mapTo(out) { Hit.Shortcut(it.first, it.second) } }
        if (!contacts.isNullOrEmpty()) { out.add(Hit.Header("Contacts")); out.addAll(contacts) }
        out.add(Hit.Header("Search"))
        val google = Hit.Action("Search Google for “$q”", "Google", R.drawable.g_search) { webSearch(q) }
        val store = Hit.Action("Search the Play Store for “$q”", if (apps.isEmpty()) "No app called that yet" else "Play Store", R.drawable.g_store) { playStore(q) }
        if (apps.isEmpty()) { out.add(store); out.add(google) } else { out.add(google); out.add(store) }
        if (!contactsAllowed && q.length >= 2) out.add(Hit.Action("Search your contacts too", "Lembas asks once", R.drawable.g_contacts) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        })
        return out
    }

    /** Name starts with it, then a word in the name does, then initials ("gm" = Google Maps), then anywhere in the name. */
    private fun matchApps(n: String): List<AppEntry> {
        val starts = ArrayList<AppEntry>(); val words = ArrayList<AppEntry>(); val initials = ArrayList<AppEntry>(); val contains = ArrayList<AppEntry>()
        for (e in repo.allApps) {
            val s = e.searchNorm
            when {
                s.startsWith(n) -> starts.add(e)
                s.contains(" $n") -> words.add(e)
                n.length >= 2 && initialsOf(e.norm).startsWith(n) -> initials.add(e)
                s.contains(n) -> contains.add(e)
            }
        }
        return (starts + words + initials + contains).take(8)
    }

    private fun initialsOf(s: String) = s.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    private fun matchShortcuts(n: String): List<Pair<ShortcutInfo, AppEntry?>> {
        if (n.length < 2) return emptyList()
        return shortcuts.filter {
            val l = AppEntry.normalize(it.first.shortLabel?.toString() ?: "")
            l.startsWith(n) || l.contains(" $n")
        }.take(4)
    }

    private fun queryContacts(q: String): List<Hit.Contact> = try {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        activity.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER), null, null, null)?.use { c ->
            val out = ArrayList<Hit.Contact>()
            while (c.moveToNext() && out.size < 5) {
                out.add(Hit.Contact(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3), c.getInt(4) > 0))
            }
            out
        } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    // ------------------------------------------------------------------ what a tap does
    fun webSearch(q: String) {
        val tries = listOf(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
        )
        open(tries)
    }

    fun playStore(q: String) = open(listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(q) + "&c=apps")),
    ))

    fun openContact(c: Hit.Contact) = open(listOf(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(c.id, c.lookup))))

    /** Call or message the contact's main number. */
    fun reach(c: Hit.Contact, sms: Boolean) {
        exec.execute {
            val number = try {
                activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(c.id.toString()),
                    "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Throwable) { null }
            main.post {
                if (number == null) { openContact(c); return@post }
                open(listOf(if (sms) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))))
            }
        }
    }

    fun copy(result: String) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Result", result))
        Toast.makeText(activity, "Copied $result", Toast.LENGTH_SHORT).show()
    }

    private fun open(intents: List<Intent>) {
        for (i in intents) try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) { }
        Toast.makeText(activity, "No app can open that", Toast.LENGTH_SHORT).show()
    }

    /** A contact's photo, or their initial in a circle. */
    fun contactBitmap(c: Hit.Contact, size: Int, color: Int, ink: Int, tf: Typeface): Bitmap {
        c.photo?.let { p ->
            try {
                activity.contentResolver.openInputStream(Uri.parse(p))?.use { s ->
                    BitmapFactory.decodeStream(s)?.let { return circle(Bitmap.createScaledBitmap(it, size, size, true)) }
                }
            } catch (_: Throwable) { }
        }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = ink; p.typeface = tf; p.textSize = size * 0.46f; p.textAlign = Paint.Align.CENTER
        val ch = c.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        cv.drawText(ch, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return b
    }

    private fun circle(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawCircle(src.width / 2f, src.height / 2f, src.width / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, 0f, 0f, p)
        return b
    }

    companion object { const val REQUEST_CONTACTS = 0xc0c }
}

/** Shows the search results in the Red Book list, in the look's own type and colours. */
class SearchAdapter(
    private val activity: Activity,
    private val look: Look,
    private val icons: IconCache,
    private val iconSpec: IconCache.Spec,
    private val engine: SearchEngine,
) : BaseAdapter() {
    var hits: List<Hit> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()
    private val rowFont: Typeface = Fonts.get(activity.assets, look.displayFont, 400)
    private val labelFont: Typeface = Fonts.get(activity.assets, look.labelFont, 500)
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun getCount() = hits.size
    override fun getItem(position: Int): Hit = hits[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (hits[position] is Hit.Header) 0 else 1
    override fun isEnabled(position: Int) = hits[position] !is Hit.Header
    override fun areAllItemsEnabled() = false

    /** The first thing Enter should open. */
    fun first(): Hit? = hits.firstOrNull { it !is Hit.Header }

    private class Row(val icon: ImageView, val title: TextView, val sub: TextView, val call: ImageView, val text: ImageView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val h = hits[position]
        if (h is Hit.Header) {
            val tv = (convertView as? TextView) ?: TextView(activity).apply {
                typeface = labelFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.18f
                setTextColor(look.accent)
                setPadding(px(44f), px(16f), px(44f), px(4f))
            }
            tv.text = h.title.uppercase()
            return tv
        }
        val v = convertView ?: makeRow(parent)
        val r = v.tag as Row
        r.icon.foreground = null
        r.icon.imageTintList = null
        r.call.visibility = View.GONE
        r.text.visibility = View.GONE
        r.icon.tag = null
        when (h) {
            is Hit.App -> {
                r.title.text = h.e.label
                r.sub.text = if (AppRepository.get(activity).isHidden(h.e)) "Hidden from the list" else ""
                icons.bind(h.e, iconSpec, r.icon)
            }
            is Hit.Shortcut -> {
                r.title.text = h.s.shortLabel ?: h.s.longLabel ?: ""
                r.sub.text = h.app?.label ?: ""
                r.icon.setImageDrawable(null)
                val tag = h.s.id + h.s.`package`
                r.icon.tag = tag
                bg.execute {
                    val d = try { AppRepository.get(activity).launcherApps.getShortcutIconDrawable(h.s, activity.resources.displayMetrics.densityDpi) } catch (_: Throwable) { null }
                    main.post { if (r.icon.tag == tag) r.icon.setImageDrawable(d) }
                }
            }
            is Hit.Contact -> {
                r.title.text = h.name
                r.sub.text = ""
                r.icon.setImageDrawable(null)
                val tag = "c" + h.id
                r.icon.tag = tag
                val size = px(38f)
                bg.execute {
                    val b = engine.contactBitmap(h, size, (look.accent and 0x00FFFFFF) or 0x33000000, look.accent, labelFont)
                    main.post { if (r.icon.tag == tag) r.icon.setImageBitmap(b) }
                }
                if (h.hasPhone) {
                    r.call.visibility = View.VISIBLE
                    r.text.visibility = View.VISIBLE
                    r.call.setOnClickListener { engine.reach(h, sms = false) }
                    r.text.setOnClickListener { engine.reach(h, sms = true) }
                }
            }
            is Hit.Calc -> {
                r.title.text = "= ${h.result}"
                r.sub.text = "${h.expr}  ·  tap to copy"
                glyph(r.icon, R.drawable.g_calculator)
            }
            is Hit.Action -> {
                r.title.text = h.title
                r.sub.text = h.subtitle
                glyph(r.icon, h.icon)
            }
            is Hit.Header -> Unit
        }
        r.sub.visibility = if (r.sub.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        return v
    }

    private fun glyph(iv: ImageView, res: Int) {
        iv.setImageResource(res)
        iv.imageTintList = ColorStateList.valueOf(look.accent)
        iv.setPadding(px(7f), px(7f), px(7f), px(7f))
        iv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((look.accent and 0x00FFFFFF) or 0x1F000000) }
    }

    private fun makeRow(parent: ViewGroup): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56f)
            setPadding(px(44f), px(6f), px(36f), px(6f))
            val tv = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val icon = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(px(38f), px(38f)) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16f) }
        }
        val title = TextView(activity).apply {
            typeface = rowFont; setTextColor(look.rowText); setTextSize(TypedValue.COMPLEX_UNIT_SP, look.rowTextSizeSp)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(activity).apply {
            typeface = labelFont; setTextColor(look.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(title); col.addView(sub)
        fun button(res: Int, desc: String) = ImageView(activity).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(look.accent)
            setPadding(px(10f), px(10f), px(10f), px(10f))
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            contentDescription = desc
            val tv = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            visibility = View.GONE
        }
        val call = button(R.drawable.g_phone, "Call")
        val text = button(R.drawable.g_message, "Message")
        row.addView(icon); row.addView(col); row.addView(call); row.addView(text)
        row.tag = Row(icon, title, sub, call, text)
        return row
    }
}

/** A small calculator for sums typed into search: + − × ÷ ^ %, brackets and decimals. */
object Calculator {
    private val looksLikeSum = Regex("^[0-9.\\s()+\\-*/×÷x^%,]+$")

    fun evaluate(input: String): String? {
        val s = input.replace('×', '*').replace('÷', '/').replace(',', '.').replace(" x ", " * ").replace("x", "*")
        if (!looksLikeSum.matches(input) || !s.any { it in "+-*/^%" } || !s.any { it.isDigit() }) return null
        return try {
            val p = Parser(s.replace(" ", ""))
            val v = p.expr()
            if (p.pos != p.s.length || v.isNaN() || v.isInfinite()) null else format(v)
        } catch (_: Exception) { null }
    }

    private fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return "%,d".format(v.toLong())
        val bd = java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros()
        return bd.toPlainString()
    }

    private class Parser(val s: String) {
        var pos = 0
        fun expr(): Double {
            var v = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) { val op = s[pos++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/' || s[pos] == '%')) {
                val op = s[pos++]
                if (op == '%' && (pos >= s.length || s[pos] in "+-*/)")) { v /= 100; continue }   // "50%" means half
                val r = factor()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        fun factor(): Double {
            var v = unary()
            if (pos < s.length && s[pos] == '^') { pos++; v = Math.pow(v, factor()) }
            return v
        }
        fun unary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -unary() }
            if (pos < s.length && s[pos] == '+') { pos++; return unary() }
            if (pos < s.length && s[pos] == '(') { pos++; val v = expr(); if (pos < s.length && s[pos] == ')') pos++; return v }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
    }
}
