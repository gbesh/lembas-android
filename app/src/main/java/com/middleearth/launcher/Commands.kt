package com.middleearth.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Little commands typed into search, answered without opening an app first:
 *   "timer 10m", "5 min timer", "timer 1h 30"      → starts a timer in your clock app
 *   "alarm 7:30", "alarm 6am", "wake me 6.45"      → sets an alarm
 *   "10 km in miles", "180 cm to ft", "70f in c"    → unit conversions (length, weight, volume, temperature, speed)
 *   "100 usd to eur", "50 aud in gbp"               → currency, with the European Central Bank's daily rates
 *                                                     (fetched from frankfurter.app at most once a day)
 */
object Commands {

    /** The answers for [q], or nothing. [ratesArrived] is called once when currency rates finish loading. */
    fun evaluate(activity: Activity, q: String, ratesArrived: () -> Unit): List<Hit> {
        val s = q.trim().lowercase(Locale.ROOT)
        timer(activity, s)?.let { return listOf(it) }
        alarm(activity, s)?.let { return listOf(it) }
        units(s)?.let { return listOf(it) }
        currency(activity, s, ratesArrived)?.let { return listOf(it) }
        return emptyList()
    }

    // ---- timer

    private val timerRe = Regex("^(?:timer\\s+(.+)|(.+?)\\s*timer)$")
    private val partRe = Regex("(\\d+(?:[.,]\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)?")

    private fun timer(a: Activity, s: String): Hit? {
        val body = timerRe.find(s)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return null
        var secs = 0.0
        var unitless = 0
        for ((i, m) in partRe.findAll(body).withIndex()) {
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val u = m.groupValues[2]
            secs += when {
                u.startsWith("h") -> v * 3600
                u.startsWith("s") -> v
                u.startsWith("m") -> v * 60
                else -> { unitless++; if (i == 0) v * 60 else v * 60 }   // "timer 10" = ten minutes; "1h 30" = and thirty minutes
            }
        }
        val total = secs.toInt()
        if (total <= 0 || total > 24 * 3600) return null
        val label = buildString {
            if (total >= 3600) append("${total / 3600} h ")
            if (total % 3600 >= 60) append("${total % 3600 / 60} min ")
            if (total % 60 > 0) append("${total % 60} s")
        }.trim()
        return Hit.Action("Start a $label timer", "Clock", R.drawable.g_clock) {
            start(a, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, total)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Lembas"), "Timer set for $label")
        }
    }

    // ---- alarm

    private val alarmRe = Regex("^(?:alarm|wake me(?: up)?(?: at)?|alarm at|set alarm(?: for)?)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a|p)?$")

