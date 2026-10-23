package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Quick
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Three big tasks a day by default in the app; quick things sit on the day's ⚡ line and roll over, never red. */
class BigAndQuickTest {
    private val monday = LocalDate.parse("2026-10-26")
    private val board = Board(settings = PlannerSettings(bigPerDay = 3))

    private fun withTasks(n: Int): Board = (1..n).fold(board) { b, i -> BoardOps.add(b, NewTask(title = "Task $i"), monday).board }

    @Test
    fun aFourthBigTaskGoesToTheNextDay() {
        val planned = Planner.plan(withTasks(4), monday).board
        val byDay = planned.tasks.flatMap { it.steps }.groupBy { it.date }
        assertEquals(3, byDay[monday.toString()]?.size)
        assertEquals(1, byDay[monday.plusDays(1).toString()]?.size)
        assertTrue(planned.tasks.flatMap { it.steps }.all { (it.slot ?: 0) < 3 })
        assertEquals(3, Planner.rows(planned, monday, 1).single().cells.size)
    }

    @Test
    fun loweringTheNumberGivesTheExtraCellsBackWithoutLosingThem() {
        val five = Planner.plan(withTasks(5).copy(settings = PlannerSettings(bigPerDay = 5)), monday).board
        val three = Planner.plan(five.copy(settings = PlannerSettings(bigPerDay = 3)), monday).board
        assertEquals(5, three.tasks.flatMap { it.steps }.count { it.date != null }, "every task still has a day")
        assertEquals(3, three.tasks.flatMap { it.steps }.count { it.date == monday.toString() })
    }

    @Test
    fun quickThingsFillTheDayThenTheNextAndRollOver() {
        val b0 = board.copy(settings = board.settings.copy(quickMax = 2))
        val (b1, days) = Quick.add(b0, listOf("Reply to Marc", "Call the garage", "Pay the ticket"), monday, monday)
        assertEquals(listOf(monday, monday, monday.plusDays(1)), days)
        val row = Planner.rows(b1, monday, 1).single()
        assertEquals(2, row.quick.size)
        val ticked = row.quick.fold(b1) { b, q -> Quick.tick(b, q.id, true) }
        assertTrue(Planner.rows(ticked, monday, 1).single().complete, "only quick things that day, all done")
        val later = Quick.rollover(Quick.tick(b1, b1.quick.first().id, true), monday.plusDays(2))
        assertEquals(2, later.quick.count { it.date == monday.plusDays(2).toString() }, "the two left come to today")
    }
}
