package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.FoundPayment
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.Calls
import com.opslegal.tda.core.plan.Found
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.core.plan.Receipts
import com.opslegal.tda.core.plan.Rules
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Missed calls, bills found in notifications and paper receipts: by rule, no AI. */
class CaptureTest {
    private val today = LocalDate.parse("2026-11-02")
    private val now = LocalDateTime.parse("2026-11-02T10:15:00")

    @Test
    fun missedCallsAreReadFromTheDialerAndMergedByPerson() {
        val c = Calls.read("Missed call", "Sam Tremblay", now)!!
        assertEquals("Sam Tremblay", c.who)
        assertNull(Calls.read("Sam", "See you at 5", now))
        assertEquals(3, Calls.read("3 missed calls", "Mom", now)!!.count)
        assertEquals("Fares", Calls.read("Fares", "Appel manqué", now)!!.who)
        assertEquals("Sam", Calls.read("Missed call from Sam", "", now)!!.who)
        var b = Calls.add(Board(), c)
        b = Calls.add(b, Calls.read("Missed call", "Sam Tremblay", now.plusMinutes(20))!!)
        assertEquals(1, b.missed.size)
        assertEquals(2, b.missed.single().count)
        b = Calls.toToday(b, b.missed.single().id, today)
        assertTrue(b.missed.isEmpty())
        assertEquals("📞 Call back Sam Tremblay", b.quick.single().title)
        assertEquals(today.toString(), b.quick.single().date)
        assertEquals(3, b.quick.single().impact)
    }

    @Test
    fun aBillIsFoundByItsActionOrItsWordsUnlessTheSenderIsNotABill() {
        val inv = Update("1", "gmail", "Hydro-Québec", "", "Your invoice of 142,37 $ is ready", title = "Invoice ready")
        val chat = Update("2", "sms", "Sam", "", "See you at five", title = "Dinner")
        val act = Update("3", "gmail", "Bell", "", "Hello", actions = listOf(UpdateAction("bill", title = "Bell", amount = 88.5)))
        assertTrue(Found.isBill(Board(), inv))
        assertFalse(Found.isBill(Board(), chat))
        assertTrue(Found.isBill(Board(), act))
        assertEquals(142.37, Found.amount(inv))
        assertEquals("Bell", Found.vendor(act))
        val b = Rules.learn(Board(), "notbill", "Hydro-Québec", "1", "you said", today)
        assertFalse(Found.isBill(b, inv))
    }

    @Test
    fun aReceiptMatchesTheSameAmountWithinThreeDaysAndTheStoreBreaksATie() {
        val p = FoundPayment("p1", 23.45, "DOLLARAMA #123", "2026-11-01T15:00", app = "Scotia")
        val other = Expense("e1", 23.45, vendor = "Pharmaprix", date = "2026-11-02")
        val b = Board(paymentsFound = listOf(p), expenses = listOf(other), projects = listOf(Project("Boat")))
        // Two fit by amount and date: the store's first word decides.
        val m = Receipts.match(b, 23.44, "2026-11-02", "Dollarama")
        assertTrue(m is Receipts.Match.Payment)
        assertNull(Receipts.match(b, 23.45, "2026-11-20", "Dollarama"))
        assertNull(Receipts.match(b, 99.0, "2026-11-02", "Dollarama"))
        val linked = Receipts.link(b, m!!, "content://x/r.jpg", "home", "Boat", today)
        assertTrue(linked.paymentsFound.isEmpty())
        val row = Paperwork.all(linked).first { it.e.photo.isNotBlank() }
        assertEquals("Boat", row.where)
        assertEquals("Boat", Receipts.project(linked, "Dollarama Montréal"))
    }
}
