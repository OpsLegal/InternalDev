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

/** The front door's rules: who an email speaks to, forwarded mailboxes, flagged cards, next actions in progress, after calls. */
class FrontDoorTest {
    private val today = java.time.LocalDate.parse("2026-11-09")
    private val now = java.time.LocalDateTime.parse("2026-11-09T10:00:00")

    @Test
    fun theGreetingSaysWhoTheEmailIsFor() {
        val fd = com.opslegal.tda.core.plan.FrontDoor
        assertEquals("me", fd.addressedTo("Hi Mohamed, can you confirm the room?", "Mohamed", false))
        assertEquals("Sarah", fd.addressedTo("Hi Sarah, send the NDA by Thursday.", "Mohamed", false))
        assertEquals("all", fd.addressedTo("Bonjour à tous, voici l'ordre du jour", "Mohamed", false))
        assertEquals("Julie", fd.addressedTo("Julie, please send the deed.", "Mohamed", true))
        assertEquals("", fd.addressedTo("Reminder: your tax return is due.", "Mohamed", true))
        assertEquals("", fd.addressedTo("Hi Sarah", "", false))
    }

    @Test
    fun flaggedEmailsBecomeCardsOnceAndNextActionsStayInProgressUntilDone() {
        val fd = com.opslegal.tda.core.plan.FrontDoor
        val inc = com.opslegal.tda.core.model.Incoming("i1", "outlook", "Hélène Roy", "Mortgage renewal — Il me manque vos relevés de paie. Merci.", "2026-11-09T09:00", mailId = "m1", flagged = true)
        var b = fd.flaggedCards(Board(projects = listOf(Project("Refinancing"))), listOf(inc), now.toString())
        b = fd.flaggedCards(b, listOf(inc), now.toString())
        assertEquals(1, b.updates.size)
        val u = b.updates.single()
        assertTrue(u.flagged); assertEquals("Mortgage renewal", u.title)
        assertEquals(1, com.opslegal.tda.core.plan.Updates.inbox(b, today).size)
        b = fd.next(b, u.id, "Send the pay stubs to Hélène", "task", "Refinancing", null, "", today)
        assertTrue(com.opslegal.tda.core.plan.Updates.inbox(b, today).isEmpty())
        assertEquals(1, fd.inProgress(b).size)
        b = fd.closeProgress(b, u.id, markDone = true, today = today)
        assertTrue(fd.inProgress(b).isEmpty())
        assertTrue(b.tasks.flatMap { it.steps }.any { it.title == "Send the pay stubs to Hélène" && it.done })
    }

    @Test
    fun parkedCardsComeBackAndCallsAskForNextActions() {
        val fd = com.opslegal.tda.core.plan.FrontDoor
        var b = Board(updates = listOf(Update("u1", "sms", "Fares", "Thursday or Friday?", "Drinks", actions = listOf(UpdateAction("add", title = "Drinks with Fares")))))
        b = fd.park(b, "u1", com.opslegal.tda.core.model.Park(today.toString(), "12:30"))
        assertTrue(fd.parked(b.updates.single(), now))
        assertTrue(!fd.parked(b.updates.single(), now.withHour(13)))
        b = fd.callEnded(b, "Fares", 18, "2026-11-09T09:40")
        b = fd.callEnded(b, "Fares", 18, "2026-11-09T09:41")
        assertEquals(1, b.afterCalls.size)
        b = fd.afterCall(b, b.afterCalls.single().id, listOf("Drinks with Fares next week" to "", "Send the documents" to "Refinancing"), today)
        assertTrue(b.afterCalls.isEmpty())
        assertEquals("Drinks with Fares next week", b.quick.single().title)
        assertTrue(b.projects.any { it.name == "Refinancing" } || b.tasks.any { it.project == "Refinancing" })
    }
}

/** Pause a project: every planned cell leaves the table (missed ones too), done ones stay; it comes back on its day. */
class PauseTest {
    @Test
    fun pausingClearsTheTableAndComesBackOnItsDay() {
        val today = java.time.LocalDate.parse("2026-11-09")
        var b = Board(projects = listOf(Project("Refinancing")))
        b = com.opslegal.tda.core.plan.BoardOps.addTask(b, com.opslegal.tda.core.plan.BoardOps.NewTask("Refinancing", project = "Refinancing", stepTitles = listOf("Call the notary", "Send the documents", "Sign")), today).first
        b = com.opslegal.tda.core.plan.Planner.plan(b, today).board
        val steps = b.tasks.flatMap { it.steps }
        b = com.opslegal.tda.core.plan.BoardOps.mapStep(b, steps[0].id) { it.copy(done = true, date = "2026-11-06", slot = 0) }
        b = com.opslegal.tda.core.plan.BoardOps.mapStep(b, steps[1].id) { it.copy(outcome = com.opslegal.tda.core.model.Outcome.MISSED, date = "2026-11-06", slot = 1) }
        val pause = com.opslegal.tda.core.model.Pause("Waiting on someone", "Bank", today.toString(), "2026-11-12")
        b = com.opslegal.tda.core.plan.Projects.park(b, "Refinancing", today, pause)
        val left = b.tasks.flatMap { it.steps }.filter { it.date != null && !it.done }
        assertTrue(left.isEmpty(), "planned and missed cells leave the table")
        assertTrue(b.tasks.flatMap { it.steps }.any { it.done && it.date == "2026-11-06" }, "done stays as the record")
        assertEquals("Waiting on someone", b.projects.single().pause?.why)
        assertTrue(com.opslegal.tda.core.plan.Projects.autoResume(b, today.plusDays(1)).second.isEmpty())
        val (back, names) = com.opslegal.tda.core.plan.Projects.autoResume(b, java.time.LocalDate.parse("2026-11-12"))
        assertEquals(listOf("Refinancing"), names)
        assertTrue(back.tasks.flatMap { it.steps }.none { it.outcome == com.opslegal.tda.core.model.Outcome.PARKED })
        assertEquals(null, back.projects.single().pause)
    }
}

