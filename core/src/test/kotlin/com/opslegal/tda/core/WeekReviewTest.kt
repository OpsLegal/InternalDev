package com.opslegal.tda.core

import com.opslegal.tda.core.agent.WeekReview
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
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
        // Monday: 2 morning cells done, 1 afternoon cell left undone (slips at rollover), 1 afternoon cell pushed.
        repeat(4) { i -> b = BoardOps.addTaskOn(b, BoardOps.NewTask("T$i"), monday, monday).first }
        val steps = b.tasks.map { it.steps.single() }
        b = BoardOps.setStepDone(b, steps[0].id, true)
        b = BoardOps.setStepDone(b, steps[1].id, true)
        b = BoardOps.pushStep(b, steps[3].id, monday)
        b = Planner.dailyRefresh(b, monday.plusDays(1)).board
        val f = WeekReview.facts(b, monday, monday.plusDays(4))
        assertEquals(2, f.done)
        assertEquals(2, f.moved, "One pushed, one slipped")
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
}
