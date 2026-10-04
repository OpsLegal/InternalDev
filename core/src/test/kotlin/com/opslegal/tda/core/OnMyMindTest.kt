package com.opslegal.tda.core

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.agent.OnMyMind
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.MindItem
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.CalendarCells
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnMyMindTest {
    private val friday = LocalDate.parse("2026-10-09")

    private fun mind(vararg lines: String) = Board(mind = lines.mapIndexed { i, t -> MindItem("m$i", t) })

    @Test
    fun everyLineLandsSomewhereAndTheListEmpties() {
        val b = mind("Call the bank", "Email the accountant", "Buy printer ink", "Website redo someday", "Renew passport", "Garden party")
        val reply = """{"items":[{"line":1,"kind":"quick","title":"Call the bank","rank":2},{"line":2,"kind":"quick","title":"Email the accountant","rank":3},
            {"line":3,"kind":"buy","title":"Printer ink","rank":9},{"line":4,"kind":"idea","title":"Website redo","rank":9},
            {"line":6,"kind":"task","title":"Plan the garden party","rank":5,"work":false}]}"""
        val sorted = OnMyMind.parse(reply, b)
        assertEquals(6, sorted.size, "The line the AI skipped (passport) still becomes a task")
        val after = OnMyMind.place(b, sorted, friday.atTime(9, 0))
        assertTrue(after.mind.isEmpty())
        assertEquals(listOf("Printer ink"), after.buy.map { it.text })
        assertTrue(after.projects.any { it.name == "Website redo" })
        val quick = after.tasks.single { it.title.startsWith("Quick things") }
        assertEquals("Quick things (2)", quick.title)
        // Work stays on work days; personal life may take the weekend.
        val party = after.tasks.single { it.title == "Plan the garden party" }
        assertTrue(party.personal)
        val passport = after.tasks.single { it.title == "Renew passport" }.steps.single().date!!
        assertTrue(LocalDate.parse(passport).dayOfWeek.value <= 5)
    }

    @Test
    fun newThingsNeverMoveWhatIsPlannedAndRespectTheCalendar() {
        var b = Board()
        repeat(3) { i -> b = BoardOps.addTaskOn(b, BoardOps.NewTask("Planned $i"), friday, friday).first }
        val events = listOf(CalendarEvent("Court hearing", "2026-10-09T09:00", "2026-10-09T12:30"))
        b = b.copy(reserved = CalendarCells.reserved(b, events), mind = listOf(MindItem("a", "One"), MindItem("b", "Two")))
        val after = OnMyMind.place(b, OnMyMind.parse("", b), friday.atTime(9, 0))
        val planned = after.tasks.filter { it.title.startsWith("Planned") }.map { it.steps.single().date }
        assertTrue(planned.all { it == "2026-10-09" }, "Nothing already planned moves")
        // Friday: 3 cells + 2 reserved by the hearing = full, so the new ones go to Monday (not the weekend).
        assertEquals(listOf("2026-10-12", "2026-10-12"), after.tasks.filter { it.title in setOf("One", "Two") }.map { it.steps.single().date })
        // Late in the day, nothing new lands today.
        val late = OnMyMind.place(mind("Three"), OnMyMind.parse("", mind("Three")), LocalDate.parse("2026-10-07").atTime(16, 0))
        assertEquals("2026-10-08", late.tasks.single().steps.single().date)
    }

    @Test
    fun aParkedProjectWaitsWithTheIdeasAndResumes() {
        var b = Planner.plan(BoardOps.add(Board(), BoardOps.NewTask("Refi", project = "Refi", stepTitles = listOf("A", "B")), friday).board, friday).board
        val p = b.projects.single()
        b = Projects.park(b, "Refi", friday)
        assertTrue(Projects.isIdea(b, p) && Projects.isParked(b, p))
        assertTrue(b.tasks.single().steps.all { it.date == null })
        b = Projects.resume(b, "Refi", friday)
        assertTrue(!Projects.isIdea(b, p) && b.tasks.single().steps.all { it.date != null })
    }

    @Test
    fun aWorkDeadlineThatWorkDaysCannotMeetMayUseTheWeekend() {
        var b = Board()
        // Friday is full; the deadline is Monday (buffer 1 day = Sunday): Saturday rescues it.
        repeat(5) { i -> b = BoardOps.addTaskOn(b, BoardOps.NewTask("Busy $i"), friday, friday).first }
        b = BoardOps.add(b, BoardOps.NewTask("Brief", deadline = "2026-10-12"), friday).board
        b = Planner.plan(b, friday).board
        val day = LocalDate.parse(b.tasks.single { it.title == "Brief" }.steps.single().date!!)
        assertTrue(day.dayOfWeek.value >= 6 && day.isBefore(LocalDate.parse("2026-10-12")), "Got $day")
    }
}
