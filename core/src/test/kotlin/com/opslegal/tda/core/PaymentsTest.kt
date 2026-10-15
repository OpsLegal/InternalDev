package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.core.plan.Payments
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Payments the phone already announces become expenses to sort: wallet, bank app, Interac; never money received. */
class PaymentsTest {
    private val at = LocalDateTime.parse("2026-10-15T12:30:00")

    @Test
    fun walletBankAndInteracAreRead() {
        val w = Payments.read("com.google.android.apps.walletnfcrel", "Tim Hortons", "$4.85 with Visa •••• 1234", at)!!
        assertEquals(4.85, w.amount); assertEquals("Tim Hortons", w.merchant); assertEquals("card", w.kind)
        val s = Payments.read("com.scotiabank.banking", "Scotia", "Achat de 125,40 $ chez COSTCO WHOLESALE", at)!!
        assertEquals(125.40, s.amount); assertEquals("Costco Wholesale", s.merchant)
        val i = Payments.read("com.google.android.gm", "INTERAC e-Transfer", "Your e-Transfer of $1,250.00 to Marie Roy was deposited.", at)!!
        assertEquals(1250.0, i.amount); assertEquals("transfer", i.kind); assertEquals("Marie Roy", i.merchant)
    }

    @Test
    fun moneyInRefundsAndOtherAppsAreNot() {
        assertNull(Payments.read("com.google.android.gm", "INTERAC e-Transfer", "Marie sent you $50.00", at))
        assertNull(Payments.read("com.scotiabank.banking", "Scotia", "Refund of $20.00 from Amazon", at))
        assertNull(Payments.read("com.scotiabank.banking", "Scotia", "Your available balance is $1,200.00", at))
        assertNull(Payments.read("com.whatsapp", "Luc", "I paid $20 at the bar", at))
        assertNull(Payments.read("com.google.android.gm", "Newsletter", "Save $20.00 today", at))
    }

    @Test
    fun sortedOnceIntoTheReport() {
        val p = Payments.read("com.scotiabank.banking", "Scotia", "Purchase of $62.10 at Bistro Le Mile", at)!!
        var b = Payments.add(Board(), p)
        b = Payments.add(b, p.copy(id = "again"))
        assertEquals(1, b.paymentsFound.size, "the same payment twice is kept once")
        assertEquals("Meal", Payments.guessCategory(p.merchant))
        b = Payments.keep(b, b.paymentsFound.single().id, "Meal", "ACME contract", billable = true)
        assertTrue(b.paymentsFound.isEmpty())
        val e = Paperwork.all(b).single()
        assertEquals(62.10, e.e.amount); assertEquals("ACME contract", e.where); assertTrue(e.e.billable)
    }
}
