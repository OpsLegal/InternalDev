package com.opslegal.tda.core

import com.opslegal.tda.core.agent.UpdateCheck
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.model.UpdateStatus
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Updates
import com.opslegal.tda.core.agent.MessageItem
import com.opslegal.tda.core.agent.ReplyWriter
import com.opslegal.tda.core.agent.Threads
import com.opslegal.tda.core.model.ReplySettings
import com.opslegal.tda.core.model.UpdateChecks
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private val monday = LocalDate.parse("2026-09-21")

    private fun refinancing(): Board =
        BoardOps.add(Board(), NewTask("Call the notary", project = "Refinancing", stepTitles = listOf("Call the notary", "Sign")), monday).board

    private fun update(vararg actions: UpdateAction) =
        Update("u1", "gmail", "Me Dubé", "The list is attached.", "Mark the call done.", actions = actions.toList())

    @Test
    fun applyMarksAStepDoneAndAddsAProjectStep() {
        var b = refinancing()
        val call = BoardOps.projectTask(b, "Refinancing")!!.steps.first()
        val u = update(UpdateAction("done", step = call.id), UpdateAction("add", project = "refinancing", title = "Gather the documents"))
        b = Updates.add(b, listOf(u))
        val next = assertNotNull(Updates.apply(b, u, monday))
        val steps = BoardOps.projectTask(next, "Refinancing")!!.steps
        assertTrue(steps.first { it.id == call.id }.done)
        assertEquals("Gather the documents", steps.last().title)
        assertEquals(1, next.tasks.size, "The new step joins the existing project (two levels)")
        assertEquals(UpdateStatus.APPLIED, next.updates.single().status)
    }

    @Test
    fun aStepFoundByTitleAndAMeetingMoveKeepsItsDay() {
        val b = BoardOps.add(Board(), NewTask("ACME", project = "ACME", stepTitles = listOf("Kick-off meeting"), kind = TaskKind.MEETING), monday).board
        val friday = monday.plusDays(4)
        val next = assertNotNull(Updates.apply(b, update(UpdateAction("move", project = "ACME", step = "kick-off meeting", date = friday.toString())), monday))
        val step = BoardOps.projectTask(next, "ACME")!!.steps.single()
        assertEquals(friday.toString(), step.date)
        assertEquals(friday.toString(), step.fixedDate)
    }

    @Test
    fun anUpdateThatNoLongerAppliesChangesNothing() {
        var b = refinancing()
        val call = BoardOps.projectTask(b, "Refinancing")!!.steps.first()
        b = BoardOps.setStepDone(b, call.id, true)
        assertNull(Updates.apply(b, update(UpdateAction("done", step = call.id)), monday))
        assertNull(Updates.apply(b, update(UpdateAction("deadline", project = "Unknown", date = "2026-10-01")), monday))
    }

    @Test
    fun deadlineChangesTheProject() {
        val b = Board(projects = listOf(Project("Tax report")))
        val next = assertNotNull(Updates.apply(b, update(UpdateAction("deadline", project = "Tax report", date = "2026-10-02")), monday))
        assertEquals("2026-10-02", next.projects.single().deadline)
    }

    @Test
    fun parseKeepsOnlyKnownItemsAndActions() {
        val items = listOf(Incoming("n1", "outlook", "Revenu Québec", "Your return is due soon.", "2026-09-21T08:00"))
        val reply = """Here: {"updates":[
            {"item":"n1","summary":"Set the Tax report deadline to Oct 2.","project":"Tax report","urgent":true,
             "actions":[{"type":"deadline","project":"Tax report","date":"2026-10-02"},{"type":"send_email"}]},
            {"item":"zz","summary":"Unknown item","actions":[{"type":"done","step":"x"}]},
            {"item":"n1","summary":"Nothing to do","actions":[]}
        ]}"""
        val updates = UpdateCheck.parse(reply, items, "2026-09-21T09:00")
        val u = updates.single()
        assertEquals("outlook", u.source)
        assertTrue(u.urgent)
        assertEquals(listOf("deadline"), u.actions.map { it.type })
        assertTrue(UpdateCheck.parse("no json", items, "x").isEmpty())
    }

    @Test
    fun theSameUpdateIsNotAddedTwiceAndOldHandledOnesArePruned() {
        val u = update(UpdateAction("deadline", project = "X", date = "2026-10-02"))
        var b = Updates.add(Board(), listOf(u))
        b = Updates.add(b, listOf(u.copy(id = "u2")))
        assertEquals(1, b.updates.size)
        repeat(Updates.KEEP_HANDLED + 5) { i -> b = Updates.setStatus(b.copy(updates = b.updates + u.copy(id = "h$i", text = "$i")), "h$i", UpdateStatus.DISMISSED) }
        assertEquals(Updates.KEEP_HANDLED, b.updates.count { it.status != UpdateStatus.NEW })
        assertEquals(1, Updates.waiting(b).size)
    }

    @Test
    fun aReplyOnlyItemIsKeptAndSortedIntoTheRepliesPile() {
        val items = listOf(Incoming("n1", "whatsapp", "Sophie", "Avez-vous lu mon bail ?", "2026-09-21T08:00"),
            Incoming("n2", "outlook", "Jean", "Can you send me the signed contract today?", "2026-09-21T08:05", mailId = "AAMk1"),
            Incoming("n3", "outlook", "Véronique", "CN/Ops Legal - Call — Réunion Microsoft Teams", "2026-09-21T08:06"))
        val reply = """{"updates":[{"item":"n1","summary":"Sophie waits for your view.","reply":true,"actions":[]},
            {"item":"n2","summary":"Jean needs the signed contract.","reply":true,"urgent":true,
             "actions":[{"type":"add","title":"Send the signed contract to Jean"}]},
            {"item":"n3","summary":"Invitation from Véronique.","reply":true,"actions":[{"type":"add","title":"14:00 Call CN","kind":"MEETING","date":"2026-09-23"}]}]}"""
        val found = UpdateCheck.parse(reply, items, "2026-09-21T09:00")
        assertEquals(3, found.size)
        assertEquals("AAMk1", found[1].mailId)
        assertFalse(found[2].needsReply, "An invitation is answered in the calendar, never as a reply")
        var b = Updates.add(Board(), found)
        assertTrue(Updates.replies(b).isEmpty(), "No replies while the reply assistant is off")
        b = b.copy(replies = ReplySettings(on = true))
        assertEquals(listOf("Jean", "Sophie"), Updates.replies(b).map { it.from }, "Urgent first")
        assertEquals(listOf("Véronique"), Updates.tasks(b).map { it.from }, "Jean's card shows once, in Replies, with its Apply")
        // Dismissing the change to the plan keeps the answer to prepare; drafting it removes it.
        b = Updates.setStatus(b, found[1].id, UpdateStatus.DISMISSED)
        assertEquals(2, Updates.replies(b).size)
        b = Updates.setReplied(b, found[1].id)
        assertEquals(listOf("Sophie"), Updates.replies(b).map { it.from })
        assertFalse(UpdateCheck.prompt(b, items, monday).contains("Never propose to send"))
    }

    @Test
    fun theBellTurnsRedAtReviewTimeUntilOpened() {
        val u = update(UpdateAction("deadline", project = "X", date = "2026-10-02"))
        var b = Updates.add(Board(checks = UpdateChecks(times = listOf("08:30", "12:30"))), listOf(u))
        val at = { t: String -> LocalDateTime.parse("2026-09-21T$t") }
        assertFalse(Updates.reviewDue(b, at("08:00")), "Before the first review time")
        assertTrue(Updates.reviewDue(b, at("08:31")))
        b = b.copy(checks = b.checks.copy(lastReview = "2026-09-21T09:00"))
        assertFalse(Updates.reviewDue(b, at("11:00")), "Opened after 08:30")
        assertTrue(Updates.reviewDue(b, at("12:31")), "Next review time")
        assertFalse(Updates.reviewDue(Board(checks = b.checks), at("12:31")), "Nothing waiting")
    }

    @Test
    fun theReplyPromptCarriesTheChoiceAndNeverSends() {
        val u = Update("u", "outlook", "Jean", "Invitation", "Jean invites you.", needsReply = true, meeting = "Review, Fri 10:00")
        val p = ReplyWriter.prompt(Board(), u, ReplyWriter.Choice.OTHER_TIME, listOf("Mon Sep 28, 10:00–11:00"), "", monday)
        assertTrue(p.contains("Mon Sep 28, 10:00–11:00"))
        assertTrue(p.contains("never send"))
        assertEquals("Hello Jean", ReplyWriter.clean("Subject: Re: review\n\"Hello Jean\""))
    }

    @Test
    fun aThreadStartsBeforeTheLastReplyAndEndsWithTheirMessages() {
        fun m(t: String, me: Boolean, text: String) = MessageItem("c", if (me) "Me" else "Sophie", text, "2026-09-2${t}", me)
        val msgs = listOf(m("0T09:00", true, "Hi"), m("0T10:00", false, "Old"), m("1T09:00", true, "Send me the lease"),
            m("2T08:00", false, "Here it is"), m("2T08:05", false, "Is clause 12 legal?"), m("3T07:00", false, "Ok merci"))
        val thread = Threads.sinceMyLastReply(msgs, before = 1)!!
        assertEquals(listOf("Old", "Send me the lease", "Here it is", "Is clause 12 legal?", "Ok merci"), thread.map { it.text })
        assertEquals("2026-09-22T08:00", Threads.waitingSince(thread))
        assertNull(Threads.sinceMyLastReply(msgs + m("3T08:00", true, "Je regarde")), "The user wrote last")
    }

    @Test
    fun oneCardPerChatAndAnsweringElsewhereClearsIt() {
        val first = Update("a", "whatsapp", "Sophie", "Bail ?", "Sophie waits.", needsReply = true, chatId = "room1")
        var b = Updates.add(Board(replies = ReplySettings(on = true)), listOf(first))
        b = Updates.add(b, listOf(first.copy(id = "b", text = "Ok merci")))
        assertEquals(listOf("Ok merci"), Updates.replies(b).map { it.text }, "One card, with the newest message")
        b = Updates.answeredElsewhere(b, setOf("room1"))
        assertTrue(Updates.replies(b).isEmpty())
    }

    @Test
    fun anAnswerAndATaskFromTheSameOriginAreOneCard() {
        val on = Board(replies = ReplySettings(on = true))
        // Same email, two proposals: one card with the answer and the task.
        val reply = Update("a", "outlook", "Jean", "Can you send the contract?", "Jean waits.", needsReply = true, mailId = "M1")
        val task = Update("b", "outlook", "Jean", "Can you send the contract?", "Send the contract.", mailId = "M1",
            actions = listOf(UpdateAction("add", title = "Send the contract to Jean")))
        var b = Updates.add(on, listOf(reply, task))
        assertEquals(1, b.updates.size)
        val card = b.updates.single()
        assertTrue(card.needsReply && card.actions.size == 1)
        assertEquals(listOf(card.id), Updates.replies(b).map { it.id })
        assertTrue(Updates.tasks(b).isEmpty())
        // The answer written: the change waits alone in Tasks.
        b = Updates.setReplied(b, card.id)
        assertEquals(listOf(card.id), Updates.tasks(b).map { it.id })
        // A notification with no id, same person, a reply waiting: the task joins it.
        val chat = Update("c", "sms", "Sophie (client)", "Tu peux revoir le bail ?", "Sophie waits.", needsReply = true)
        val work = Update("d", "sms", "Sophie", "Tu peux revoir le bail ?", "Review the lease.", actions = listOf(UpdateAction("add", title = "Review Sophie's lease")))
        b = Updates.add(Updates.add(on, listOf(chat)), listOf(work))
        assertEquals(1, b.updates.size)
        // The same proposal again adds nothing.
        assertEquals(b, Updates.add(b, listOf(work.copy(id = "e"))))
    }

    @Test
    fun theCardNoteSetsDayProjectReplyAndIsKeptAboutThePerson() {
        val sat = LocalDate.of(2026, 10, 10)
        assertEquals(LocalDate.of(2026, 10, 16), Updates.dayInNote("put it Friday, Refinancing", sat))
        assertEquals(LocalDate.of(2026, 10, 15), Updates.dayInNote("mettre ça jeudi", sat))
        assertEquals(LocalDate.of(2026, 10, 11), Updates.dayInNote("demain", sat))
        assertEquals(LocalDate.of(2026, 10, 12), Updates.dayInNote("next week please", sat))
        assertEquals(null, Updates.dayInNote("Julie already handled it", sat))
        var b = Board(projects = listOf(Project("Refinancing"), Project("Tax report")),
            updates = listOf(Update("u1", "outlook", "Me Dubé, notary → Julie (you in CC)", "Send the deed", "Deed to send", needsReply = true)))
        assertEquals("Refinancing", Updates.projectInNote(b, "put it Friday, refinancing"))
        assertEquals(null, Updates.projectInNote(b, "later"))
        b = Updates.setComment(b, "u1", " say yes for Thursday ")
        assertTrue(ReplyWriter.prompt(b, b.updates.single(), null, emptyList(), "", sat).contains("say yes for Thursday"))
        b = Updates.dismiss(b, "u1", "done", "Julie already handled it", null, sat)
        assertTrue(Updates.aboutPerson(b, "Me Dubé, notary (office)").single().contains("Julie already handled it"))
        assertTrue(com.opslegal.tda.core.agent.AgentTools.describe(b, sat, 7).contains("PEOPLE"))
    }

    @Test
    fun searchIsInstantAndForgivingAndFindSortsByDay() {
        val names = listOf("OPS - ACT - WORKSHOPS", "OPS - ACT - Outside Counsel", "Bénéva", "RENOVATIONS MTL", "RENOVATIONS QC")
        assertEquals(listOf("OPS - ACT - WORKSHOPS", "OPS - ACT - Outside Counsel"), com.opslegal.tda.core.plan.Search.ranked("ops act", names))
        assertEquals(listOf("Bénéva"), com.opslegal.tda.core.plan.Search.ranked("BENEVA", names))
        assertEquals(listOf("RENOVATIONS MTL"), com.opslegal.tda.core.plan.Search.ranked("reno mtl", names))
        assertEquals(listOf("RENOVATIONS QC"), com.opslegal.tda.core.plan.Search.ranked("qc", names))
        val sat = LocalDate.of(2026, 10, 10)
        assertEquals(LocalDate.of(2026, 10, 20), com.opslegal.tda.core.plan.Search.dayIn("oct 20", sat))
        assertEquals(LocalDate.of(2026, 10, 16), com.opslegal.tda.core.plan.Search.dayIn("friday", sat))
        var b = Board()
        b = BoardOps.addTaskOn(b, NewTask("Call the notary"), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 1)).first
        b = BoardOps.addTaskOn(b, NewTask("Notary papers"), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1)).first
        val ev = com.opslegal.tda.core.agent.CalendarEvent("Notary meeting", "2026-10-12T10:00", "2026-10-12T11:00")
        val found = com.opslegal.tda.core.plan.Search.find(b, listOf(ev), "notary", sat)
        assertEquals(listOf("2026-10-12", "2026-10-14", "2026-10-05"), found.map { it.date })
        assertEquals(listOf("Coming up", "Coming up", "Past"), found.map { it.group })
    }

    @Test
    fun aSuggestionDealtWithLeavesUntilItsSituationChanges() {
        val today = LocalDate.of(2026, 10, 12)
        var b = BoardOps.addTaskOn(Board(), NewTask("Qualinet review"), today.plusDays(2), today).first
        b = b.copy(tasks = b.tasks.map { it.copy(pushes = 2) })
        val id = com.opslegal.tda.core.plan.Flags.all(b, today).single { it.id.startsWith("push:") }.id
        b = com.opslegal.tda.core.plan.Flags.settle(b, id, today)
        assertTrue(com.opslegal.tda.core.plan.Flags.all(b, today).none { it.id == id })
        // Pushed once more: it comes back.
        b = b.copy(tasks = b.tasks.map { it.copy(pushes = 3) })
        assertTrue(com.opslegal.tda.core.plan.Flags.all(b, today).any { it.id == id })
    }

    @Test
    fun snoozeHidesASuggestionForTheChosenTime() {
        val mon = LocalDate.of(2026, 10, 12)
        var b = BoardOps.addTaskOn(Board(), NewTask("Qualinet review"), mon.plusDays(2), mon).first
        b = b.copy(tasks = b.tasks.map { it.copy(pushes = 2) })
        val id = com.opslegal.tda.core.plan.Flags.all(b, mon).single { it.id.startsWith("push:") }.id
        val (_, until) = com.opslegal.tda.core.plan.Flags.snoozes(mon).first { it.first == "48 h" }
        b = com.opslegal.tda.core.plan.Flags.notNow(b, id, "Qualinet review", "snoozed 48 h", mon, until)
        assertTrue(com.opslegal.tda.core.plan.Flags.all(b, mon.plusDays(1)).none { it.id == id })
        assertTrue(com.opslegal.tda.core.plan.Flags.all(b, mon.plusDays(2)).any { it.id == id })
        // Next week: back on Monday.
        assertEquals(LocalDate.of(2026, 10, 18), com.opslegal.tda.core.plan.Flags.snoozes(mon).last().second)
    }

    @Test
    fun aHeldCellMovesOnlyWhereItKeepsItsOrder() {
        val mon = LocalDate.of(2026, 10, 12)
        var b = BoardOps.addTaskOn(Board(), NewTask("Call Sophie"), mon, mon).first
        val call = b.tasks.single().steps.single().id
        b = com.opslegal.tda.core.plan.Moves.drop(b, call, mon.plusDays(2), 3, mon)!!
        assertEquals("2026-10-14" to 3, b.tasks.single().steps.single().let { it.date to it.slot })
        assertEquals(false, com.opslegal.tda.core.plan.Moves.canDrop(b, call, mon.minusDays(1), mon).ok)
        // A project's step stays between its steps before and after.
        b = BoardOps.addTaskOn(b, NewTask("Draft", project = "Lease"), mon, mon).first
        b = BoardOps.addTaskOn(b, NewTask("Review", project = "Lease"), mon.plusDays(3), mon).first
        val draft = b.tasks.first { it.isProject }.steps.first { it.title == "Draft" || it.date == "2026-10-12" }.id
        assertEquals(false, com.opslegal.tda.core.plan.Moves.canDrop(b, draft, mon.plusDays(4), mon).ok)
        assertEquals(true, com.opslegal.tda.core.plan.Moves.canDrop(b, draft, mon.plusDays(1), mon).ok)
    }

    @Test
    fun aCardWhoseTaskIsAlreadyInTheTableCloses() {
        var b = Board(updates = listOf(Update("u1", "outlook", "Luc", "Send the lease", "Luc asks for the lease", actions = listOf(UpdateAction("add", title = "Send Luc the lease")))))
        b = BoardOps.addTaskOn(b, NewTask("send luc the lease"), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12)).first
        assertEquals(UpdateStatus.APPLIED, Updates.settleDone(b).updates.single().status)
    }
}
