package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.DefaultRules
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.DayLabel
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Rescheduler
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    // A Monday.
    private val monday = LocalDate.parse("2026-09-21")

    private fun board() = Board(rules = DefaultRules.all)

    private fun Board.add(spec: NewTask): Board = BoardOps.addTask(this, spec, monday).first

    @Test
    fun dayLabels() {
        assertEquals("F12", DayLabel.of(LocalDate.parse("2026-06-12")))
        assertEquals("Th3", DayLabel.of(LocalDate.parse("2026-09-03")))
        assertEquals("M21", DayLabel.of(monday))
        assertEquals("L21", DayLabel.of(monday, "fr"))
    }

    @Test
    fun neverMoreThanFivePerDay() {
        var b = board()
        repeat(12) { b = b.add(NewTask("Task $it")) }
        val planned = Planner.plan(b, monday).board
        val perDay = planned.tasks.flatMap { it.steps }.groupBy { it.date }
        assertTrue(perDay.values.all { it.size <= SLOTS_PER_DAY })
        assertEquals(5, perDay.getValue("2026-09-21").size)
        assertEquals(5, perDay.getValue("2026-09-22").size)
        assertEquals(2, perDay.getValue("2026-09-23").size)
        // Columns are unique within a day.
        assertTrue(perDay.values.all { day -> day.map { it.slot }.toSet().size == day.size })
    }

    @Test
    fun stepsOfOneTaskGoOnDifferentDaysAndSkipWeekends() {
        val b = board().add(NewTask("Tax report", stepTitles = listOf("Gather", "Fill", "Review", "File", "Pay", "Archive")))
        val steps = Planner.plan(b, monday).board.tasks.single().steps
        assertEquals(
            listOf("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-28"),
            steps.map { it.date },
        )
    }

    @Test
    fun waitingTimeBetweenSteps() {
        val b = board().add(NewTask("Loan", stepTitles = listOf("Ask bank", "Follow up"), minDaysBetweenSteps = 3))
        val steps = Planner.plan(b, monday).board.tasks.single().steps
        assertEquals(listOf("2026-09-21", "2026-09-24"), steps.map { it.date })
    }

    @Test
    fun blockerInheritsPriorityOfWhatItBlocks() {
        var b = board().add(NewTask("Refinance buildings", priority = Priority.CRITICAL))
        val refi = b.tasks.single().id
        b = b.add(NewTask("File tax report", priority = Priority.LOW, blocks = listOf(refi)))
        b = b.add(NewTask("Tidy garage", priority = Priority.HIGH))
        val weights = Planner.effectiveWeights(b)
        val tax = b.tasks.first { it.title == "File tax report" }
        assertEquals(Priority.CRITICAL.weight, weights[tax.id])

        // The tax report is placed before the garage although its own priority is LOW.
        val order = Planner.plan(b, monday).board.tasks.associate { it.title to it.steps.single().slot }
        assertTrue(order.getValue("File tax report")!! < order.getValue("Tidy garage")!!)
    }

    @Test
    fun rolloverMovesUnfinishedCellsToToday() {
        var b = board().add(NewTask("Call notary"))
        b = Planner.plan(b, monday).board
        val next = Planner.dailyRefresh(b, monday.plusDays(1)).board
        assertEquals("2026-09-22", next.tasks.single().steps.single().date)
    }

    @Test
    fun doneCellsStayInThePast() {
        var b = Planner.plan(board().add(NewTask("Call notary")), monday).board
        b = BoardOps.setStepDone(b, b.tasks.single().steps.single().id, true)
        val next = Planner.dailyRefresh(b, monday.plusDays(1)).board
        assertEquals("2026-09-21", next.tasks.single().steps.single().date)
        assertTrue(Planner.rows(next, monday, 1).single().allDone)
    }

    @Test
    fun fixedDateMeeting() {
        val b = board().add(NewTask("Meeting with ACME", fixedDate = "2026-09-24"))
        assertEquals("2026-09-24", Planner.plan(b, monday).board.tasks.single().steps.single().date)
    }

    @Test
    fun urgentTaskGetsRankedOptions() {
        var b = board()
        // Fill Monday to Wednesday with 15 open-ended low-priority tasks.
        repeat(15) { b = b.add(NewTask("Filler $it", priority = Priority.LOW)) }
        b = b.add(NewTask("Client report", priority = Priority.HIGH, deadline = "2026-10-30"))
        b = Planner.plan(b, monday).board

        val (withUrgent, urgent) = BoardOps.addTask(
            b, NewTask("Bank documents", priority = Priority.CRITICAL, deadline = "2026-09-23", stepTitles = listOf("Collect", "Send")), monday,
        )
        val options = Rescheduler.options(withUrgent, urgent.id, monday)
        assertTrue(options.size >= 2)
        val best = options.first()
        assertTrue(best.lateTasks.isEmpty(), "best option keeps everything on time: $best")
        assertTrue(best.moves.none { it.title == "Client report" }, "does not move the higher priority task")
        val urgentSteps = best.board.tasks.first { it.id == urgent.id }.steps
        assertTrue(urgentSteps.all { it.date!! <= "2026-09-22" })
        assertEquals("accept-late", options.last().id)
    }

    @Test
    fun rulesReorder() {
        val b = BoardOps.moveRule(board(), "default-3", -1)
        assertEquals(listOf("default-1", "default-3", "default-2"), b.rules.sortedBy { it.order }.take(3).map { it.id })
    }

    @Test
    fun pushTodayLeavesGreyRecordAndMovesTheWork() {
        var b = Planner.plan(board().add(NewTask("Call notary")), monday).board
        val stepId = b.tasks.single().steps.single().id
        b = Planner.plan(BoardOps.pushStep(b, stepId, monday), monday).board
        val steps = b.tasks.single().steps
        assertEquals(com.opslegal.tda.core.model.Outcome.PUSHED, steps[0].outcome)
        assertEquals("2026-09-21", steps[0].date)
        assertEquals("2026-09-22", steps[1].date)
        // Grey cells settle the day: done + grey = a full yellow line.
        var withDone = board().add(NewTask("Garage")).add(NewTask("Bank"))
        withDone = Planner.plan(withDone, monday).board
        withDone = BoardOps.setStepDone(withDone, withDone.tasks[0].steps[0].id, true)
        withDone = BoardOps.cancelStep(withDone, withDone.tasks[1].steps[0].id, monday)
        assertTrue(Planner.rows(withDone, monday, 1).single().allDone)
        // Rollover never revives a grey cell.
        assertEquals("2026-09-21", Planner.dailyRefresh(withDone, monday.plusDays(1)).board.tasks[1].steps[0].date)
    }

    @Test
    fun pushingAFutureCellFreesItAndGoesLater() {
        var b = board().add(NewTask("Report", stepTitles = listOf("Draft", "Final")))
        b = Planner.plan(b, monday).board
        val final = b.tasks.single().steps[1]
        assertEquals("2026-09-22", final.date)
        b = Planner.plan(BoardOps.pushStep(b, final.id, monday), monday).board
        assertEquals("2026-09-23", b.tasks.single().steps[1].date)
        assertEquals(2, b.tasks.single().steps.size)
    }

    @Test
    fun cancelTaskKeepsDoneCells() {
        var b = Planner.plan(board().add(NewTask("Loan", stepTitles = listOf("Ask", "Sign"))), monday).board
        val (ask, sign) = b.tasks.single().steps
        b = BoardOps.setStepDone(b, ask.id, true)
        b = BoardOps.cancelTask(b, b.tasks.single().id, monday)
        val steps = b.tasks.single().steps
        assertTrue(steps[0].done)
        assertEquals(com.opslegal.tda.core.model.Outcome.CANCELLED, steps[1].outcome)
        assertEquals(null, steps[1].date, "future cell is freed")
        assertTrue(b.tasks.single().isDone)
        assertEquals(sign.id, steps[1].id)
    }

    @Test
    fun addOnADayUsesFreeThenGreyCells() {
        var b = board()
        repeat(5) { b = b.add(NewTask("T$it")) }
        b = Planner.plan(b, monday).board
        val spec = NewTask("Dentist", description = "Crown check, bring the insurance card")
        val (_, _, placedFull) = BoardOps.addTaskOn(b, spec, monday, monday)
        assertTrue(!placedFull, "Monday is full")
        b = BoardOps.cancelStep(b, b.tasks[0].steps[0].id, monday)
        val (after, task, placed) = BoardOps.addTaskOn(b, spec, monday, monday)
        assertTrue(placed)
        assertEquals("2026-09-21", task.steps.single().date)
        assertEquals("Crown check, bring the insurance card", task.description)
        assertEquals(5, Planner.rows(after, monday, 1).single().filled)
    }
}
