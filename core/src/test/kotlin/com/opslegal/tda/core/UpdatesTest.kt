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
            Incoming("n2", "outlook", "Jean", "Invitation: review, Fri 10:00", "2026-09-21T08:05", mailId = "AAMk1"))
        val reply = """{"updates":[{"item":"n1","summary":"Sophie waits for your view.","reply":true,"actions":[]},
            {"item":"n2","summary":"Jean invites you.","reply":true,"meeting":"Review, Fri 10:00-11:00","urgent":true,
             "actions":[{"type":"add","title":"Review with Jean","kind":"MEETING"}]}]}"""
        val found = UpdateCheck.parse(reply, items, "2026-09-21T09:00")
        assertEquals(2, found.size)
        assertEquals("AAMk1", found[1].mailId)
        var b = Updates.add(Board(), found)
        assertTrue(Updates.replies(b).isEmpty(), "No replies while the reply assistant is off")
        b = b.copy(replies = ReplySettings(on = true))
        assertEquals(listOf("Jean", "Sophie"), Updates.replies(b).map { it.from }, "Urgent first")
        assertEquals(listOf("Jean"), Updates.tasks(b).map { it.from })
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
        assertEquals(listOf("b"), Updates.replies(b).map { it.id })
        b = Updates.answeredElsewhere(b, setOf("room1"))
        assertTrue(Updates.replies(b).isEmpty())
    }
}
