package com.opslegal.tda.core

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.agent.ReplyWriter
import com.opslegal.tda.core.agent.UpdateCheck
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.ReplySettings
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.model.UpdateStatus
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.CalendarCells
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarAndPutAwayTest {
    private val monday = LocalDate.parse("2026-10-05")

    @Test
    fun anEventTakesCellsByLengthAndMovesWorkFromAFullDay() {
        var b = Board()
        repeat(5) { i -> b = BoardOps.addTaskOn(b, BoardOps.NewTask("Work $i"), monday, monday).first }
        val hearing = CalendarEvent("Court hearing", "2026-10-05T09:00", "2026-10-05T12:30")
        assertEquals(2, CalendarCells.cells(hearing))
        assertEquals(1, CalendarCells.cells(CalendarEvent("Call", "2026-10-05T14:00", "2026-10-05T16:00")))
        assertEquals(5, CalendarCells.cells(CalendarEvent("Holiday", "2026-10-05", "2026-10-06", allDay = true)))
        b = CalendarCells.add(b, hearing, monday)
        val day = b.tasks.flatMap { t -> t.steps.map { t to it } }.filter { it.second.date == monday.toString() }
        assertEquals(5, day.size)
        assertEquals(2, day.count { (t, s) -> t.kindOf(s) == TaskKind.MEETING })
        assertEquals(listOf("09:00 Court hearing", "Court hearing (cont.)"), day.filter { (t, s) -> t.kindOf(s) == TaskKind.MEETING }.map { it.second.title })
        assertTrue(b.tasks.flatMap { it.steps }.filter { it.title.startsWith("Work") }.all { it.date != null }, "Moved work is placed later")
        assertTrue(CalendarCells.shown(b, listOf(hearing), monday.toString()).isEmpty())
        val lunch = CalendarEvent("Lunch", "2026-10-05T12:00", "2026-10-05T13:00")
        b = CalendarCells.leaveOut(b, lunch, monday)
        assertTrue(CalendarCells.isLeftOut(b, lunch))
    }

    @Test
    fun putAwayClosesTheCardButTeachesNothing() {
        val u = Update("u1", "outlook", "Me Dubé → Julie", "Send the deed.", "Julie must send the deed.", cc = true, needsReply = true,
            actions = listOf(UpdateAction("add", title = "Check the deed")))
        var b = Updates.add(Board(replies = ReplySettings(on = true)), listOf(u, u.copy(id = "u2", from = "Promo", text = "Sale!")))
        b = Updates.putAway(b, "u1", done = true, pile = "tasks")
        assertTrue(Updates.tasks(b).none { it.id == "u1" } && Updates.replies(b).none { it.id == "u1" }, "Done closes both piles")
        b = Updates.putAway(b, "u2", done = false, pile = "replies")
        assertTrue(b.learned.isEmpty(), "Not this time teaches nothing")
        assertTrue(Updates.tasks(b).any { it.id == "u2" }, "Not this time for a reply keeps the change to the plan")
        val prompt = UpdateCheck.prompt(b, listOf(Incoming("n", "outlook", "X", "Y", "2026-10-05T08:00", cc = true)), monday)
        assertTrue(prompt.contains("proposed again"))
        assertTrue(prompt.contains("Already handled") && prompt.contains("(user only in CC)"))
        // How the user writes is kept (the last few), as style only.
        var s = Board()
        repeat(7) { i -> s = Updates.rememberReply(s, "Bonjour, c'est noté $i. Merci!") }
        assertEquals(Updates.STYLE_EXAMPLES, s.replyStyle.size)
        assertTrue(ReplyWriter.prompt(s, u, null, emptyList(), "", monday).contains("HOW THE USER WRITES"))
        assertEquals(UpdateStatus.DISMISSED, b.updates.first { it.id == "u1" }.status)
    }

    @Test
    fun dueComesBackFromTheCheck() {
        val items = listOf(Incoming("n1", "whatsapp", "Sophie", "Before Friday please", "2026-10-05T08:00"))
        val found = UpdateCheck.parse("""{"updates":[{"item":"n1","summary":"Review the lease.","reply":true,"due":"2026-10-09","actions":[]}]}""", items, "x")
        assertEquals("2026-10-09", found.single().due)
    }

    @Test
    fun aDayIsReviewedOnlyWhenEachOfItsEventsHasAChoice() {
        val lunch = CalendarEvent("Lunch", "2026-10-05T12:00", "2026-10-05T13:00")
        val dentist = CalendarEvent("Dentist", "2026-10-05T09:00", "2026-10-05T10:00")
        var b = Board()
        assertFalse(CalendarCells.reviewed(b, listOf(lunch, dentist), "2026-10-05"))
        b = CalendarCells.leaveOut(b, lunch, monday)
        b = CalendarCells.add(b, dentist, monday)
        assertTrue(CalendarCells.reviewed(b, listOf(lunch, dentist), "2026-10-05"))
        assertTrue(CalendarCells.reviewed(b, listOf(lunch), "2026-10-06"), "No events: nothing to review")
    }

    @Test
    fun theCheckSeesTheCalendarAndWhatIsWaitingAndIgnoresThanks() {
        val waiting = Update("w", "outlook", "Jean", "Invitation", "Jean invites you to the ACME review.", actions = listOf(UpdateAction("add", title = "ACME review")))
        val b = Updates.add(Board(replies = ReplySettings(on = true)), listOf(waiting))
        val p = UpdateCheck.prompt(b, listOf(Incoming("n", "whatsapp", "Sophie", "Merci !", "2026-10-05T08:00")), monday,
            listOf(CalendarEvent("ACME review", "2026-10-06T10:00", "2026-10-06T11:00")))
        assertTrue(p.contains("ACME review") && p.contains("THE USER'S CALENDAR"))
        assertTrue(p.contains("ALREADY WAITING") && p.contains("Jean invites you"))
        assertTrue(p.contains("Never for thanks"))
    }

    @Test
    fun aMeetingFromAnEmailTakesTheCalendarDateAndIsNeverAddedTwice() {
        val event = CalendarEvent("CN/Ops Legal - Call to discuss GaLexy and data", "2026-10-07T14:00", "2026-10-07T15:00")
        // The AI put it on the wrong day: the calendar wins.
        val u = Update("v", "outlook", "Véronique", "Invitation", "Call with CN.",
            actions = listOf(UpdateAction("add", title = "Appel CN/Ops Legal · GaLexy et données", kind = TaskKind.MEETING, date = "2026-10-05")))
        var b = Updates.add(Board(), listOf(u))
        assertTrue(Updates.describe(b, u, monday, listOf(event)).single().contains("Wed 7"))
        b = Updates.apply(b, u, monday, listOf(event))!!
        val meetings = b.tasks.flatMap { t -> t.steps.filter { t.kindOf(it) == TaskKind.MEETING } }
        assertEquals(listOf("2026-10-07"), meetings.map { it.date })
        // Reviewing the calendar event afterwards links to it: still one cell.
        b = CalendarCells.add(b, event, monday)
        assertEquals(1, b.tasks.flatMap { t -> t.steps.filter { t.kindOf(it) == TaskKind.MEETING } }.size)
        // A meeting without a date and no calendar match is not placed on a random day.
        val noDate = u.copy(id = "n", actions = listOf(UpdateAction("add", title = "Lunch Marc", kind = TaskKind.MEETING)))
        assertEquals(null, Updates.apply(Board(), noDate, monday, emptyList()))
        assertFalse(CalendarCells.similar("Hirfaty weekly call", "MFounders Weekly"))
    }
}
