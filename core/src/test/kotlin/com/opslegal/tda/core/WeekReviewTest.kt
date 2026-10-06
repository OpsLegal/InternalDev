package com.opslegal.tda.core

import com.opslegal.tda.core.agent.WeekReview
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.ReplySettings
import com.opslegal.tda.core.plan.Updates
import com.opslegal.tda.core.agent.UpdateCheck
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekReviewTest {
    private val monday = LocalDate.parse("2026-09-28")

    @Test
    fun factsComeFromTheTableAndTheLogOfMovedCells() {
        var b = Board()
        // Monday: 2 morning cells done, 1 afternoon cell left undone (stays red), 1 afternoon cell pushed.
        repeat(4) { i -> b = BoardOps.addTaskOn(b, BoardOps.NewTask("T$i"), monday, monday).first }
        val steps = b.tasks.map { it.steps.single() }
        b = BoardOps.setStepDone(b, steps[0].id, true)
        b = BoardOps.setStepDone(b, steps[1].id, true)
        b = BoardOps.pushStep(b, steps[3].id, monday)
        b = Planner.dailyRefresh(b, monday.plusDays(1)).board
        val f = WeekReview.facts(b, monday, monday.plusDays(1))
        assertEquals(2, f.done)
        assertEquals(2, f.moved, "One pushed, one not done")
        assertEquals(4, f.planned)
        assertEquals(2 to 2, f.morningDone to f.morningAll)
        assertEquals(0, f.afternoonDone)
        assertTrue(WeekReview.prompt(b, f).contains("COACH PLAYBOOK"))
    }

    @Test
    fun reviewTimeAndOneSuggestionAWeek() {
        assertNull(WeekReview.weekToReview(LocalDateTime.parse("2026-10-01T10:00")), "Thursday: not yet")
        assertEquals(monday, WeekReview.weekToReview(LocalDateTime.parse("2026-10-02T14:00")))
        assertEquals(monday, WeekReview.weekToReview(LocalDateTime.parse("2026-10-05T09:00")), "Monday morning: last week")
        val a = WeekReview.parse("""{"pattern":"Mornings work.","suggestion":"Long reading in the first morning cell.","rule":"Long reading goes in the first morning cell.","check":"Fewer moves.","lastWeek":""}""")!!
        val f = WeekReview.facts(Board(), monday, monday.plusDays(4))
        var b = WeekReview.accept(Board(), monday, f, a)
        assertEquals("Long reading goes in the first morning cell.", b.rules.last().text)
        assertTrue(!WeekReview.due(b, LocalDateTime.parse("2026-10-03T10:00")), "Reviewed this week")
        b = WeekReview.decline(Board(), monday, f, a)
        assertEquals(listOf(a.suggestion), b.review.declined)
        assertTrue(b.rules.isEmpty())
    }

    @Test
    fun theSweepBringsBackOnlyWhatIsNotInHandAndProjectsShowTheirWeek() {
        val mail = Incoming("i1", "outlook", "Jean", "Contract? — Can you send it?", "2026-09-10T09:00", mailId = "M1")
        val chat = Incoming("i2", "whatsapp", "Sophie", "Clause 12?", "2026-09-12T09:00", chatId = "C1")
        val other = Incoming("i3", "outlook", "Marc", "Plumber?", "2026-09-15T09:00", mailId = "M3")
        var b = Board(updates = listOf(
            Update("u1", "outlook", "Jean", "x", "Jean waits.", needsReply = true, mailId = "M1"),
            Update("u3", "outlook", "Marc", "x", "Marc waits.", needsReply = true, replied = true, mailId = "M3", handledAs = "not_now"),
        ), replies = ReplySettings(on = true))
        assertEquals(listOf("i2", "i3"), Updates.sweepable(b, listOf(mail, chat, other)).map { it.id }, "Waiting stays out; not this time comes back")
        assertTrue(UpdateCheck.prompt(b, listOf(chat), monday, sweep = true).contains("THE WEEKLY SWEEP"))
        // A project with a step done this week, one stalled.
        b = BoardOps.add(b, BoardOps.NewTask("Refi", project = "Refi", stepTitles = listOf("A", "B")), monday).board
        b = BoardOps.add(b, BoardOps.NewTask("Tax", project = "Tax", stepTitles = listOf("C", "D")), monday).board
        b = Planner.plan(b, monday).board
        b = BoardOps.setStepDone(b, BoardOps.projectTask(b, "Refi")!!.steps.first().id, true)
        val f = WeekReview.facts(b, monday, monday.plusDays(4))
        val refi = f.projectMoves.single { it.name == "Refi" }
        assertEquals(1 to 50, refi.doneThisWeek to refi.percent)
        assertTrue(f.projectMoves.single { it.name == "Tax" }.stalled)
        assertTrue(WeekReview.prompt(b, f).contains("no step done this week"))
    }
}
