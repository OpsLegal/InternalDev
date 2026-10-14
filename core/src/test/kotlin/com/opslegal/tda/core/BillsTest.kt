package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.Balance
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.Gbn
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** To pay: typed, from an email, one Pay bills cell, paid when the cell is done, late bills are open loops. */
class BillsTest {
    private val today = LocalDate.parse("2026-10-14") // a Wednesday
    private fun board() = Gbn.turnOn(Board(settings = PlannerSettings(holidays = "")))

    @Test
    fun aTypedBillIsReadOnThePhone() {
        val b = Bills.parse("Hydro-Québec 142,37 $ due 28", today)
        assertEquals("Hydro-Québec", b.vendor)
        assertEquals(142.37, b.amount)
        assertEquals("2026-10-28", b.due)
        assertEquals("Home", b.category)
        assertEquals("2026-11-05", Bills.parse("Vidéotron 96.53 le 5", today).due, "a day already past this month is next month's")
    }

    @Test
    fun oneCellPaysThemAllAndDoneMarksThemPaid() {
        var b = Bills.add(Bills.add(board(), Bills.parse("Bell 85.20 due 18", today)), Bills.parse("Hydro 142.37 due 20", today))
        assertEquals(2, Bills.soon(b, today).size)
        assertNotNull(Flags.all(b, today).firstOrNull { it.kind == "bills" } ?: Flags.all(b, today.plusDays(2)).firstOrNull { it.kind == "bills" })
        val (planned, day) = Bills.planCell(b, today)
        assertEquals(LocalDate.parse("2026-10-17"), day, "the day before Sunday the 18th (Personal is planned every day)")
        val cell = planned.tasks.single { it.billsCell }
        assertTrue(planned.bills.all { it.cell == cell.id })
        b = BoardOps.setStepDone(planned, cell.steps.single().id, true)
        assertTrue(b.bills.all { it.paid != null }, "the cell done: its bills are paid")
    }

    @Test
    fun aLateBillIsAnOpenLoopUntilPaid() {
        val late = Bills.add(board(), Bills.parse("SAAQ 120.00 due 13", today.minusDays(5)))
        val cost = Balance.of(late, today).lines.getValue("Organized").cost
        assertEquals(0.5, cost)
        val paid = Bills.markPaid(late, late.bills.single().id, today)
        assertEquals(0.0, Balance.of(paid, today).lines.getValue("Organized").cost)
    }

    @Test
    fun aBillFromAnEmailGoesToToPayNotTheTable() {
        val u = Update("u1", "outlook", "Hydro-Québec", "Votre facture", "Facture à payer", actions = listOf(UpdateAction("bill", title = "Hydro-Québec", amount = 142.37, date = "2026-10-23", category = "Home")))
        val b = assertNotNull(Updates.apply(board(), u, today))
        assertEquals(1, b.bills.size)
        assertTrue(b.tasks.isEmpty())
        assertTrue(Updates.describe(board(), u, today).single().startsWith("Adds to To pay: Hydro-Québec 142.37"))
    }

    @Test
    fun plannedCellsShowAsHope() {
        var b = board()
        b = BoardOps.add(b, BoardOps.NewTask("Dinner with my brother", serve = mapOf("Relations" to 3)), today).board
        b = BoardOps.moveStep(b, b.tasks.single().steps.single().id, today.plusDays(1))!!
        val w = Balance.of(b, today)
        assertEquals(0, w.pillar.getValue(Gbn.NOURISH))
        assertTrue(w.plannedPct.getValue(Gbn.NOURISH) > 0, "planned, not done: shown as hope")
        assertTrue(Balance.oneCell(w, "Health") > 0)
    }
}
