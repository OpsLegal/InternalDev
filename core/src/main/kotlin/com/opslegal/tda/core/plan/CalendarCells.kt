package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.CalendarChoice
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.TaskKind
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil

/**
 * Calendar events in the day: the user chooses which ones take cells. An added event becomes black meeting
 * cells fixed to its day, placed before anything else, and less important work moves to make room.
 */
object CalendarCells {

    fun key(e: CalendarEvent) = "${e.start}|${e.title}"

    fun date(e: CalendarEvent): LocalDate? = runCatching { LocalDate.parse(e.start.take(10)) }.getOrNull()

    /** About 1.5 h a cell: up to 2 h is one cell, a half-day two, an all-day event the whole day. */
    fun cells(e: CalendarEvent): Int {
        if (e.allDay) return SLOTS_PER_DAY
        val minutes = runCatching { Duration.between(LocalDateTime.parse(e.start), LocalDateTime.parse(e.end)).toMinutes() }.getOrDefault(60L)
        return ceil((minutes - 30) / 90.0).toInt().coerceIn(1, SLOTS_PER_DAY)
    }

    fun startTime(e: CalendarEvent): String = if (e.allDay) "" else e.start.substringAfter('T', "").take(5)

    fun whenText(e: CalendarEvent): String = if (e.allDay) "all day" else "${startTime(e)}–${e.end.substringAfter('T', "").take(5)}"

    /** Events of [day] not put in the day yet: undecided first, then the ones left out (still shown, to change one's mind). */
    fun shown(board: Board, events: List<CalendarEvent>, day: String): List<CalendarEvent> =
        events.filter { it.start.take(10) == day && board.calendarChoices[key(it)]?.added != true }

    /**
     * Every event of [day] is reviewed (in the day or left out). The day before it in the table is their review
     * day: that day is complete only when its cells are done and these events are reviewed.
     */
    fun reviewed(board: Board, events: List<CalendarEvent>, day: String): Boolean =
        events.filter { it.start.take(10) == day }.all { board.calendarChoices[key(it)] != null }

    fun isLeftOut(board: Board, e: CalendarEvent) = board.calendarChoices[key(e)]?.added == false

    /** Puts [e] in its day. Returns the board with its cells placed and the planner run. */
    fun add(board: Board, e: CalendarEvent, today: LocalDate): Board {
        val day = date(e) ?: return board
        var b = board
        val ids = mutableListOf<String>()
        repeat(cells(e)) { i ->
            val title = if (i == 0) listOf(startTime(e), e.title.ifBlank { "Busy" }).filter { it.isNotBlank() }.joinToString(" ") else "${e.title} (cont.)"
            val spec = BoardOps.NewTask(title, "${whenText(e)}, from your calendar.", kind = TaskKind.MEETING, fixedDate = day.toString())
            var (next, task, placed) = BoardOps.addTaskOn(b, spec, day, today)
            if (!placed) {
                // A full day: the least fixed work cell of that day moves to a later day.
                val victim = next.tasks.flatMap { t -> t.steps.map { t to it } }
                    .filter { (t, s) -> s.date == day.toString() && s.slot != null && !s.done && s.outcome == null && t.kindOf(s) == TaskKind.TASK }
                    .sortedWith(compareBy({ !it.second.pinned }, { it.second.slot }))
                    .lastOrNull()
                if (victim != null) {
                    next = BoardOps.unschedule(next, victim.second.id, day.plusDays(1).toString())
                    next = BoardOps.moveStep(next, task.steps.first().id, day) ?: next
                }
            }
            b = next
            ids += task.id
        }
        b = Planner.plan(b, today).board
        return b.copy(calendarChoices = recent(b, today) + (key(e) to CalendarChoice(true, ids)))
    }

    /** Takes an added event out of the day: its cells go, the planner refills the day; it counts as left out. */
    fun takeOut(board: Board, e: CalendarEvent, today: LocalDate): Board {
        val ids = board.calendarChoices[key(e)]?.taskIds.orEmpty().toSet()
        val without = board.copy(tasks = board.tasks.filter { it.id !in ids })
        return leaveOut(Planner.plan(without, today).board, e, today)
    }

    /** All events of [day], sorted by time, with how many are still to review. */
    fun ofDay(board: Board, events: List<CalendarEvent>, day: String): Pair<List<CalendarEvent>, Int> {
        val list = events.filter { it.start.take(10) == day }.sortedBy { it.start }
        return list to list.count { board.calendarChoices[key(it)] == null }
    }

    fun isAdded(board: Board, e: CalendarEvent) = board.calendarChoices[key(e)]?.added == true

    fun leaveOut(board: Board, e: CalendarEvent, today: LocalDate): Board =
        board.copy(calendarChoices = recent(board, today) + (key(e) to CalendarChoice(false)))

    /** Choices for past events are dropped after a week. */
    private fun recent(board: Board, today: LocalDate): Map<String, CalendarChoice> {
        val limit = today.minusDays(7).toString()
        return board.calendarChoices.filterKeys { it.take(10) >= limit }
    }
}
