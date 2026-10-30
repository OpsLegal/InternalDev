package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Quick
import com.opslegal.tda.core.plan.Updates
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
    fun quickThingsArePickedFromThePoolAndCanBeSwapped() {
        val b0 = board.copy(settings = board.settings.copy(quickPerDay = 2))
        val (b1, days) = Quick.add(b0, listOf("Reply to Marc", "Call the garage", "Pay the ticket"), null, monday)
        assertEquals(listOf(null, null, null), days, "no day given: the pool")
        val b2 = Quick.edit(b1, b1.quick.first { it.title == "Pay the ticket" }.id) { it.copy(due = monday.plusDays(1).toString(), impact = 3) }
        val today = Planner.rows(b2, monday, 1, today = monday).single().quick
        assertEquals(2, today.size, "your number a day")
        assertEquals("Pay the ticket", today.first().title, "closest deadline and most impact first")
        val left = b2.quick.first { q -> today.none { it.id == q.id } }
        val swapped = Quick.swap(b2, today.last().id, monday)
        assertTrue(Quick.todayIds(swapped, monday).contains(left.id), "a swap brings in the next one")
        val set = Quick.add(swapped, listOf("Confirm dinner"), monday.plusDays(2), monday).first
        assertEquals(1, Planner.rows(set, monday.plusDays(2), 1, today = monday).single().quick.size, "a set day stays on its day")
        val back = Quick.rollover(set, monday.plusDays(3))
        assertTrue(back.quick.first { it.title == "Confirm dinner" }.date.isBlank(), "not done on its day: back to the pool, never red")
        val done = Quick.tick(b2, today.first().id, true, monday)
        assertEquals(1, Planner.rows(done, monday, 1, today = monday.plusDays(1)).single().quick.size, "a past day shows what was done that day")
        assertEquals(today.map { it.id }, Quick.todayIds(done, monday), "a tick keeps today's line as it is")
    }

    @Test
    fun onMyMindNotesJoinThePoolAndLinesAreSorted() {
        val old = board.copy(mind = listOf(com.opslegal.tda.core.model.MindItem("m1", "Call the bank")))
        val b = Quick.rollover(old, monday)
        assertTrue(b.mind.isEmpty() && b.quick.single().let { it.title == "Call the bank" && it.check && it.date.isBlank() }, "old notes join the pool, to check")
        assertEquals("buy", com.opslegal.tda.core.plan.Labels.kindOf("milk and bread"))
        assertEquals("task", com.opslegal.tda.core.plan.Labels.kindOf("Prepare the ACME report"))
        assertEquals("quick", com.opslegal.tda.core.plan.Labels.kindOf("Call the garage"))
    }

    @Test
    fun labelsKeepOfficesAndPeopleOnWeekdays() {
        assertEquals("office", com.opslegal.tda.core.plan.Labels.whoOf("Call the Tribunal du logement"))
        assertEquals("alone", com.opslegal.tda.core.plan.Labels.whoOf("Fill the passport request online"))
        assertEquals("others", com.opslegal.tda.core.plan.Labels.whoOf("Ask the school about the trip"))
        assertEquals("quick", com.opslegal.tda.core.plan.Labels.sizeOf("Call the garage"))
        assertEquals("big", com.opslegal.tda.core.plan.Labels.sizeOf("Prepare the ACME report"))
        val friday = LocalDate.parse("2026-10-30")
        val b = BoardOps.add(Board(settings = PlannerSettings(bigPerDay = 3)), NewTask(title = "Call the passport office", area = "home"), friday).board
        val full = (1..3).fold(b) { x, i -> BoardOps.addTaskOn(x, NewTask(title = "Busy $i", area = "home"), friday, friday).first }
        val day = Planner.plan(full, friday).board.tasks.first { it.title == "Call the passport office" }.steps.first().date
        assertEquals("2026-11-02", day, "Friday is full: Monday, never the weekend, for an office")
    }

    @Test
    fun aCardWhoseTaskIsAlreadyOnTheTableStopsOfferingIt() {
        val card = com.opslegal.tda.core.model.Update("u1", "whatsapp", "Laila", "Appelle ton papa demain", "Laila asks you to call your dad",
            actions = listOf(com.opslegal.tda.core.model.UpdateAction("add", title = "Appeler papa pour les enfants")))
        val b0 = board.copy(updates = listOf(card))
        assertEquals(1, com.opslegal.tda.core.plan.Updates.inbox(b0).size)
        val byHand = Planner.plan(BoardOps.add(b0, NewTask(title = "Appeler papa"), monday).board, monday).board
        // Fewer words by hand ("Appeler papa" for "Appeler papa pour les enfants"): asked once, then closed with one tap.
        assertEquals(false, com.opslegal.tda.core.plan.Updates.onTable(byHand, byHand.updates.single(), monday)?.sure)
        val settled = com.opslegal.tda.core.plan.Updates.sameAnswer(byHand, "u1", true)
        assertEquals(0, com.opslegal.tda.core.plan.Updates.inbox(settled).size, "said the same: the card goes")
        val doneAfter = settled.tasks.first { it.title == "Appeler papa" }.steps.first().id.let { BoardOps.setStepDone(settled, it, true) }
        assertEquals(0, com.opslegal.tda.core.plan.Updates.inbox(doneAfter).size, "done later: it doesn't come back")
        val asQuick = Quick.add(b0, listOf("Appeler papa pour les enfants"), monday, monday).first
        assertEquals(0, com.opslegal.tda.core.plan.Updates.inbox(asQuick).size, "as a quick thing too")
        val waits = b0.copy(updates = listOf(card.copy(needsReply = true)), quick = asQuick.quick)
        assertEquals(1, com.opslegal.tda.core.plan.Updates.inbox(waits).size, "an answer still waits: the card stays")
    }

    @Test
    fun theTableAnswersCardsSurelyOrAsks() {
        val now = java.time.LocalDateTime.parse("2026-10-26T09:00")
        fun card(id: String, title: String, at: String = "2026-10-25T08:00") = com.opslegal.tda.core.model.Update(id, "outlook", "Marc", title, title, at = at,
            actions = listOf(com.opslegal.tda.core.model.UpdateAction("add", title = title)))
        val b0 = Planner.plan(BoardOps.add(board, NewTask(title = "Send the lease to Marc"), monday).board, monday).board
        // Sure: the same words. Closed once, said once, and it can come back.
        val sure = Updates.settle(b0.copy(updates = listOf(card("a", "Send the lease to Marc"))), now)
        assertEquals("planned", sure.updates.single().cleared)
        assertEquals(1, Updates.clearedToday(sure, now.toLocalDate()).size)
        val back = Updates.bringBack(sure, "a", now.toLocalDate())
        assertEquals(1, Updates.inbox(back).size, "brought back, and never closed again by that cell")
        assertEquals(1, Updates.inbox(Updates.settle(back, now)).size)
        // Alike but not sure: asked, never closed by itself.
        val maybe = b0.copy(updates = listOf(card("b", "Lease for Marc: send the signed copy")))
        val f = Updates.onTable(maybe, maybe.updates.single(), monday)
        assertTrue(f != null && !f.sure)
        assertEquals(maybe, Updates.settle(maybe, now))
        assertEquals(0, Updates.inbox(Updates.sameAnswer(maybe, "b", true, now)).size)
        val no = Updates.sameAnswer(maybe, "b", false, now)
        assertEquals(null, Updates.onTable(no, no.updates.single(), monday), "that cell is never offered again")
        // Done before the message came: maybe a new request, so asked.
        val doneBefore = Planner.plan(BoardOps.add(board, NewTask(title = "Call the dentist"), monday.minusDays(5)).board, monday.minusDays(5)).board
            .let { b -> BoardOps.setStepDone(b, b.tasks.first().steps.first().id, true) }
        val again = doneBefore.copy(updates = listOf(card("c", "Call the dentist", at = "2026-10-26T08:00")))
        assertEquals(false, Updates.onTable(again, again.updates.single(), monday)?.sure)
    }

    @Test
    fun stepsTickedInTheProjectFormBecomeDoneOrToDo() {
        val proj = com.opslegal.tda.core.model.Project("Renovation")
        var b = com.opslegal.tda.core.plan.Projects.save(board, proj, null, listOf("Quote", "Order", "Install").map { BoardOps.EditedStep(null, it) }, monday).board
        val steps = BoardOps.projectTask(b, "Renovation")!!.steps
        val order = steps[1]
        // "Order" ticked done in the form: it leaves the edited list and becomes a done cell, today at the latest.
        b = com.opslegal.tda.core.plan.Projects.save(b, proj, "Renovation", steps.filter { it.id != order.id }.map { BoardOps.EditedStep(it.id, it.title) }, monday, done = setOf(order.id)).board
        val after = BoardOps.findStep(b, order.id)!!.second
        assertTrue(after.done && after.date!! <= monday.toString(), "done today at the latest")
        // Not done again: back in the list, to do.
        b = com.opslegal.tda.core.plan.Projects.save(b, proj, "Renovation", steps.map { BoardOps.EditedStep(it.id, it.title) }, monday, reopened = setOf(order.id)).board
        assertTrue(!BoardOps.findStep(b, order.id)!!.second.done, "to do again")
        assertEquals(3, BoardOps.projectTask(b, "Renovation")!!.steps.size, "nothing doubled")
    }
}
