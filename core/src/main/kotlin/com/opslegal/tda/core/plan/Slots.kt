package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.TaskKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** A time someone can be offered for a meeting. */
data class Slot(val date: LocalDate, val start: LocalTime, val end: LocalTime) {
    fun label(locale: Locale = Locale.ENGLISH): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.month.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}, $start–$end"
}

/**
 * Finds meeting times that fit the user's day, not just their calendar: the day needs a free
 * cell (a meeting takes one of the five), is not the day of or before a deadline, isn't already
 * heavy, and hasn't reached the meeting limit. One slot per day, alternating mornings and
 * afternoons, so the person gets real choice.
 */
object Slots {

    private const val STEP_MINUTES = 30L

    fun find(
        board: Board,
        events: List<CalendarEvent>,
        from: LocalDate,
        days: Int,
        count: Int,
        now: LocalDateTime,
        durationMinutes: Int = board.meetings.durationMinutes,
    ): List<Slot> {
        val strict = search(board, events, from, days, count, now, durationMinutes, avoidDeadlines = true)
        if (strict.size >= count) return strict
        // Busy stretch: allow days next to a deadline rather than offering nothing.
        val relaxed = search(board, events, from, days, count, now, durationMinutes, avoidDeadlines = false)
        return (strict + relaxed.filter { r -> strict.none { it.date == r.date } }).sortedBy { it.date }.take(count)
    }

    private fun search(
        board: Board,
        events: List<CalendarEvent>,
        from: LocalDate,
        days: Int,
        count: Int,
        now: LocalDateTime,
        duration: Int,
        avoidDeadlines: Boolean,
    ): List<Slot> {
        val settings = board.meetings
        val windows = settings.windows.mapNotNull(::parseWindow)
        if (windows.isEmpty()) return emptyList()
        val found = mutableListOf<Slot>()
        val rows = Planner.rows(board, from, days + 1).associateBy { it.date }
        for (i in 0 until days) {
            if (found.size >= count) break
            val day = from.plusDays(i.toLong())
            if (day.isBefore(now.toLocalDate()) || day.dayOfWeek.value !in settings.days) continue
            val cells = rows[day.toString()]?.cells.orEmpty().filterNotNull().filter { it.outcome == null }
            if (cells.size >= SLOTS_PER_DAY) continue
            if (cells.count { it.effort == Effort.HEAVY && !it.done } >= 2) continue
            if (avoidDeadlines) {
                val tomorrow = rows[day.plusDays(1).toString()]?.cells.orEmpty().filterNotNull()
                if ((cells + tomorrow).any { it.kind == TaskKind.DEADLINE && !it.done }) continue
            }
            val timed = events.filter { !it.allDay && it.start.startsWith(day.toString()) }
            val meetings = maxOf(timed.size, cells.count { it.kind == TaskKind.MEETING })
            if (meetings >= settings.maxPerDay) continue

            val busy = timed.mapNotNull { e ->
                runCatching { LocalDateTime.parse(e.start).toLocalTime() to LocalDateTime.parse(e.end).toLocalTime() }.getOrNull()
            }
            val earliest = if (day == now.toLocalDate()) now.toLocalTime().plusHours(2) else LocalTime.MIN
            // Alternate mornings and afternoons across the days offered.
            val preferred = found.size % windows.size
            val order = listOf(windows[preferred]) + windows.filterIndexed { index, _ -> index != preferred }
            val slot = order.firstNotNullOfOrNull { (open, close) ->
                var start = open
                var result: Slot? = null
                while (result == null && !start.plusMinutes(duration.toLong()).isAfter(close) && start >= open) {
                    val end = start.plusMinutes(duration.toLong())
                    val clash = busy.any { (b, e) ->
                        start.isBefore(e.plusMinutes(settings.bufferMinutes.toLong())) &&
                            end.isAfter(b.minusMinutes(settings.bufferMinutes.toLong()))
                    }
                    if (!clash && !start.isBefore(earliest)) result = Slot(day, start, end)
                    val next = start.plusMinutes(STEP_MINUTES)
                    if (next <= start) break
                    start = next
                }
                result
            }
            if (slot != null) found += slot
        }
        return found
    }

    /** "09:00-12:00" to a pair of times. */
    fun parseWindow(text: String): Pair<LocalTime, LocalTime>? = runCatching {
        val (a, b) = text.split('-', '–').map { LocalTime.parse(it.trim().padStart(5, '0')) }
        if (b.isAfter(a)) a to b else null
    }.getOrNull()
}
