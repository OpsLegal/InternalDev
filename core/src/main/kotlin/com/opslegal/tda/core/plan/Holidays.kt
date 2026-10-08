package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.PlannerSettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Public holidays of the user's region, so work is never planned on a day offices (and clients) are closed.
 * Regions: QC (Québec), ON (Ontario), CA (Canada, federal), US (United States), FR (France); "" = none.
 */
object Holidays {
    val regions = linkedMapOf("QC" to "Québec", "ON" to "Ontario", "CA" to "Canada (federal)", "US" to "United States", "FR" to "France", "" to "None")

    /** The region the phone suggests: French Canada → Québec, Canada → federal, United States, France; else none. */
    fun guess(language: String, country: String): String = when (country.uppercase()) {
        "CA" -> if (language.lowercase() == "fr") "QC" else "CA"
        "US" -> "US"
        "FR" -> "FR"
        else -> ""
    }

    private fun nth(y: Int, month: Int, n: Int, dow: DayOfWeek) = LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
    private fun last(y: Int, month: Int, dow: DayOfWeek) = LocalDate.of(y, month, 1).with(TemporalAdjusters.lastInMonth(dow))
    private fun victoria(y: Int) = LocalDate.of(y, 5, 25).with(TemporalAdjusters.previous(DayOfWeek.MONDAY))

    /** A fixed-date holiday on a weekend is observed on the next Monday (Canada) or the nearest weekday (US). */
    private fun observed(d: LocalDate, us: Boolean): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> if (us) d.minusDays(1) else d.plusDays(2)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    fun days(y: Int, region: String): Set<LocalDate> {
        val e = easter(y)
        fun fixed(m: Int, d: Int, us: Boolean = false) = LocalDate.of(y, m, d).let { setOf(it, observed(it, us)) }
        return when (region) {
            "QC" -> setOf(e.minusDays(2), e.plusDays(1), victoria(y), LocalDate.of(y, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)),
                nth(y, 10, 2, DayOfWeek.MONDAY)) + fixed(1, 1) + fixed(6, 24) + fixed(7, 1)
            "ON" -> setOf(nth(y, 2, 3, DayOfWeek.MONDAY), e.minusDays(2), victoria(y), nth(y, 8, 1, DayOfWeek.MONDAY),
                LocalDate.of(y, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)), nth(y, 10, 2, DayOfWeek.MONDAY)) + fixed(1, 1) + fixed(7, 1)
            "CA" -> setOf(e.minusDays(2), e.plusDays(1), victoria(y), LocalDate.of(y, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)),
                nth(y, 10, 2, DayOfWeek.MONDAY)) + fixed(1, 1) + fixed(7, 1) + fixed(9, 30) + fixed(11, 11)
            "US" -> setOf(nth(y, 1, 3, DayOfWeek.MONDAY), nth(y, 2, 3, DayOfWeek.MONDAY), last(y, 5, DayOfWeek.MONDAY),
                LocalDate.of(y, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)), nth(y, 10, 2, DayOfWeek.MONDAY), nth(y, 11, 4, DayOfWeek.THURSDAY)) +
                fixed(1, 1, true) + fixed(6, 19, true) + fixed(7, 4, true) + fixed(11, 11, true) + fixed(12, 25, true)
            "FR" -> setOf(e.plusDays(1), e.plusDays(39), e.plusDays(50), LocalDate.of(y, 1, 1), LocalDate.of(y, 5, 1), LocalDate.of(y, 5, 8),
                LocalDate.of(y, 7, 14), LocalDate.of(y, 8, 15), LocalDate.of(y, 11, 1), LocalDate.of(y, 11, 11), LocalDate.of(y, 12, 25))
            else -> emptySet()
        }
    }

    /** In Canada, most offices close between Christmas and January 2: no work planned then. */
    private fun christmasBreak(date: LocalDate, region: String) = region in setOf("QC", "ON", "CA") &&
        ((date.month == Month.DECEMBER && date.dayOfMonth >= 24) || (date.month == Month.JANUARY && date.dayOfMonth <= 2))

    fun isOff(date: LocalDate, region: String): Boolean =
        region.isNotBlank() && (christmasBreak(date, region) || date in days(date.year, region))

    /** A day work may be planned on: a work day that is not a holiday. */
    fun isWorkDay(date: LocalDate, settings: PlannerSettings): Boolean =
        date.dayOfWeek.value in settings.workDays && !isOff(date, settings.holidays)

    /** What the region's holidays are, in a few words, for Settings. */
    fun describe(region: String): String = when (region) {
        "QC" -> "Good Friday, Easter Monday, Patriots' Day, June 24, July 1, Labour Day, Thanksgiving, and the Christmas break (Dec 24 – Jan 2)."
        "ON" -> "Family Day, Good Friday, Victoria Day, Canada Day, Civic Holiday, Labour Day, Thanksgiving, and the Christmas break (Dec 24 – Jan 2)."
        "CA" -> "Good Friday, Easter Monday, Victoria Day, Canada Day, Labour Day, Truth and Reconciliation Day, Thanksgiving, Remembrance Day, and the Christmas break."
        "US" -> "New Year, MLK Day, Presidents' Day, Memorial Day, Juneteenth, July 4, Labor Day, Columbus Day, Veterans Day, Thanksgiving, Christmas."
        "FR" -> "Jour de l'an, lundi de Pâques, 1er et 8 mai, Ascension, Pentecôte, 14 juillet, 15 août, Toussaint, 11 novembre, Noël."
        else -> "No holidays: every work day can be planned."
    }

    /** Easter Sunday (anonymous Gregorian algorithm). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }
}
