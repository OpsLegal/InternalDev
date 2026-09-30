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
import java.time.LocalDate
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
}
