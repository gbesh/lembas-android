package com.middleearth.launcher

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Middle-earth's calendar in the scene: Hobbit Day's fireworks (22 September), lanterns for the
 * Shire's Yule and Lithe, moon-letters on Durin's Day (the last new moon of autumn), and a toast
 * to the Professor at 9pm on 3 January. And the days until the next of them.
 */
object Feasts {
    class Feast(val name: String, val date: LocalDate)

    private fun south() = Sun.where().first < 0

    fun durinsDay(year: Int): LocalDate? = Heavens.durinsDay(year, south())

    /** Yule's two days and the Lithe days at midsummer: lanterns. */
    fun lanterns(d: LocalDate = LocalDate.now()): Boolean = Lore.shireDate(d).let { it.isSpecial && (it.name.contains("Yule") || it.name.contains("Lithe") || it.name.contains("year")) }

    /** Hobbit Day, from 6pm (Bilbo's party, and Gandalf's fireworks). */
    fun fireworks(now: LocalDateTime = LocalDateTime.now()): Boolean = now.monthValue == 9 && now.dayOfMonth == 22 && now.hour >= 18

    fun durin(d: LocalDate = LocalDate.now()): Boolean = durinsDay(d.year) == d

    /** At 9pm on 3 January, the Tolkien Society's toast. */
    fun toast(now: LocalDateTime = LocalDateTime.now()): Boolean = now.monthValue == 1 && now.dayOfMonth == 3 && now.hour == 21

    /** The next feast from today (today's counts), within a year. */
    fun next(today: LocalDate = LocalDate.now()): Feast {
        val list = ArrayList<Feast>()
        for (y in today.year..today.year + 1) {
            list.add(Feast("the Professor's birthday", LocalDate.of(y, 1, 3)))
            list.add(Feast("Hobbit Day", LocalDate.of(y, 9, 22)))
            list.add(Feast("Yule", LocalDate.of(y, 12, 21)))
            // Mid-year's Day: the second Lithe day
            var d = LocalDate.of(y, 6, 15)
            while (Lore.shireDate(d).name != "Mid-year's Day" && d.monthValue < 7) d = d.plusDays(1)
            list.add(Feast("Mid-year's Day", d))
            durinsDay(y)?.let { list.add(Feast("Durin's Day", it)) }
        }
        return list.filter { !it.date.isBefore(today) }.minByOrNull { it.date }!!
    }

    /** "Hobbit Day in 12 days", "Durin's Day tomorrow", "Yule today". */
    fun countdown(today: LocalDate = LocalDate.now()): String {
        val f = next(today)
        val n = ChronoUnit.DAYS.between(today, f.date).toInt()
        val name = f.name.replaceFirstChar { it.uppercase() }
        return when (n) { 0 -> "$name today"; 1 -> "$name tomorrow"; else -> "$name in $n days" }
    }
}
