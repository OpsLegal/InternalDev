package com.opslegal.tda.core

import com.opslegal.tda.core.agent.FirstLook
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.core.plan.Payments
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What was dealt with stays dealt with: a bill paid by Interac this morning, a sales sender, a subject already done. */
class AlreadyHandledTest {
    private val today = LocalDate.parse("2026-10-16")
    private val found = FirstLook.Found(
        projects = listOf(FirstLook.P("Website redesign", "3 emails", null, "", listOf("Call back"), sender = "PixelPro Agency")),
        tasks = listOf(FirstLook.T("Send the lease to Luc", "Luc", "", null, sender = "Luc Gagnon")),
        bills = listOf(FirstLook.B("Plomberie Roy", 480.0, today.plusDays(5), "Home", "", "email", sender = "Plomberie Roy")),
        read = 50, skipped = 40,
    )

    @Test
    fun aBillPaidThisMorningByInteracIsNotProposed() {
        val paid = Payments.add(Board(), Payments.read("com.google.android.gm", "INTERAC e-Transfer", "Your e-Transfer of $480.00 to Jean Roy was deposited.", LocalDateTime.parse("2026-10-16T08:10:00"))!!)
        val f = FirstLook.clean(paid, found, today)
        assertTrue(f.bills.isEmpty(), "same amount, sent this morning")
        assertEquals(1, f.tasks.size)
    }

    @Test
    fun ignoredSendersAndDealtSubjectsNeverComeBack() {
        var b = FirstLook.handled(Board(), "Website redesign", "PixelPro Agency")
        b = FirstLook.handled(b, "Send the lease to Luc", null)
        val f = FirstLook.clean(b, found, today)
        assertTrue(f.projects.isEmpty()); assertTrue(f.tasks.isEmpty()); assertEquals(1, f.bills.size)
        assertEquals(42, f.skipped)
    }

    @Test
    fun aPaidBillBecomesAnExpenseInItsAreaAndProject() {
        var b = Bills.add(Board(), Bills.parse("Hydro 142.37 due 28", today))
        b = Bills.markPaid(b, b.bills.single().id, today, area = "home", project = "Duplex Rosemont")
        val e = Paperwork.all(b).single()
        assertEquals(142.37, e.e.amount); assertEquals("home", e.e.area); assertEquals("Duplex Rosemont", e.where)
        assertTrue(Bills.alreadyPaid(b, "Hydro", 142.37, today))
    }
}
