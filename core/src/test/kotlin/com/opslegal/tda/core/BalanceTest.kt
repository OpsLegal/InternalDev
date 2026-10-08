package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Routine
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.Holidays
import com.opslegal.tda.core.plan.Pacing
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Routines
import com.opslegal.tda.core.plan.Tags
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BalanceTest {
    private val today = LocalDate.parse("2026-10-08")

    @Test
    fun quebecHolidaysAndTheChristmasBreak() {
        assertEquals(LocalDate.parse("2027-03-28"), Holidays.easter(2027))
        assertTrue(Holidays.isOff(LocalDate.parse("2026-10-12"), "QC")) // Thanksgiving
        assertTrue(Holidays.isOff(LocalDate.parse("2026-06-24"), "QC"))
        assertTrue(Holidays.isOff(LocalDate.parse("2026-12-28"), "QC"))
        assertFalse(Holidays.isOff(LocalDate.parse("2026-10-13"), "QC"))
        assertFalse(Holidays.isOff(LocalDate.parse("2026-10-12"), ""))
    }

    @Test
    fun aProjectIsPacedToItsDeadlineWithABuffer() {
        val deadline = today.plusDays(75)
        val steps = Pacing.fill(listOf("Gather credentials", "Draft the proposal", "Submit to ACT").map { Pacing.Filled(it) }, "Our client ACT must approve")
        assertTrue(steps.any { it.title == "Integrate the feedback" && it.waitDays == 5 && it.added })
        assertTrue(steps.any { it.title == "Final review" })
        val saved = Projects.save(Board(), Project("OPS - ACT", deadline = deadline.toString()), null,
            steps.map { BoardOps.EditedStep(null, it.title, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added) }, today).board
        val dates = BoardOps.projectTask(saved, "OPS - ACT")!!.steps.map { LocalDate.parse(it.date!!) }
        assertTrue(dates.zipWithNext().all { (a, b) -> b.isAfter(a) }, "$dates")
        assertTrue(dates.last().isBefore(deadline.minusDays(2)), "$dates")
        assertTrue(dates.last().isAfter(today.plusDays(30)), "spread, not jammed: $dates")
        assertTrue(dates.none { Holidays.isOff(it, "QC") })
        // Bring forward: the steps that wait for no one come sooner.
        assertTrue(Flags.sooner(saved, "OPS - ACT", today) > 0)
        val sooner = Flags.bringForward(saved, "OPS - ACT", today)
        val end = Projects.end(sooner, "OPS - ACT").end!!
        assertTrue(end.isBefore(dates.last()), "$end")
    }

    @Test
    fun tagsTakeTheHighestWeightAndHandSetWeightsWin() {
        var b = Tags.toggle(Board(), "Lawyer")
        b = Tags.toggle(b, "Parent")
        fun w(n: String) = b.values.single { it.name == n }.weight
        assertEquals(3, w("Career")); assertEquals(3, w("Relations")); assertEquals(2, w("Learning"))
        b = Tags.apply(b.copy(adjust = mapOf("Career" to 1)))
        assertEquals(1, w("Career"))
        b = Tags.toggle(b.copy(adjust = emptyMap()), "Lawyer")
        assertTrue(b.values.none { it.name == "Learning" })
    }

    @Test
    fun routinesCountInTheWeekAndCrowdedMomentsAreFound() {
        val base = Tags.apply(Board())
        assertEquals(4, Routines.base(base)["Health"]) // no routine: average
        val r = (1..3).map { Routine("r$it", "R$it", listOf(1, 3), Moment.MORNING, mapOf("Health" to 2)) }
        val b = base.copy(routines = r)
        assertEquals(12, Routines.base(b)["Health"])
        assertEquals(listOf("1:MORNING", "3:MORNING"), Routines.crowded(b).map { it.key })
        assertEquals(listOf("3:MORNING"), Routines.crowded(b.copy(routineFine = listOf("1:MORNING"))).map { it.key })
        assertEquals(6 to Moment.MORNING, Routines.freeSlot(b))
    }

    @Test
    fun freeCellsTodayFlagWorkPlannedLater() {
        var b = Board()
        b = BoardOps.addTaskOn(b, BoardOps.NewTask("Call Jean"), today.plusDays(1), today).first
        val f = Flags.all(b, today)
        assertTrue(f.any { it.id.startsWith("now:") }, "$f")
        val id = f.first { it.id.startsWith("now:") }.id.removePrefix("now:")
        val moved = Flags.doToday(b, id, today)
        assertEquals(today.toString(), moved.tasks.single().steps.single().date)
        val snoozed = Flags.notNow(b, "now:$id", "Call Jean", "Bigger than it looks", today)
        assertTrue(Flags.all(snoozed, today).none { it.id == "now:$id" })
    }

    @Test
    fun pushReasonsReachTheWeeklyReview() {
        var b = BoardOps.addTaskOn(Board(), BoardOps.NewTask("Draft the lease"), today, today).first
        val id = b.tasks.single().steps.single().id
        b = BoardOps.pushStep(b, id, today, "Bigger than it looks: needs the client's numbers")
        val f = com.opslegal.tda.core.agent.WeekReview.facts(b, today.with(java.time.DayOfWeek.MONDAY), today)
        assertEquals(listOf("Draft the lease: Bigger than it looks: needs the client's numbers"), f.reasons)
        assertTrue(com.opslegal.tda.core.agent.WeekReview.prompt(b, f).contains("needs the client's numbers"))
    }

    @Test
    fun extendMakesTheCellLongerTheSameDayNextToIt() {
        var b = Board()
        b = BoardOps.addTaskOn(b, BoardOps.NewTask("Write the brief"), today, today).first
        val st = b.tasks.single().steps.single()
        val e = Projects.longer(b, st.id, today)!!
        assertEquals(today, e.day)
        val placed = Projects.placeNear(e.board, e.stepToPlace, e.day, st.slot)!!
        val cont = BoardOps.findStep(placed, e.stepToPlace)!!.second
        assertEquals(today.toString(), cont.date)
        assertEquals(st.slot!! + 1, cont.slot)
        // A full day: no free cell, so the day must make room (or the user splits it).
        var full = Board()
        repeat(5) { i -> full = BoardOps.addTaskOn(full, BoardOps.NewTask("Busy $i"), today, today).first }
        val f0 = full.tasks.first().steps.single()
        val e2 = Projects.longer(full, f0.id, today)!!
        assertEquals(null, Projects.placeNear(e2.board, e2.stepToPlace, today, f0.slot))
    }
}
