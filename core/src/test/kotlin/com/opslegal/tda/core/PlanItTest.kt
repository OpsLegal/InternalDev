package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.PlanIt
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Quick
import com.opslegal.tda.core.plan.Replan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 📅 Plan it: today (saying what moves out when full), the next days with room, or a day picked. Missed meetings aren't moved. */
class PlanItTest {
    private val monday = LocalDate.parse("2026-10-19")

    @Test
    fun aFullTodayLineSaysWhatMovesAndMovesIt() {
        var b = Board(settings = PlannerSettings(quickPerDay = 2))
        b = Quick.add(b, listOf("Pay the dentist"), monday, monday, impact = 1).first
        b = Quick.add(b, listOf("Book the garage"), monday, monday, impact = 3).first
        b = Quick.keep(b, monday)
        b = Quick.add(b, listOf("📞 Call back Mom"), null, monday, impact = 3).first
        val call = b.quick.last().id
        assertEquals("Pay the dentist", PlanIt.todayCost(b, PlanIt.Kind.QUICK, monday, call), "the one that matters least")
        assertEquals(listOf(monday.plusDays(1), monday.plusDays(2)), PlanIt.soon(b, PlanIt.Kind.QUICK, monday))
        val after = PlanIt.quickTo(b, call, monday, monday)
        val todayLine = Quick.onDay(after, monday, monday).map { it.title }
        assertTrue("📞 Call back Mom" in todayLine && "Pay the dentist" !in todayLine)
        assertEquals(monday.plusDays(1).toString(), after.quick.first { it.title == "Pay the dentist" }.date)
        // Another day: off today's line, on that day.
        val later = PlanIt.quickTo(after, call, monday.plusDays(3), monday)
        assertEquals(monday.plusDays(3).toString(), later.quick.first { it.id == call }.date)
        assertTrue(call !in Quick.todayIds(later, monday))
    }

    @Test
    fun aCellGoesOnThePickedDayAndAFullDayMakesRoom() {
        val tuesday = monday.plusDays(1)
        var b = Board(settings = PlannerSettings(bigPerDay = 2))
        b = (1..2).fold(b) { x, i -> BoardOps.addTaskOn(x, NewTask(title = "Busy $i"), tuesday, monday).first }
        b = BoardOps.add(b, NewTask(title = "Write the offer"), monday).board
        val step = b.tasks.last().steps.first().id
        assertEquals(0, PlanIt.free(b, PlanIt.Kind.CELL, tuesday, monday))
        assertTrue(tuesday !in PlanIt.soon(b, PlanIt.Kind.CELL, monday))
        val after = PlanIt.cellTo(b, step, tuesday, monday)
        assertEquals(tuesday.toString(), BoardOps.findStep(after, step)!!.second.date)
        assertNull(PlanIt.todayCost(Board(), PlanIt.Kind.CELL, monday), "an empty day has room")
    }

    @Test
    fun aMissedMeetingIsNotMovedAndARepeatingOneIsKnown() {
        var b = Board(settings = PlannerSettings(bigPerDay = 3))
        b = BoardOps.addTaskOn(b, NewTask(title = "17:00 MFounders Weekly", kind = TaskKind.MEETING, fixedDate = monday.toString()), monday, monday).first
        b = BoardOps.addTaskOn(b, NewTask(title = "Lunch avec Fayçal", kind = TaskKind.MEETING, fixedDate = monday.toString()), monday, monday).first
        b = BoardOps.add(b, NewTask(title = "Old report"), monday).board
        b = Planner.plan(b, monday).board
        val p = Replan.propose(b, monday.plusDays(7))
        val weekly = p.items.first { it.title.contains("Weekly") }
        val lunch = p.items.first { it.title.contains("Lunch") }
        assertTrue(weekly.meeting && weekly.repeating && weekly.to == null)
        assertTrue(lunch.meeting && !lunch.repeating && lunch.to == null)
        assertTrue(p.items.first { it.title == "Old report" }.to != null, "a task still gets its day")
        // Postponed: a day picked for it wins.
        val day = monday.plusDays(10)
        assertEquals(day, Replan.propose(b, monday.plusDays(7), picks = mapOf(lunch.stepId to day)).items.first { it.stepId == lunch.stepId }.to)
    }
}
