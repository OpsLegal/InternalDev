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

/** Old undone big tasks come back from tomorrow, most important first, on days that still have room for what is really done. */
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
        assertTrue(days.groupBy { it }.values.all { it.size <= Replan.realistic(planned, nextMonday) }, "never more than what is really done in a day")
        assertTrue(days.all { it.isAfter(nextMonday) }, "never today or in the past")
        // A day picked by the user wins.
        val pick = nextMonday.plusDays(9)
        assertEquals(pick, Replan.propose(planned, nextMonday, picks = mapOf(p.items.first().stepId to pick)).items.first { it.stepId == p.items.first().stepId }.to)
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

    @Test
    fun whatComesFirstOrdersTheGroupsAndAnEmergencyCanTakeToday() {
        val today = nextMonday
        var b = Board(settings = PlannerSettings(bigPerDay = 3))
        b = BoardOps.add(b, NewTask(title = "Tidy the garage"), today).board
        b = BoardOps.add(b, NewTask(title = "Send the quote to the client", deadline = today.plusDays(10).toString()), today).board
        b = BoardOps.add(b, NewTask(title = "Draft my own notes", deadline = today.plusDays(5).toString()), today).board
        val t = { title: String -> b.tasks.first { it.title == title } }
        assertEquals("normal", com.opslegal.tda.core.plan.Order.group(b, t("Tidy the garage"), null, today))
        assertEquals("promised", com.opslegal.tda.core.plan.Order.group(b, t("Send the quote to the client"), null, today), "client words: expected outside")
        assertEquals("due", com.opslegal.tda.core.plan.Order.group(b, t("Draft my own notes"), null, today))
        // The tag wins over the words.
        val untagged = b.copy(tasks = b.tasks.map { if (it.title.startsWith("Send")) it.copy(expected = false) else it })
        assertEquals("due", com.opslegal.tda.core.plan.Order.group(untagged, untagged.tasks.first { it.title.startsWith("Send") }, null, today))
        // The usual order starts with emergencies; moving the first one up does nothing.
        val moved = com.opslegal.tda.core.plan.Order.move(b, "emergency", -1)
        assertEquals("emergency", com.opslegal.tda.core.plan.Order.order(b).first())
        assertEquals(b.settings.order, moved.settings.order, "a move past the top does nothing")
        // Fill today, then an emergency arrives: it is asked once, and can take today's lowest cell.
        b = (1..3).fold(b) { x, i -> BoardOps.addTaskOn(x, NewTask(title = "Today $i"), today, today).first }
        b = BoardOps.add(b, NewTask(title = "Court filing reply", deadline = today.plusDays(1).toString()), today).board
        val em = b.tasks.first { it.title == "Court filing reply" }
        val ask = com.opslegal.tda.core.plan.Order.toAsk(b, today)
        assertEquals(em.id, ask?.first?.id)
        val plan = com.opslegal.tda.core.plan.Order.today(b, em.steps.first().id, today)!!
        assertTrue(plan.outStep != null && plan.to!!.isAfter(today))
        val after = com.opslegal.tda.core.plan.Order.takeToday(b, em.steps.first().id, today)
        assertEquals(today.toString(), after.tasks.first { it.id == em.id }.steps.first().date)
        assertEquals(plan.to.toString(), after.tasks.flatMap { it.steps }.first { it.id == plan.outStep }.date)
        assertEquals(null, com.opslegal.tda.core.plan.Order.toAsk(after, today), "asked once")
    }
}