/** My things: upkeep becomes a quick thing a week before, once; documents and expenses stay with the thing. */
class AssetsTest {
    @Test
    fun upkeepRemindsAWeekBeforeAndMovesOn() {
        val today = java.time.LocalDate.parse("2026-11-11")
        val car = com.opslegal.tda.core.model.Asset("a1", "car", "Honda CR-V 2019", upkeep = listOf(
            com.opslegal.tda.core.model.Upkeep("u1", "Oil change", 6, "2026-11-15"), com.opslegal.tda.core.model.Upkeep("u2", "Winter tires", 12, "2026-11-25")))
        var b = Board(assets = listOf(car))
        b = com.opslegal.tda.core.plan.Assets.upkeepDue(b, today)
        b = com.opslegal.tda.core.plan.Assets.upkeepDue(b, today)
        assertEquals(1, b.quick.size)
        assertEquals("🔧 Oil change · Honda CR-V 2019", b.quick.single().title)
        assertEquals("2026-11-15", b.quick.single().due)
        assertEquals("a1", b.quick.single().asset)
        assertEquals("2027-05-15", b.assets.single().upkeep.first().next)
        assertEquals("a1", com.opslegal.tda.core.plan.Assets.guess(b, "Intact Insurance auto policy renewal")?.id)
        b = com.opslegal.tda.core.plan.Assets.addExpense(b, "a1", com.opslegal.tda.core.model.Expense("e1", 89.95, vendor = "Canadian Tire", date = "2026-11-01"))
        val row = com.opslegal.tda.core.plan.Paperwork.all(b).single()
        assertEquals("a1", row.asset)
        b = com.opslegal.tda.core.plan.Paperwork.sort(b, "e1", "home", null)
        assertEquals("home", b.assets.single().expenses.single().area)
    }
}

/** Things: kinds, status, timeline items, upkeep as a task, making it now; a project's notifications follow its pause and delete. */
class ThingsTest {
    private val today = java.time.LocalDate.parse("2026-11-12")

    @Test
    fun thingsHaveKindsItemsAndUpkeepThatBecomesTasks() {
        val car = com.opslegal.tda.core.model.Asset("a1", "", "Honda", cat = "ve", sub = "Car", fields = mapOf("Insurance renews" to "2026-12-01"),
            upkeep = listOf(com.opslegal.tda.core.model.Upkeep("u1", "Tires", 6, "2026-11-15", becomes = "task")))
        val old = com.opslegal.tda.core.model.Asset("a2", "watch", "Omega")
        var b = Board(assets = listOf(car, old))
        assertEquals("⌚", com.opslegal.tda.core.plan.Things.icon(old))
        assertEquals("co", com.opslegal.tda.core.plan.Things.cat(old).key)
        val items = com.opslegal.tda.core.plan.Things.items(b, car, today, today.plusMonths(3))
        assertTrue(items.any { it.kind == "date" && it.date == "2026-12-01" })
        assertEquals("warn", com.opslegal.tda.core.plan.Things.status(b, car, today))
        assertEquals(listOf("a1"), com.opslegal.tda.core.plan.Things.search(b, "insurance").map { it.id })
        b = com.opslegal.tda.core.plan.Assets.upkeepDue(b, today)
        assertTrue(b.tasks.any { it.asset == "a1" && it.title.contains("Tires") && it.steps.first().date == "2026-11-15" })
        b = com.opslegal.tda.core.plan.Assets.makeNow(b, "a1", "u1", today)
        assertEquals("2027-05-12", b.assets.first().upkeep.first().next)
    }

    @Test
    fun aProjectsNotificationsFollowItsPauseAndDelete() {
        var b = Board(projects = listOf(Project("Refinancing")), updates = listOf(Update("u1", "outlook", "Bank", "Offer v3", "New offer", project = "Refinancing")))
        b = com.opslegal.tda.core.plan.BoardOps.addTask(b, com.opslegal.tda.core.plan.BoardOps.NewTask("Refinancing", project = "Refinancing", stepTitles = listOf("Call", "Sign")), today).first
        b = com.opslegal.tda.core.plan.Projects.park(b, "Refinancing", today)
        assertEquals("Refinancing", b.updates.single().pausedWith)
        assertTrue(com.opslegal.tda.core.plan.Updates.inbox(b, today).isEmpty())
        b = com.opslegal.tda.core.plan.Projects.resume(b, "Refinancing", today)
        assertEquals(null, b.updates.single().park)
        b = com.opslegal.tda.core.plan.Projects.delete(b, "Refinancing", )
        assertEquals("irrelevant", b.updates.single().handledAs)
        assertTrue(b.projects.isEmpty())
    }
}