    private fun alarm(a: Activity, s: String): Hit? {
        val m = alarmRe.find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (ap.startsWith("p") && h < 12) h += 12
        if (ap.startsWith("a") && h == 12) h = 0
        if (h > 23 || min > 59) return null
        val shown = String.format(Locale.ROOT, "%d:%02d", h, min)
        return Hit.Action("Set an alarm for $shown", "Clock", R.drawable.g_clock) {
            start(a, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Lembas"), "Alarm set for $shown")
        }
    }

    private fun start(a: Activity, i: Intent, done: String) {
        try { a.startActivity(i); Toast.makeText(a, done, Toast.LENGTH_SHORT).show() }
        catch (_: Exception) { Toast.makeText(a, "No clock app to do that", Toast.LENGTH_SHORT).show() }
    }

    // ---- units

    private val convRe = Regex("^(-?\\d+(?:[.,]\\d+)?)\\s*([a-z°/\" ']+?)\\s+(?:in|to|as|into)\\s+([a-z°/\" ']+)$")

    /** Each unit's size in its kind's base (metre, kilogram, litre, m/s). */
    private val UNITS: Map<String, Pair<String, Double>> = buildMap {
        fun add(kind: String, f: Double, vararg names: String) { for (n in names) put(n, kind to f) }
        add("len", 1.0, "m", "metre", "metres", "meter", "meters")
        add("len", 1000.0, "km", "kilometre", "kilometres", "kilometer", "kilometers")
        add("len", 0.01, "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        add("len", 0.001, "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        add("len", 1609.344, "mi", "mile", "miles")
        add("len", 0.9144, "yd", "yard", "yards")
        add("len", 0.3048, "ft", "foot", "feet", "'")
        add("len", 0.0254, "in", "inch", "inches", "\"")
        add("len", 1852.0, "nmi", "nautical mile", "nautical miles")
        add("len", 4828.032, "league", "leagues")
        add("mass", 1.0, "kg", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.001, "g", "gram", "grams")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds")
        add("mass", 0.028349523, "oz", "ounce", "ounces")
        add("mass", 6.35029318, "st", "stone", "stones")
        add("mass", 1000.0, "t", "tonne", "tonnes")
        add("vol", 1.0, "l", "litre", "litres", "liter", "liters")
        add("vol", 0.001, "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        add("vol", 3.785411784, "gal", "gallon", "gallons", "us gallon", "us gallons")
        add("vol", 4.54609, "uk gallon", "uk gallons", "imperial gallon", "imperial gallons")
        add("vol", 0.2365882365, "cup", "cups")
        add("vol", 0.473176473, "pint", "pints", "us pint", "us pints")
        add("vol", 0.56826125, "uk pint", "uk pints")
        add("vol", 0.0295735, "fl oz", "floz")
        add("vol", 0.0147868, "tbsp", "tablespoon", "tablespoons")
        add("vol", 0.00492892, "tsp", "teaspoon", "teaspoons")
        add("speed", 1.0, "m/s")
        add("speed", 1 / 3.6, "km/h", "kmh", "kph")
        add("speed", 0.44704, "mph")
        add("speed", 0.514444, "kn", "knot", "knots")
        for (c in listOf("c", "°c", "celsius")) put(c, "temp" to 0.0)
        for (f in listOf("f", "°f", "fahrenheit")) put(f, "temp" to 1.0)
        for (k in listOf("k", "kelvin")) put(k, "temp" to 2.0)
    }

    private fun units(s: String): Hit? {
        val m = convRe.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = UNITS[m.groupValues[2].trim()] ?: return null
        val toName = m.groupValues[3].trim()
        val to = UNITS[toName] ?: return null
        if (from.first != to.first) return null
        val r = if (from.first == "temp") {
            val c = when (from.second) { 0.0 -> v; 1.0 -> (v - 32) * 5 / 9; else -> v - 273.15 }
            when (to.second) { 0.0 -> c; 1.0 -> c * 9 / 5 + 32; else -> c + 273.15 }
        } else v * from.second / to.second
        return Hit.Calc(s, "${fmt(r)} ${pretty(toName)}")
    }

    private fun pretty(u: String) = when (u) { "c", "°c", "celsius" -> "°C"; "f", "°f", "fahrenheit" -> "°F"; "k", "kelvin" -> "K"; else -> u }

    private fun fmt(v: Double): String {
        val a = kotlin.math.abs(v)
        val digits = when { a >= 1000 -> 0; a >= 100 -> 1; a >= 1 -> 2; else -> 4 }
        return String.format(Locale.getDefault(), "%,.${digits}f", v).let { if (digits > 0) it.trimEnd('0').trimEnd('.', ',') else it }
    }

    // ---- currency

    private val curRe = Regex("^(\\d+(?:[.,]\\d+)?)\\s*([a-z]{3}|[$€£¥])\\s+(?:in|to|as|into)\\s+([a-z]{3}|[$€£¥])$")
    private val symbols = mapOf("$" to "usd", "€" to "eur", "£" to "gbp", "¥" to "jpy")
    @Volatile private var fetching = false

    private fun currency(ctx: Context, s: String, arrived: () -> Unit): Hit? {
        val m = curRe.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = (symbols[m.groupValues[2]] ?: m.groupValues[2]).uppercase(Locale.ROOT)
        val to = (symbols[m.groupValues[3]] ?: m.groupValues[3]).uppercase(Locale.ROOT)
        val p = ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE)
        val rates = try { JSONObject(p.getString("fx_rates", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val age = System.currentTimeMillis() - p.getLong("fx_at", 0)
        if (age > 20 * 3600_000L) fetch(ctx.applicationContext, arrived)
        fun perEuro(c: String): Double? = if (c == "EUR") 1.0 else rates.optDouble(c).takeIf { !it.isNaN() }
        val a = perEuro(from); val b = perEuro(to)
        if (a == null || b == null) {
            if (rates.length() == 0) return Hit.Action("Converting $from to $to…", "Getting today's rates", R.drawable.g_bank) { }
            return null
        }
        val r = v / a * b
        return Hit.Calc(s, String.format(Locale.getDefault(), "%,.2f %s", r, to))
    }

    private fun fetch(ctx: Context, arrived: () -> Unit) {
        if (fetching) return
        fetching = true
        Thread {
            try {
                val c = URL("https://api.frankfurter.app/latest?from=EUR").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val rates = JSONObject(body).getJSONObject("rates")
                ctx.getSharedPreferences(Themes.PREFS, Context.MODE_PRIVATE).edit()
                    .putString("fx_rates", rates.toString()).putLong("fx_at", System.currentTimeMillis()).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).post(arrived)
            } catch (_: Exception) {
            } finally { fetching = false }
        }.apply { priority = Thread.MIN_PRIORITY; name = "lembas-rates" }.start()
    }
}

/** Forgiving matching: "spotfy", "whatsap" and "gogle maps" still find the app (one slip per four letters). */
object Fuzzy {
    fun close(query: String, name: String): Boolean {
        if (query.length < 4) return false
        val allowed = if (query.length >= 8) 2 else 1
        // against the whole name's start, and against each word
        if (distance(query, name.take(query.length + 1).trimEnd(), allowed) <= allowed ||
            distance(query, name.take(query.length), allowed) <= allowed) return true
        return name.split(' ').any { it.length >= 3 && distance(query, it.take(query.length + 1), allowed) <= allowed }
    }

    /** Damerau–Levenshtein (with swapped letters), giving up past [cap]. */
    fun distance(a: String, b: String, cap: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return cap + 1
        }
        return d[a.length][b.length]
    }
}
