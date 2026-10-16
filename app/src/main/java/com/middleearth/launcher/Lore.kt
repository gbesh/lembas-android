package com.middleearth.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The words on the home screen: Shire calendar, Elvish time of day, hobbit mealtimes. */
object Lore {

    private val MONTHS = arrayOf(
        "Afteryule", "Solmath", "Rethe", "Astron", "Thrimidge", "Forelithe",
        "Afterlithe", "Wedmath", "Halimath", "Winterfilth", "Blotmath", "Foreyule",
    )

    fun shireWeekday(d: DayOfWeek): String = when (d) {
        DayOfWeek.SATURDAY -> "Sterday"
        DayOfWeek.SUNDAY -> "Sunday"
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Trewsday"
        DayOfWeek.WEDNESDAY -> "Hevensday"
        DayOfWeek.THURSDAY -> "Mersday"
        DayOfWeek.FRIDAY -> "Highday"
    }

    /** A Shire date: day number (0 for special days) and the month or special-day name. */
    class ShireDate(val day: Int, val name: String) {
        val isSpecial get() = day == 0
    }

    /**
     * Shire Reckoning. Every month has 30 days; the year starts at Yule (around 21 December)
     * with two Yule days, and three Lithe days (four in a leap year) sit at midsummer.
     */
    fun shireDate(date: LocalDate): ShireDate {
        val start = if (date.monthValue == 12 && date.dayOfMonth >= 21)
            LocalDate.of(date.year, 12, 21) else LocalDate.of(date.year - 1, 12, 21)
        var d = ChronoUnit.DAYS.between(start, date).toInt()
        val leap = Year.isLeap(start.year + 1L)
        if (d == 0) return ShireDate(0, "1 Yule")
        if (d == 1) return ShireDate(0, "2 Yule")
        d -= 2
        if (d < 180) return ShireDate(d % 30 + 1, MONTHS[d / 30])
        d -= 180
        val lithe = if (leap) listOf("1 Lithe", "Mid-year's Day", "Overlithe", "2 Lithe")
        else listOf("1 Lithe", "Mid-year's Day", "2 Lithe")
        if (d < lithe.size) return ShireDate(0, lithe[d])
        d -= lithe.size
        if (d < 180) return ShireDate(d % 30 + 1, MONTHS[6 + d / 30])
        return ShireDate(0, "1 Yule")
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    /** Quenya word for the time of day, with an English gloss. */
    fun period(hour: Int): Pair<String, String> = when (hour) {
        in 4..6 -> "Tindómë" to "the star-dawn"
        in 7..17 -> "Aurë" to "the day"
        in 18..20 -> "Undómë" to "eventide"
        else -> "Lómë" to "the night"
    }

    fun meal(hour: Int): String = when (hour) {
        5, 6 -> "Kettle on"
        7, 8 -> "Breakfast"
        9, 10 -> "Second breakfast"
        11 -> "Elevenses"
        12, 13 -> "Luncheon"
        14 -> "A stroll in the garden"
        15, 16 -> "Afternoon tea"
        17 -> "A pint at the Green Dragon"
        18, 19 -> "Dinner"
        20, 21 -> "Supper"
        else -> "The Shire sleeps"
    }

    private fun isMealHour(hour: Int) = hour in 7..13 || hour in 15..16 || hour in 18..21

    /** Real-world days Tolkien readers mark. */
    fun occasion(date: LocalDate): String? = when (date.monthValue to date.dayOfMonth) {
        1 to 3 -> "The Professor's birthday"
        3 to 25 -> "Tolkien Reading Day"
        9 to 22 -> "Hobbit Day"
        else -> null
    }

    class HomeText(val label: String, val shire: String, val date: String, val meal: String)

    fun homeText(look: Look, now: LocalDateTime, showMeals: Boolean): HomeText {
        val date = now.toLocalDate()
        val sd = shireDate(date)
        val wd = shireWeekday(date.dayOfWeek)
        val (quenya, gloss) = period(now.hour)
        val occ = occasion(date)
        val loc = Locale.getDefault()
        val mealText = if (showMeals) meal(now.hour) else ""
        return when (look.id) {
            "shire" -> HomeText(
                label = "${wd.uppercase(loc)} · ${quenya.uppercase(loc)}",
                shire = if (sd.isSpecial) sd.name else "${sd.day} ${sd.name}",
                date = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc)) +
                    (occ?.let { " · $it" } ?: ""),
                meal = mealText,
            )
            "redbook" -> HomeText(
                label = wd,
                shire = if (sd.isSpecial) sd.name else "${ordinal(sd.day)} of ${sd.name}",
                date = date.format(DateTimeFormatter.ofPattern("EEEE", loc)) +
                    ", the ${ordinal(date.dayOfMonth)} of " +
                    date.format(DateTimeFormatter.ofPattern("MMMM", loc)) +
                    (occ?.let { " · $it" } ?: ""),
                meal = if (showMeals && isMealHour(now.hour))
                    "Time for " + mealText.replaceFirstChar { it.lowercase(loc) } else mealText,
            )
            "elven" -> HomeText(
                label = quenya.uppercase(loc),
                shire = "$wd · ${if (sd.isSpecial) sd.name else "${sd.day} ${sd.name}"}",
                date = (date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc)) +
                    (occ?.let { " · $it" } ?: "")).uppercase(loc),
                meal = mealText,
            )
            else -> HomeText(
                label = "${quenya.uppercase(loc)} · ${gloss.uppercase(loc)}",
                shire = "$wd, ${if (sd.isSpecial) sd.name else "${sd.day} ${sd.name}"}",
                date = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc)) +
                    (occ?.let { " · $it" } ?: ""),
                meal = mealText,
            )
        }
    }
}
