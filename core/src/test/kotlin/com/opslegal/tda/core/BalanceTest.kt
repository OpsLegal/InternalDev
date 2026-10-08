package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Routine
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.Holidays
import com.opslegal.tda.core.plan.Pacing
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Routines
import com.opslegal.tda.core.plan.Tags
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BalanceTest {
    private val today = LocalDate.parse("2026-10-08")

    @Test
    fun quebecHolidaysAndTheChristmasBreak() {
        assertEquals(LocalDate.parse("2027-03-28"), Holidays.easter(2027))
        assertTrue(Holidays.isOff(LocalDate.parse("2026-10-12"), "QC")) // Thanksgiving
        assertTrue(Holidays.isOff(LocalDate.parse("2026-06-24"), "QC"))
        assertTrue(Holidays.isOff(LocalDate.parse("2026-12-28"), "QC"))
        assertFalse(Holidays.isOff(LocalDate.parse("2026-10-13"), "QC"))
        assertFalse(Holidays.isOff(LocalDate.parse("2026-10-12"), ""))
        assertTrue(Holidays.isOff(LocalDate.parse("2026-07-03"), "US")) // July 4 2026 is a Saturday: observed Friday
        assertTrue(Holidays.isOff(LocalDate.parse("2026-05-14"), "FR")) // Ascension
        assertTrue(Holidays.isOff(LocalDate.parse("2026-02-16"), "ON")) // Family Day
    }

    @Test
    fun aProjectIsPacedToItsDeadlineWithABuffer() {
        val deadline = today.plusDays(75)
        val steps = Pacing.fill(listOf("Gather credentials", "Draft the proposal", "Submit to ACT").map { Pacing.Filled(it) }, "Our client ACT must approve")
        assertTrue(steps.any { it.title == "Integrate the feedback" && it.waitDays == 5 && it.added })
        assertTrue(steps.any { it.title == "Final review" })
        val saved = Projects.save(Board(settings = com.opslegal.tda.core.model.PlannerSettings(holidays = "QC")), Project("OPS - ACT", deadline = deadline.toString()), null,
            steps.map { BoardOps.EditedStep(null, it.title, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added) }, today).board
        val dates = BoardOps.projectTask(saved, "OPS - ACT")!!.steps.map { LocalDate.parse(it.date!!) }
        assertTrue(dates.zipWithNext().all { (a, b) -> b.isAfter(a) }, "$dates")
        assertTrue(dates.last().isBefore(deadline.minusDays(2)), "$dates")
        assertTrue(dates.last().isAfter(today.plusDays(30)), "spread, not jammed: $dates")
        assertTrue(dates.none { Holidays.isOff(it, "QC") })
        // Bring forward: the steps that wait for no one come sooner.
        assertTrue(Flags.sooner(saved, "OPS - ACT", today) > 0)
        val sooner = Flags.bringForward(saved, "OPS - ACT", today)
        val end = Projects.end(sooner, "OPS - ACT").end!!
        assertTrue(end.isBefore(dates.last()), "$end")
    }

    @Test
    fun tagsTakeTheHighestWeightAndHandSetWeightsWin() {
        var b = Tags.toggle(Board(), "Lawyer")
        b = Tags.toggle(b, "Parent")
        fun w(n: String) = b.values.single { it.name == n }.weight
        assertEquals(3, w("Career")); assertEquals(3, w("Relations")); assertEquals(2, w("Learning"))
        b = Tags.apply(b.copy(adjust = mapOf("Career" to 1)))
        assertEquals(1, w("Career"))
        b = Tags.toggle(b.copy(adjust = emptyMap()), "Lawyer")
        assertTrue(b.values.none { it.name == "Learning" })
    }

    @Test
    fun routinesCountInTheWeekAndCrowdedMomentsAreFound() {
        val base = Tags.apply(Board())
        assertEquals(4, Routines.base(base)["Health"]) // no routine: average
        val r = (1..3).map { Routine("r$it", "R$it", listOf(1, 3), Moment.MORNING, mapOf("Health" to 2)) }
        val b = base.copy(routines = r)
        assertEquals(12, Routines.base(b)["Health"])
        assertEquals(listOf("1:MORNING", "3:MORNING"), Routines.crowded(b).map { it.key })
        assertEquals(listOf("3:MORNING"), Routines.crowded(b.copy(routineFine = listOf("1:MORNING"))).map { it.key })
        assertEquals(6 to Moment.MORNING, Routines.freeSlot(b))
    }

    @Test
    fun freeCellsTodayFlagWorkPlannedLater() {
        var b = Board()
        b = BoardOps.addTaskOn(b, BoardOps.NewTask("Call Jean"), today.plusDays(1), today).first
        val f = Flags.all(b, today)
        assertTrue(f.any { it.id.startsWith("now:") }, "$f")
        val id = f.first { it.id.startsWith("now:") }.id.removePrefix("now:")
        val moved = Flags.doToday(b, id, today)
        assertEquals(today.toString(), moved.tasks.single().steps.single().date)
        val snoozed = Flags.notNow(b, "now:$id", "Call Jean", "Bigger than it looks", today)
        assertTrue(Flags.all(snoozed, today).none { it.id == "now:$id" })
    }

    @Test
    fun pushReasonsReachTheWeeklyReview() {
        var b = BoardOps.addTaskOn(Board(), BoardOps.NewTask("Draft the lease"), today, today).first
        val id = b.tasks.single().steps.single().id
        b = BoardOps.pushStep(b, id, today, "Bigger than it looks: needs the client's numbers")
        val f = com.opslegal.tda.core.agent.WeekReview.facts(b, today.with(java.time.DayOfWeek.MONDAY), today)
        assertEquals(listOf("Draft the lease: Bigger than it looks: needs the client's numbers"), f.reasons)
        assertTrue(com.opslegal.tda.core.agent.WeekReview.prompt(b, f).contains("needs the client's numbers"))
    }

    @Test
    fun extendMakesTheCellLongerTheSameDayNextToIt() {
        var b = Board()
        b = BoardOps.addTaskOn(b, BoardOps.NewTask("Write the brief"), today, today).first
        val st = b.tasks.single().steps.single()
        val e = Projects.longer(b, st.id, today)!!
        assertEquals(today, e.day)
        val placed = Projects.placeNear(e.board, e.stepToPlace, e.day, st.slot)!!
        val cont = BoardOps.findStep(placed, e.stepToPlace)!!.second
        assertEquals(today.toString(), cont.date)
        assertEquals(st.slot!! + 1, cont.slot)
        // A full day: no free cell, so the day must make room (or the user splits it).
        var full = Board()
        repeat(5) { i -> full = BoardOps.addTaskOn(full, BoardOps.NewTask("Busy $i"), today, today).first }
        val f0 = full.tasks.first().steps.single()
        val e2 = Projects.longer(full, f0.id, today)!!
        assertEquals(null, Projects.placeNear(e2.board, e2.stepToPlace, today, f0.slot))
    }

    @Test
    fun oneListEachItemOnceAndDismissKeepsWhatMattersInTheProject() {
        val p = Project("Lease")
        val reply = com.opslegal.tda.core.model.Update("u1", "outlook", "Luc (client)", "Any news?", "Luc asks for news", project = "Lease", needsReply = true,
            actions = listOf(com.opslegal.tda.core.model.UpdateAction("add", title = "Send Luc the lease")))
        val fyi = com.opslegal.tda.core.model.Update("u2", "outlook", "Nadia", "FYI", "Nadia copied you", needsReply = true, cc = true)
        var b = Board(projects = listOf(p), updates = listOf(reply, fyi))
        // Replies off: the unanswered email still shows, once, with its task.
        assertEquals(listOf("u1", "u2"), com.opslegal.tda.core.plan.Updates.inbox(b).map { it.id }.sorted())
        b = com.opslegal.tda.core.plan.Updates.dismiss(b, "u2", "noted", "She now handles the file", "Lease", today)
        assertEquals(listOf("u1"), com.opslegal.tda.core.plan.Updates.inbox(b).map { it.id })
        assertTrue(b.projects.single().history.single().contains("She now handles the file"))
        b = com.opslegal.tda.core.plan.Updates.dismiss(b, "u1", "irrelevant", "", null, today)
        assertTrue(com.opslegal.tda.core.plan.Updates.inbox(b).isEmpty())
        assertEquals(1, b.projects.single().history.size)
    }

    @Test
    fun daysOffKeepWorkAwayAndTheAssistantKnowsThem() {
        val off = (0L..4L).map { today.plusDays(it).toString() }
        var b = Board(settings = com.opslegal.tda.core.model.PlannerSettings(holidays = "", daysOff = off))
        b = com.opslegal.tda.core.plan.Planner.plan(BoardOps.add(b, BoardOps.NewTask("Write the brief"), today).board, today).board
        val d = LocalDate.parse(b.tasks.single().steps.single().date!!)
        assertTrue(d.isAfter(today.plusDays(4)), "$d")
        assertEquals(listOf(today to today.plusDays(4)), Holidays.ranges(off))
        assertTrue(com.opslegal.tda.core.agent.AgentTools.describe(b, today, 7).contains("DAYS OFF"))
    }

    @Test
    fun aChangedPlanUpdatesTheCellInsteadOfAddingOne() {
        val now = LocalDate.now()
        var b = BoardOps.addTaskOn(Board(), BoardOps.NewTask("Apéro chez Béatrice et Arnaud"), now, now).first
        val id = b.tasks.single().steps.single().id
        val u = com.opslegal.tda.core.model.Update("u1", "whatsapp", "Béatrice", "On va plutôt au resto", "Restaurant at 19:00",
            actions = listOf(com.opslegal.tda.core.model.UpdateAction("add", title = "19:00 Restaurant avec Béatrice et Arnaud, Vieux-Port", date = now.toString())))
        b = com.opslegal.tda.core.plan.Updates.add(b, listOf(u))
        val a = b.updates.single().actions.single()
        assertEquals("change", a.type); assertEquals(id, a.step)
        b = com.opslegal.tda.core.plan.Updates.apply(b, b.updates.single(), now)!!
        assertEquals(1, b.tasks.size)
        assertEquals("19:00 Restaurant avec Béatrice et Arnaud, Vieux-Port", b.tasks.single().title)
        // A meeting moved from tomorrow to today: the same cell moves.
        var m = BoardOps.addTaskOn(Board(), BoardOps.NewTask("10:00 Meeting with CN Rail", kind = com.opslegal.tda.core.model.TaskKind.MEETING, fixedDate = now.plusDays(1).toString()), now.plusDays(1), now).first
        val ms = m.tasks.single().steps.single().id
        val mv = com.opslegal.tda.core.model.Update("u2", "outlook", "CN", "Can we do it today instead?", "Moved to today",
            actions = listOf(com.opslegal.tda.core.model.UpdateAction("change", step = ms, date = now.toString())))
        m = com.opslegal.tda.core.plan.Updates.apply(com.opslegal.tda.core.plan.Updates.add(m, listOf(mv)), mv, now)!!
        assertEquals(1, m.tasks.size)
        assertEquals(now.toString(), m.tasks.single().steps.single { it.outcome == null }.date)
    }

    @Test
    fun eachCategoryHasAScoreOutOf100() {
        val b = Tags.apply(Board())
        val s0 = Routines.scores(b, today)
        assertEquals(100, s0["nourish"]) // no routine: average, as expected
        assertEquals(0, s0["build"]) // nothing planned yet
    }

    @Test
    fun aTaskPushedTwiceIsFlaggedFixedAndTracked() {
        var b = BoardOps.addTaskOn(Board(settings = com.opslegal.tda.core.model.PlannerSettings(holidays = "")), BoardOps.NewTask("Call the bank about the loan"), today, today).first
        repeat(2) { val id = b.tasks.single().steps.first { s -> !s.closed }.id; b = BoardOps.pushStep(b, id, today); b = com.opslegal.tda.core.plan.Planner.plan(b, today).board }
        val p = com.opslegal.tda.core.plan.Habits.patterns(b, today).single()
        assertEquals("pushed", p.kind); assertEquals(2, p.count)
        val (fixed, h) = com.opslegal.tda.core.plan.Habits.apply(b, p.taskId, "big", "", today)!!
        val st = fixed.tasks.single { it.id == p.taskId }.steps.first { !it.closed }
        assertTrue(st.title.startsWith("First 30 min"))
        assertEquals(0, st.slot)
        assertTrue(com.opslegal.tda.core.plan.Habits.patterns(fixed, today).isEmpty(), "being worked on: not flagged again")
        val done = com.opslegal.tda.core.plan.Habits.track(BoardOps.setStepDone(fixed, st.id, true))
        assertEquals("done", done.habitFixes.single { it.id == h.id }.outcome)
    }

    @Test
    fun answeredCardsCloseByThemselves() {
        val alex = com.opslegal.tda.core.model.Update("a", "whatsapp", "Alex", "Park on Rachel street", "Alex suggests where to park", needsReply = true,
            chatId = "c1", at = "2026-10-07T18:00:00")
        val client = com.opslegal.tda.core.model.Update("m", "outlook", "Client", "Send the lease", "Send the lease", needsReply = true,
            mailId = "mail-1", at = "2026-10-07T09:00:00", actions = listOf(com.opslegal.tda.core.model.UpdateAction("add", title = "Send the lease")))
        val b = Board(updates = listOf(alex, client))
        // Alex: the user wrote in the chat the next morning (not about parking): settled. The client: no reply in that thread.
        val (s1, n1) = com.opslegal.tda.core.plan.Updates.settle(b, mapOf("c1" to "2026-10-08T08:10:00"), emptySet())
        assertEquals(1, n1); assertEquals(listOf("m"), com.opslegal.tda.core.plan.Updates.inbox(s1).map { it.id })
        // A message from the user before Alex's suggestion does not count.
        assertEquals(0, com.opslegal.tda.core.plan.Updates.settle(b, mapOf("c1" to "2026-10-07T17:00:00"), emptySet()).second)
        // The client's email answered in its own thread: closed.
        assertTrue(com.opslegal.tda.core.plan.Updates.inbox(com.opslegal.tda.core.plan.Updates.settle(s1, emptyMap(), setOf("mail-1")).first).isEmpty())
    }

    @Test
    fun eachAreaIsPlannedOnItsOwnDays() {
        // Thursday Oct 8. Work Mon–Fri; Buildings only on weekends; a Cairo office Sun–Thu.
        val areas = listOf(com.opslegal.tda.core.model.Area("work", "OPS LEGAL", listOf(1, 2, 3, 4, 5)),
            com.opslegal.tda.core.model.Area("bld", "Buildings", listOf(6, 7), work = false))
        var b = Board(settings = com.opslegal.tda.core.model.PlannerSettings(holidays = "", areas = areas))
        b = BoardOps.add(b, BoardOps.NewTask("Fix the roof leak"), today).board
        b = b.copy(tasks = b.tasks.map { it.copy(area = "bld") })
        b = BoardOps.add(b, BoardOps.NewTask("Draft the lease"), today).board
        b = com.opslegal.tda.core.plan.Planner.plan(b, today).board
        val roof = LocalDate.parse(b.tasks.first { it.title == "Fix the roof leak" }.steps.single().date!!)
        val lease = LocalDate.parse(b.tasks.first { it.title == "Draft the lease" }.steps.single().date!!)
        assertTrue(roof.dayOfWeek.value in 6..7, "$roof")
        assertTrue(lease.dayOfWeek.value in 1..5, "$lease")
        // The table now shows the weekend, and the assistant knows each area's days.
        assertTrue(com.opslegal.tda.core.plan.Planner.rows(b, today, 7).any { LocalDate.parse(it.date).dayOfWeek.value == 6 })
        assertTrue(com.opslegal.tda.core.agent.AgentTools.describe(b, today, 7).contains("Buildings Sat, Sun"))
        // A Cairo weekend: work moves to Sun–Thu, the Friday cell goes back to the planner.
        val cairo = b.copy(settings = b.settings.copy(areas = listOf(areas[0].copy(days = listOf(7, 1, 2, 3, 4)), areas[1])))
        val friday = cairo.copy(tasks = cairo.tasks.map { t -> if (t.title == "Draft the lease") t.copy(steps = t.steps.map { it.copy(date = "2026-10-09", slot = 0) }) else t })
        val (moved, n, kept) = com.opslegal.tda.core.plan.Areas.replan(friday, today)
        assertEquals(1, n); assertTrue(kept.isEmpty())
        assertTrue(LocalDate.parse(moved.tasks.first { it.title == "Draft the lease" }.steps.single().date!!).dayOfWeek.value in listOf(7, 1, 2, 3, 4))
        assertEquals("Sun–Thu", com.opslegal.tda.core.plan.Areas.daysText(listOf(7, 1, 2, 3, 4)))
        assertEquals("every day", com.opslegal.tda.core.plan.Areas.daysText((1..7).toList()))
    }

    @Test
    fun aMessageToTheAssistantComesFirstAndItRepliesAsItself() {
        val b = Board(persona = com.opslegal.tda.core.model.Persona("Jimmy", 2))
        val me = com.opslegal.tda.core.agent.Me
        assertTrue(me.forMe(b, "Jimmy, please remind the boss to pick up the cake"))
        assertTrue(me.forMe(b, "Hey jimmy! golf is Sunday"))
        assertFalse(me.forMe(b, "Jimmyson called"))
        assertFalse(me.forMe(Board(), "Assistant, please"))
        assertEquals("Please remind the boss", me.said(b, "Jimmy, please remind the boss"))
        assertEquals("Sarah", me.firstName("Sarah (wife)"))
        val u = com.opslegal.tda.core.model.Update("u1", "whatsapp", "Sarah (wife)", "Jimmy, the cake Friday after 4", "Pick up the cake", needsReply = true)
        val other = com.opslegal.tda.core.model.Update("u2", "outlook", "Bank", "Your statement", "Statement", needsReply = true)
        assertEquals("u1", com.opslegal.tda.core.plan.Updates.inbox(b.copy(updates = listOf(other, u))).first().id)
        assertTrue(com.opslegal.tda.core.agent.ReplyWriter.prompt(b, u, null, emptyList(), "", today).contains("Reply AS Jimmy"))
    }
}
