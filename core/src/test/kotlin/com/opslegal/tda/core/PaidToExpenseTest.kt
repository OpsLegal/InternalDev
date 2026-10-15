package com.opslegal.tda.core

import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.Paperwork
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A paid bill is always an expense, once; it is sorted on its own only when that is certain; and expenses are found by a few words. */
class PaidToExpenseTest {
    private val today = LocalDate.parse("2026-10-15")

    @Test
    fun aBillPaidInAnOlderVersionBecomesAnExpenseOnce() {
        val b = Board(bills = listOf(Bill("r1", "Rachel Boisjoli", 120.0, "2026-10-15", paid = "2026-10-15")))
        val once = Bills.backfill(b)
        assertEquals(1, Paperwork.all(once).size)
        assertEquals(once, Bills.backfill(once), "never twice")
        assertTrue(Paperwork.unsorted(Paperwork.all(once).single()), "nothing sure: left for the user to sort")
    }

    @Test
    fun sureOnlyWhenTheVendorAlwaysWentToTheSamePlace() {
        val e = Expense("e1", 96.53, vendor = "Vidéotron", date = "2026-09-20", area = "home")
        val board = Board(projects = listOf(Project("Duplex Laval", expenses = listOf(e))))
        assertEquals("home" to "Duplex Laval", Bills.sureWhere(board, "Videotron"))
        val mixed = board.copy(expenses = listOf(e.copy(id = "e2", area = "personal")))
        assertNull(Bills.sureWhere(mixed, "Vidéotron"), "two places before: ask")
    }

    @Test
    fun paidGoesStraightToItsSurePlace() {
        val board = Board(projects = listOf(Project("Rachel Boisjoli sessions")), bills = listOf(Bill("r1", "Rachel Boisjoli", null, "2026-10-15")))
        val paid = Bills.markPaid(board, "r1", today, amount = 120.0)
        val row = Paperwork.all(paid).single()
        assertEquals("Rachel Boisjoli sessions", row.where); assertEquals(120.0, row.e.amount)
        assertEquals(row.e.id, paid.bills.single().expense)
    }

    @Test
    fun sortLaterAndSearchByAFewWords() {
        val b = Bills.backfill(Board(projects = listOf(Project("Mia école")), bills = listOf(Bill("r1", "Rachel Boisjoli", null, "2026-10-15", paid = "2026-10-15", category = "Health"))))
        val id = Paperwork.all(b).single().e.id
        val sorted = Paperwork.sort(b, id, "personal", "Mia école", 120.0)
        val r = Paperwork.all(sorted).single()
        assertEquals("Mia école", r.where); assertEquals("personal", r.e.area); assertEquals(120.0, r.e.amount)
        assertEquals(1, Paperwork.search(sorted, "rachel ecole", { "Personal" }).size)
        assertEquals(1, Paperwork.search(sorted, "120", { "Personal" }).size)
        assertTrue(Paperwork.search(sorted, "hydro", { "Personal" }).isEmpty())
    }

    @Test
    fun aScannedReceiptGoesStraightToItsAreaWithItsPhoto() {
        val b = Bills.addPaid(Board(projects = listOf(Project("Duplex Laval"))), "Canadian Tire #117", 62.05, "2026-07-07", "Home", "Paint, screws · Receipt",
            "realestate", "Duplex Laval", photo = "content://receipt.jpg", taxes = 8.08)
        val r = Paperwork.all(b).single()
        assertEquals("Duplex Laval", r.where); assertEquals("realestate", r.e.area); assertEquals("content://receipt.jpg", r.e.photo)
        assertTrue(Bills.alreadyPaid(b, "Canadian Tire", 62.05, LocalDate.parse("2026-07-20")), "never proposed again as a bill")
        assertTrue(b.bills.isEmpty(), "a receipt is not a bill to pay")
    }
}
