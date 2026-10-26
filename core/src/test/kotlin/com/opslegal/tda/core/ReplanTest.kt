package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Replan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Old undone big tasks come back one a day, on days that still have room for what is really done, never all tomorrow. */
class ReplanTest {
    private val monday = LocalDate.parse("2026-10-19")
    private val nextMonday = LocalDate.parse("2026-10-26")

    @Test
    fun undoneBigTasksAreSpreadOneADay() {
        // Last week: 3 big tasks a day planned; Monday's three were never done.
        val b0 = (1..3).fold(Board(settings = PlannerSettings(bigPerDay = 3))) { b, i -> BoardOps.add(b, NewTask(title = "Old task $i"), monday).board }
        val planned = Planner.plan(b0, monday).board
        assertEquals(3, Replan.waiting(planned, nextMonday).size)
        val p = Replan.propose(planned, nextMonday)
        val days = p.items.mapNotNull { it.to }
        assertEquals(3, days.size)
        assertEquals(3, days.distinct().size, "one old task a day, never three tomorrow")
        assertTrue(days.all { it.isAfter(nextMonday) })
        val after = Replan.apply(planned, p.items, nextMonday)
        assertEquals(0, Replan.waiting(after, nextMonday).size)
        assertEquals(3, after.tasks.flatMap { it.steps }.count { it.outcome == com.opslegal.tda.core.model.Outcome.MISSED }, "the red records stay")
    }

    @Test
    fun aDayAlreadyFullOfRealWorkIsSkippedAndLaterMovesOne() {
        val b0 = BoardOps.add(Board(settings = PlannerSettings(bigPerDay = 3)), NewTask(title = "Old report"), monday).board
        val old = Planner.plan(b0, monday).board
        val tuesday = nextMonday.plusDays(1)
        val full = (1..3).fold(old) { b, i -> BoardOps.addTaskOn(b, NewTask(title = "Busy $i"), tuesday, nextMonday).first }
        val p = Replan.propose(full, nextMonday)
        assertEquals(nextMonday.plusDays(2), p.items.single().to, "Tuesday has its 3 big tasks: Wednesday")
        assertEquals(nextMonday.plusDays(3), Replan.propose(full, nextMonday, later = mapOf(p.items.single().stepId to 1)).items.single().to)
    }
}
