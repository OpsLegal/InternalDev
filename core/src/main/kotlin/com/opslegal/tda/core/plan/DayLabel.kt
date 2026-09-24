package com.opslegal.tda.core.plan

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The first column of the table: one or two letters for the weekday followed by the
 * day of the month. "F12" is Friday the 12th, "Th3" is Thursday the 3rd.
 */
object DayLabel {
    private val english = mapOf(
        DayOfWeek.MONDAY to "M",
        DayOfWeek.TUESDAY to "Tu",
        DayOfWeek.WEDNESDAY to "W",
        DayOfWeek.THURSDAY to "Th",
        DayOfWeek.FRIDAY to "F",
        DayOfWeek.SATURDAY to "Sa",
        DayOfWeek.SUNDAY to "Su",
    )

    private val french = mapOf(
        DayOfWeek.MONDAY to "L",
        DayOfWeek.TUESDAY to "Ma",
        DayOfWeek.WEDNESDAY to "Me",
        DayOfWeek.THURSDAY to "J",
        DayOfWeek.FRIDAY to "V",
        DayOfWeek.SATURDAY to "S",
        DayOfWeek.SUNDAY to "D",
    )

    fun of(date: LocalDate, language: String = "en"): String {
        val letters = if (language.startsWith("fr")) french else english
        return letters.getValue(date.dayOfWeek) + date.dayOfMonth
    }
}
