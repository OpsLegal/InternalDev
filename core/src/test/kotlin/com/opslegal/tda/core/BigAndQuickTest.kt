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
}
