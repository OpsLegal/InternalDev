package com.opslegal.tda.core

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.CalendarChoice
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.CalendarAlerts
import com.opslegal.tda.core.plan.CalendarCells
import com.opslegal.tda.core.plan.Fill
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Undecided calendar events become urgent notifications; notifications sort; the widget's buttons fill today. */
class FillAndAlertsTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val board = Board(settings = PlannerSettings(bigPerDay = 3))

    @Test
    fun undecidedCalendarEventsAreUrgentFromTheChosenDaysBefore() {
        val soon = CalendarEvent("Hearing", "2026-11-04T10:00", "2026-11-04T11:00")
        val far = CalendarEvent("Dentist", "2026-11-20T10:00", "2026-11-20T11:00")
        val invite = CalendarEvent("ACME call", "2026-11-03T14:00", "2026-11-03T15:00", invited = true)
        var b = CalendarAlerts.sync(board, listOf(soon, far, invite), monday)
        assertEquals(setOf("cal:" + CalendarCells.key(soon), "cal:" + CalendarCells.key(invite)), b.updates.map { it.id }.toSet(), "only within 3 days")
        assertTrue(b.updates.all { it.urgent })
        assertTrue(Updates.inbox(b, monday).size == 2, "they show in the notifications")
        // Decided: in the day. The invitation still waits for an answer.
        b = b.copy(calendarChoices = mapOf(CalendarCells.key(soon) to CalendarChoice(true), CalendarCells.key(invite) to CalendarChoice(true)))
        b = CalendarAlerts.sync(b, listOf(soon, far, invite), monday)
        assertEquals(listOf("cal:" + CalendarCells.key(invite)), b.updates.map { it.id })
    }

    @Test
    fun notificationsSortByDeadlineByDefault() {
        val a = Update("a", "outlook", "X", "newsletter", "fyi", due = "")
        val b2 = Update("b", "outlook", "Y", "court date", "hearing", due = "2026-11-10")
        val c = Update("c", "outlook", "Z", "call me", "asap", due = "2026-11-05", urgent = true)
        assertEquals(listOf("c", "b", "a"), Updates.sorted(board, listOf(a, b2, c), "deadline", monday).map { it.id })
        assertEquals("a", Updates.sorted(board, listOf(a, b2, c), "impact", monday).last().id)
        assertEquals("c", Updates.sorted(board, listOf(a, b2, c), "emergency", monday).first().id)
    }

    @Test
    fun fillPutsTheNextProjectStepTodayButNeverJumpsAhead() {
        val p = Project("Lease")
        var b = Projects.save(board, p, null, listOf("Draft", "Send").map { BoardOps.EditedStep(null, it) }, monday).board
        // Pretend the first step is planned later: Fill brings it today (the first open step), never the second.
        val steps = BoardOps.projectTask(b, "Lease")!!.steps
        b = BoardOps.mapStep(b, steps[0].id) { it.copy(date = "2026-11-06", slot = 0) }
        b = BoardOps.mapStep(b, steps[1].id) { it.copy(date = "2026-11-09", slot = 0) }
        val (after, placed) = Fill.bigs(b, monday)
        assertEquals(listOf("Lease: Draft"), placed.map { it.replace(" · ", ": ") }.take(1).ifEmpty { placed })
        assertEquals(monday.toString(), BoardOps.findStep(after, steps[0].id)!!.second.date)
        assertTrue(BoardOps.findStep(after, steps[1].id)!!.second.date != monday.toString(), "the second waits for the first")
    }
}
