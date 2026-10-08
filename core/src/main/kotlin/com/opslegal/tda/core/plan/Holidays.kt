package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.PlannerSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** Public holidays, so work is never planned on a day the office (and the client's) is closed. */
object Holidays {

    /** Québec's public holidays, and the Christmas break (Dec 24 to Jan 2) when most offices close. */
    fun isOff(date: LocalDate, region: String): Boolean {
        if (region != "QC") return false
        val y = date.year
        if ((date.month == Month.DECEMBER && date.dayOfMonth >= 24) || (date.month == Month.JANUARY && date.dayOfMonth <= 2)) return true
        val easter = easter(y)
        val days = setOf(
            easter.minusDays(2), // Good Friday
            easter.plusDays(1), // Easter Monday
            LocalDate.of(y, 5, 25).with(TemporalAdjusters.previous(DayOfWeek.MONDAY)), // National Patriots' Day
            LocalDate.of(y, 6, 24), // Fête nationale
            LocalDate.of(y, 7, 1), // Canada Day
            LocalDate.of(y, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)), // Labour Day
            LocalDate.of(y, 10, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.MONDAY)), // Thanksgiving
        )
        return date in days
    }

    /** A day work may be planned on: a work day that is not a holiday. */
    fun isWorkDay(date: LocalDate, settings: PlannerSettings): Boolean =
        date.dayOfWeek.value in settings.workDays && !isOff(date, settings.holidays)

    /** Easter Sunday (anonymous Gregorian algorithm). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }
}
