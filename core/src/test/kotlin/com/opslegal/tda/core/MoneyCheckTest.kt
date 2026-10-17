package com.opslegal.tda.core

import com.opslegal.tda.core.agent.MoneyCheck
import com.opslegal.tda.core.model.Area
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.Paperwork
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two money checks: missed invoices and receipts found once, never twice; expenses without an area sorted with a sure guess. */
class MoneyCheckTest {
    private val today = LocalDate.parse("2026-10-17")
    private val areas = listOf(Area("work", "Work", listOf(1, 2, 3, 4, 5)), Area("re", "Real estate", listOf(6, 7), work = false, words = listOf("duplex", "rénovation")))
    private val board = Board(settings = PlannerSettings(areas = areas), projects = listOf(Project("Duplex Laval", area = "re")),
        expenses = listOf(Expense("e1", 96.53, vendor = "Vidéotron", date = "2026-10-01")))
    private val items = listOf(
        Incoming("i0", "outlook", "Rona", "Votre reçu — Total 212,40 $ payé par Visa", "2026-09-20T10:00"),
        Incoming("i1", "outlook", "Vidéotron", "Votre facture de 96,53 $", "2026-10-01T09:00"),
        Incoming("i2", "outlook", "Newsletter", "Our autumn news", "2026-10-02T09:00"),
    )

    @Test
    fun onlyMoneyGoesToTheAssistantAndWhatTheAppHoldsIsLeftOut() {
        assertEquals(listOf("i0", "i1"), MoneyCheck.relevant(items).map { it.id })
        val reply = """{"found":[{"item":0,"vendor":"Rona","amount":212.40,"date":"2026-09-20","paid":true,"category":"Home","note":"Lumber","area":"re","project":"Duplex Laval"},
            {"item":1,"vendor":"Vidéotron","amount":96.53,"date":"2026-10-01","paid":false,"due":"2026-10-20","category":"Phone & internet","note":"","area":null,"project":null}]}"""
        val found = MoneyCheck.parse(reply, board, items, today)
        assertEquals(listOf("Rona"), found.map { it.vendor }, "Vidéotron is already an expense")
        val b = MoneyCheck.add(board, found.single(), "re", "Duplex Laval", paid = true)
        assertEquals("Duplex Laval", Paperwork.all(b).first { it.e.vendor == "Rona" }.where)
        assertTrue(MoneyCheck.parse(reply, b, items, today).isEmpty(), "never found twice")
    }

    @Test
    fun expensesWithoutAnAreaAreListedWithASureGuessOnlyWhenClear() {
        val b = board.copy(expenses = board.expenses + Expense("e2", 40.0, vendor = "Quincaillerie", note = "vis duplex", date = "2026-10-10"))
        val list = MoneyCheck.unsorted(b)
        assertEquals(2, list.size)
        assertEquals("re", list.first { it.id == "e2" }.area, "the area's word 'duplex'")
        val guessed = MoneyCheck.parseSort("""{"sort":[{"id":"e1","area":"work","project":null},{"id":"e2","area":"work"}]}""", b, list)
        assertEquals("work", guessed.first { it.id == "e1" }.area)
        assertEquals("re", guessed.first { it.id == "e2" }.area, "a sure place is not overwritten")
        assertTrue(Bills.alreadyPaid(b, "Vidéotron", 96.53, today))
    }
}
