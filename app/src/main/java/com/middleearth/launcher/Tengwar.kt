package com.middleearth.launcher

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * English in Tengwar, for the optional Elvish script on the date and clock (a simple general-use
 * tehtar mode). Letters map to tengwar by sound-ish spelling (th, sh, ch, ng, wh, ph as single
 * letters); each vowel rides as a tehta on the consonant after it (on a short carrier when no
 * consonant follows), a final silent e is a dot under the last letter, doubled letters get a bar
 * beneath, and n or m before a stop is a bar over it. Digits are Tengwar numerals. Codepoints follow
 * the Free Tengwar layout that the bundled Alcarin Tengwar font (Open Font License) uses. The same
 * rules are in tools/tengwar.py (watch face) and the PC app.
 */
object Tengwar {
    const val PREF = "tengwar"          // "off", "date", "both"
    const val FONT = "AlcarinTengwar.otf"

    fun mode(ctx: Context): String = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).getString(PREF, "off") ?: "off"
    fun font(assets: AssetManager): Typeface? = Fonts.get(assets, FONT).takeIf { assets.list("fonts")?.contains(FONT) == true }

    private val C = mapOf(
        "th" to 0xE008, "sh" to 0xE00A, "ch" to 0xE002, "ng" to 0xE012, "wh" to 0xE029, "ph" to 0xE009, "ck" to 0xE003, "qu" to 0xE003,
        "t" to 0xE000, "p" to 0xE001, "k" to 0xE003, "c" to 0xE003, "d" to 0xE004, "b" to 0xE005, "j" to 0xE006, "g" to 0xE007,
        "f" to 0xE009, "v" to 0xE00D, "n" to 0xE010, "m" to 0xE011, "w" to 0xE015, "l" to 0xE022, "s" to 0xE024, "z" to 0xE026,
        "h" to 0xE028, "r" to 0xE014, "x" to 0xE003, "y" to 0xE016,
    )
    private val V = mapOf('a' to 0xE040, 'e' to 0xE046, 'i' to 0xE044, 'o' to 0xE04A, 'u' to 0xE04C, 'y' to 0xE044)
    private const val ROMEN = 0xE020; private const val ORE = 0xE014
    private const val SILME = 0xE024; private const val SILME_N = 0xE025; private const val ESSE = 0xE026; private const val ESSE_N = 0xE027
    private const val SHORT = 0xE02E; private const val LONG = 0xE02C
    private const val DOT_BELOW = 0xE045; private const val DOUBLER = 0xE051; private const val NASAL = 0xE050; private const val DOUBLE_ACUTE = 0xE048
    private val STOPS = setOf(0xE000, 0xE001, 0xE002, 0xE003, 0xE004, 0xE005, 0xE006, 0xE007)
    private const val VOWELS = "aeiou"

    private class Unit(val vowel: Boolean, var code: Int, val long: Boolean = false, val marks: MutableList<Int> = ArrayList())

    private fun units(w: String): MutableList<Unit> {
        val out = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            val ch = w[i]
            val nxt = if (i + 1 < w.length) w[i + 1] else ' '
            if (ch == 'y') {
                if (nxt in VOWELS) out.add(Unit(false, C.getValue("y"))) else out.add(Unit(true, V.getValue('y')))
                i++; continue
            }
            if (ch in VOWELS) {
                if (nxt == ch && (ch == 'e' || ch == 'o')) { out.add(Unit(true, V.getValue(ch), long = true)); i += 2; continue }
                out.add(Unit(true, V.getValue(ch))); i++; continue
            }
            val two = if (i + 2 <= w.length) w.substring(i, i + 2) else ""
            if (two == "gh") { i += 2; continue }
            if (two.length == 2 && two[0] != two[1] && C.containsKey(two)) { out.add(Unit(false, C.getValue(two))); i += 2; continue }
            if (ch == 'c' && (nxt == 'e' || nxt == 'i' || nxt == 'y')) { out.add(Unit(false, SILME)); i++; continue }
            if (ch == 'x') { out.add(Unit(false, C.getValue("k"))); out.add(Unit(false, SILME)); i++; continue }
            val code = C[ch.toString()]
            if (code != null) {
                if (nxt == ch) { out.add(Unit(false, code, marks = mutableListOf(DOUBLER))); i += 2; continue }
                out.add(Unit(false, code)); i++; continue
            }
            i++
        }
        return out
    }

    fun word(raw: String): String {
        val w = raw.lowercase()
        if (w.all { it.isDigit() }) return w.map { (0xE070 + (it - '0')).toChar() }.joinToString("")
        val u = units(w)
        if (u.size >= 3 && u.last().vowel && u.last().code == V['e'] && !u.last().long && !u[u.size - 2].vowel && w.endsWith("e")) {
            u.removeAt(u.size - 1); u.last().marks.add(DOT_BELOW)
        }
        var k = 0
        while (k < u.size - 1) {
            val a = u[k]; val b = u[k + 1]
            if (!a.vowel && (a.code == C["n"] || a.code == C["m"]) && a.marks.isEmpty() && !b.vowel && b.code in STOPS) { b.marks.add(NASAL); u.removeAt(k); continue }
            k++
        }
        for (j in u.indices) if (!u[j].vowel && u[j].code == ORE && j + 1 < u.size && u[j + 1].vowel) u[j].code = ROMEN
        val sb = StringBuilder()
        k = 0
        while (k < u.size) {
            val x = u[k]
            if (x.vowel) {
                val tehta = if (x.long && x.code == V['e']) DOUBLE_ACUTE else x.code
                val n = u.getOrNull(k + 1)
                if (n != null && !n.vowel && !x.long) {
                    val code = when (n.code) { SILME -> SILME_N; ESSE -> ESSE_N; else -> n.code }
                    sb.append(code.toChar()).append(tehta.toChar()); n.marks.forEach { sb.append(it.toChar()) }
                    k += 2; continue
                }
                sb.append((if (x.long) LONG else SHORT).toChar()).append(tehta.toChar())
                k++; continue
            }
            sb.append(x.code.toChar()); x.marks.forEach { sb.append(it.toChar()) }
            k++
        }
        return sb.toString()
    }

    private val token = Regex("[A-Za-z]+|\\d+|[^A-Za-z\\d]+")
    /** A phrase in Tengwar (words and numbers; spaces and punctuation as they are, ':' as a double stop). */
    fun text(s: String): String = token.findAll(s).joinToString("") { m ->
        val t = m.value
        if (t[0].isLetterOrDigit()) word(t) else t.replace(":", "").replace(",", "")
    }
}
