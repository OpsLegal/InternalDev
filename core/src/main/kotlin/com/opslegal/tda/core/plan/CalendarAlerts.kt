package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateStatus
import java.time.LocalDate

/**
 * A calendar event left undecided is a risk: not in the day (nor left out), or an invitation not answered. From
 * [com.opslegal.tda.core.model.UpdateChecks.calendarDays] days before (2–4), each one is an urgent notification
 * until it is decided; then (or once it is past) the notification goes by itself.
 */
object CalendarAlerts {
    const val SOURCE = "calendar"

    fun id(e: CalendarEvent) = "cal:" + CalendarCells.key(e)

    fun undecided(board: Board, e: CalendarEvent) = board.calendarChoices[CalendarCells.key(e)] == null || e.invited

    fun sync(board: Board, events: List<CalendarEvent>, today: LocalDate): Board {
        val last = today.plusDays(board.checks.calendarDays.coerceIn(1, 7).toLong())
        val due = events.filter { e -> val d = CalendarCells.date(e); d != null && !d.isBefore(today) && !d.isAfter(last) && undecided(board, e) }.associateBy { id(it) }
        // Decided or past: the card goes (one the user put away stays put away).
        val kept = board.updates.filter { it.source != SOURCE || it.id in due || it.status != UpdateStatus.NEW }
        val fresh = due.values.map { e -> card(e, kept.firstOrNull { it.id == id(e) }) }
        val updates = kept.map { u -> fresh.firstOrNull { it.id == u.id && u.status == UpdateStatus.NEW } ?: u } + fresh.filter { f -> kept.none { it.id == f.id } }
        return if (updates == board.updates) board else board.copy(updates = updates)
    }

    private fun card(e: CalendarEvent, old: Update?): Update {
        val day = CalendarCells.date(e)!!
        val whenText = "${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${day.dayOfMonth}" +
            CalendarCells.startTime(e).let { if (it.isBlank()) " (all day)" else ", $it" }
        return (old ?: Update(id(e), SOURCE, e.calendar.ifBlank { "Calendar" }, e.describe(), "", createdAt = LocalDate.now().toString())).copy(
            title = "📅 ${e.title.ifBlank { "Calendar event" }} · $whenText",
            summary = if (e.invited) "Invitation not answered · $whenText. Answer it in your calendar, then add it to your day or leave it out."
                else "Not decided · $whenText. Add it to your day or leave it out.",
            text = e.describe(), urgent = true, due = day.toString(),
        )
    }
}
