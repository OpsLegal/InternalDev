package com.opslegal.tda.core.plan

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Several projects at once, as a list said or typed in a meeting: one per line, an end date in plain words when there
 * is one ("Contacter LIA by Nov 30", "Site web – fin d'année", "Bolton: 2026-12-15"). Read on the phone, no AI.
 */
object QuickProjects {
    data class Item(val name: String, val deadline: LocalDate?)

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private val MONTHS = listOf(
        listOf("jan", "janv", "january", "janvier"), listOf("feb", "fev", "february", "fevrier"), listOf("mar", "march", "mars"),
        listOf("apr", "avr", "april", "avril"), listOf("may", "mai"), listOf("jun", "june", "juin"), listOf("jul", "july", "juil", "juillet"),
        listOf("aug", "august", "aout"), listOf("sep", "sept", "september", "septembre"), listOf("oct", "october", "octobre"),
        listOf("nov", "november", "novembre"), listOf("dec", "december", "decembre"),
    )
    private fun month(w: String): Int? = MONTHS.indexOfFirst { names -> names.any { w == it || (w.length >= 3 && it.startsWith(w) && w.length >= 4) } }.takeIf { it >= 0 }?.plus(1)

    /** The next date (today or later) with this month and day. */
    private fun next(m: Int, d: Int, today: LocalDate): LocalDate? = runCatching {
        val y = LocalDate.of(today.year, m, d); if (y.isBefore(today)) y.plusYears(1) else y
    }.getOrNull()

    /** An end date in the words, and the words without it. */
    fun dateIn(text: String, today: LocalDate): Pair<LocalDate?, String> {
        val t = fold(text)
        val joiner = """(?:\s*(?:[-–—:,]|\bby\b|\bbefore\b|\bdue\b|\bdeadline\b|\bavant\b|\bpour\b|\bd'ici\b|\bd ici\b|\bau\b|\ble\b|\bfor\b|\bend\b)\s*)*"""
        fun cut(r: MatchResult) = text.substring(0, r.range.first).trim().trimEnd('-', '–', '—', ':', ',', ' ')
        Regex("""$joiner(\d{4})-(\d{2})-(\d{2})\s*$""").find(t)?.let { r ->
            return runCatching { LocalDate.of(r.groupValues[1].toInt(), r.groupValues[2].toInt(), r.groupValues[3].toInt()) }.getOrNull() to cut(r)
        }
        Regex("""$joiner(?:end of (?:the )?year|year end|fin (?:de l'?|d'?)?annee|fin de l annee)\s*$""").find(t)?.let { r -> return LocalDate.of(today.year, 12, 31) to cut(r) }
        Regex("""$joiner(?:end of (?:the )?month|fin du mois)\s*$""").find(t)?.let { r -> return today.with(TemporalAdjusters.lastDayOfMonth()) to cut(r) }
        Regex("""$joiner(\d{1,2})\s*/\s*(\d{1,2})\s*$""").find(t)?.let { r -> return next(r.groupValues[2].toInt(), r.groupValues[1].toInt(), today) to cut(r) }
        Regex("""$joiner(\d{1,2})(?:st|nd|rd|th|er)?\s+([a-z]{3,9})\.?\s*$""").find(t)?.let { r ->
            month(r.groupValues[2])?.let { m -> return next(m, r.groupValues[1].toInt(), today) to cut(r) }
        }
        Regex("""$joiner([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?\s*$""").find(t)?.let { r ->
            month(r.groupValues[1])?.let { m -> return next(m, r.groupValues[2].toInt(), today) to cut(r) }
        }
        return null to text.trim()
    }

    fun parse(text: String, today: LocalDate): List<Item> = text.lines()
        .map { it.trim().replace(Regex("""^(?:[-•*·]|\d{1,2}[.)])\s*"""), "") }
        .filter { it.isNotBlank() }
        .map { line -> val (d, name) = dateIn(line, today); Item(name.ifBlank { line }.take(60), d) }
        .distinctBy { fold(it.name) }
        .take(15)
}
