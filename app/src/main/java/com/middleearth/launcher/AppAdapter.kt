package com.middleearth.launcher

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.util.Arrays

/** The app drawer's A–Z list. Same layout in every theme; colours and fonts come from the Look. */
class AppAdapter(
    private val context: Context,
    private val look: Look,
    private val icons: IconCache,
    private val iconSpec: IconCache.Spec,
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""
    private var sectionStarts = IntArray(0)
    private var sectionLetters = CharArray(0)

    private val rowFont: Typeface = Fonts.get(context.assets, look.displayFont, 400)
    private val sectionFont: Typeface = if (look.sectionUsesDisplay)
        Fonts.get(context.assets, look.displayFont) else Fonts.get(context.assets, look.labelFont, 500)

    private class Holder(val section: TextView, val label: TextView, val icon: ImageView)

    fun setApps(apps: List<AppEntry>) {
        all = apps
        refilter()
    }

    fun setQuery(q: String) {
        val n = AppEntry.normalize(q.trim())
        if (n == query) return
        query = n
        refilter()
    }

    val isSearching get() = query.isNotEmpty()

    fun first(): AppEntry? = shown.firstOrNull()

    /** Letters that have at least one app, for the letter strips. */
    fun presentLetters(): Set<Char> = sectionLetters.toSet()

    private fun refilter() {
        shown = if (query.isEmpty()) all else {
            val starts = ArrayList<AppEntry>()
            val words = ArrayList<AppEntry>()
            val contains = ArrayList<AppEntry>()
            for (e in all) when {
                e.norm.startsWith(query) -> starts.add(e)
                e.norm.contains(" $query") -> words.add(e)
                e.norm.contains(query) -> contains.add(e)
            }
            starts + words + contains
        }
        buildSections()
        notifyDataSetChanged()
    }

    fun letterOf(e: AppEntry): Char {
        val ch = e.norm.firstOrNull() ?: return '#'
        return if (ch in 'a'..'z') ch.uppercaseChar() else '#'
    }

    private fun buildSections() {
        val starts = ArrayList<Int>()
        val letters = StringBuilder()
        if (query.isEmpty()) {
            var last = '\u0000'
            shown.forEachIndexed { i, e ->
                val l = letterOf(e)
                if (l != last) {
                    starts.add(i); letters.append(l); last = l
                }
            }
        }
        sectionStarts = starts.toIntArray()
        sectionLetters = letters.toString().toCharArray()
    }

    /** List position for a letter: that letter's section, or the next one that exists. */
    fun positionForLetter(c: Char): Int {
        if (sectionLetters.isEmpty()) return 0
        if (c == '#') {
            val i = sectionLetters.indexOf('#')
            return if (i >= 0) sectionStarts[i] else shown.size - 1
        }
        for (i in sectionLetters.indices) {
            val l = sectionLetters[i]
            if (l != '#' && l >= c) return sectionStarts[i]
        }
        return sectionStarts.last()
    }

    fun letterAt(position: Int): Char {
        if (sectionStarts.isEmpty()) return 'A'
        var i = Arrays.binarySearch(sectionStarts, position)
        if (i < 0) i = -i - 2
        return sectionLetters[i.coerceIn(0, sectionLetters.size - 1)]
    }

    private fun isSectionStart(pos: Int) = query.isEmpty() && Arrays.binarySearch(sectionStarts, pos) >= 0

    override fun getCount() = shown.size
    override fun getItem(position: Int): AppEntry = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: createView(parent)
        val h = view.tag as Holder
        val e = shown[position]
        h.label.text = e.label
        view.findViewById<View>(R.id.row).contentDescription = e.label
        if (isSectionStart(position)) {
            h.section.visibility = View.VISIBLE
            h.section.text = letterOf(e).toString()
        } else h.section.visibility = View.GONE
        icons.bind(e, iconSpec, h.icon)
        return view
    }

    private fun createView(parent: ViewGroup): View {
        val v = inflater.inflate(R.layout.item_list, parent, false)
        val label = v.findViewById<TextView>(R.id.label)
        label.typeface = rowFont
        label.setTextColor(look.rowText)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.rowTextSizeSp)
        val section = v.findViewById<TextView>(R.id.section)
        section.typeface = sectionFont
        section.setTextColor(look.accent)
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sectionSizeSp)
        if (!look.sectionUsesDisplay) section.letterSpacing = 0.2f
        v.tag = Holder(section, label, v.findViewById(R.id.icon))
        return v
    }
}
