package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoLevelsTest {
    // A Monday.
    private val monday = LocalDate.parse("2026-09-21")

    private fun Board.add(spec: NewTask) = BoardOps.add(this, spec, monday).board

    @Test
    fun severalStepsWithoutAProjectBecomeAProject() {
        val b = Board().add(NewTask("Tax report", stepTitles = listOf("Gather slips", "Fill the forms")))
        assertEquals(listOf("Tax report"), b.projects.map { it.name })
        val t = b.tasks.single()
        assertTrue(t.isProject)
        assertEquals("Tax report · Gather slips", Planner.cellTitle(t, t.steps[0]))
    }

    @Test
    fun oldBoardsAreConvertedOnce() {
        val old = Board(
            projects = listOf(Project("Refinancing", Priority.HIGH)),
            tasks = listOf(
                Task("a", "Call the notary", "Ask for the list", project = "Refinancing", kind = TaskKind.MEETING, steps = listOf(Step("s1", "Call the notary"))),
                Task("b", "Send documents", project = "Refinancing", steps = listOf(Step("s2", "Send documents"))),
                Task("c", "Report", steps = listOf(Step("s3", "Draft"), Step("s4", "Final"))),
                Task("d", "Groceries", steps = listOf(Step("s5", "Groceries"))),
            ),
        )
        val b = BoardOps.migrateToTwoLevels(old, monday)
        assertEquals(2, b.version)
        val refinancing = BoardOps.projectTask(b, "Refinancing")!!
        assertEquals(listOf("Call the notary", "Send documents"), refinancing.steps.map { it.title })
        assertEquals(TaskKind.MEETING, refinancing.kindOf(refinancing.steps[0]))
        assertEquals("Ask for the list", refinancing.steps[0].description)
        assertTrue(BoardOps.projectTask(b, "Report") != null)
        assertTrue(b.tasks.first { it.title == "Groceries" }.let { !it.isProject })
        assertEquals(b, BoardOps.migrateToTwoLevels(b, monday))
    }

    @Test
    fun extendingAOneCellTaskMakesItAProject() {
        var b = Planner.plan(Board().add(NewTask("Car brakes", effort = Effort.LIGHT)), monday).board
        val step = b.tasks.single().steps.single()
        val more = Projects.extend(b, step.id, Projects.Extension.MORE_EFFORT, "", monday)!!
        assertTrue(more.becameProject)
        assertEquals(LocalDate.parse("2026-09-22"), more.day)
        b = BoardOps.placeStep(more.board, more.stepToPlace, more.day)!!
        val t = b.tasks.single()
        assertEquals(listOf("Car brakes", "Car brakes (cont.)"), t.steps.map { it.title })
        assertEquals(Effort.LIGHT, t.effortOf(t.steps[1]))

        // Related task: it takes this cell and the task moves on.
        val related = Projects.extend(b, t.steps[0].id, Projects.Extension.RELATED_TASK, "Buy brake pads", monday)!!
        val after = related.board.tasks.single()
        assertEquals("Buy brake pads", after.steps[0].title)
        assertEquals("2026-09-21", after.steps[0].date)
        assertNull(after.steps[1].date)
        assertEquals(t.steps[0].id, related.stepToPlace)
    }

    @Test
    fun aFullDayOffersItsLeastImportantMovableCell() {
        var b = Board()
        b = b.add(NewTask("Court filing", kind = TaskKind.DEADLINE))
        b = b.add(NewTask("Client call", priority = Priority.HIGH))
        repeat(3) { b = b.add(NewTask("Admin $it", priority = Priority.LOW)) }
        b = Planner.plan(b, monday).board
        assertTrue(Projects.isFull(b, monday))
        val (task, _) = assertNotNull(Projects.movableOn(b, monday, null, monday))
        assertTrue(task.title.startsWith("Admin"))
    }

    @Test
    fun projectsTakeTheImportanceOfWhatTheyUnlock() {
        var b = Board(projects = listOf(Project("Refinancing", Priority.CRITICAL), Project("Tax report", Priority.LOW, blocks = listOf("Refinancing"))))
        b = b.add(NewTask("Tax report", stepTitles = listOf("Slips", "Forms", "Send"), project = "Tax report"))
        val tax = BoardOps.projectTask(b, "Tax report")!!
        assertEquals(Priority.CRITICAL.weight, Projects.weight(b, tax))
        b = Planner.plan(b, monday).board
        val stats = Projects.stats(b, b.projects.first { it.name == "Tax report" })
        assertEquals(0, stats.percent)
        b = BoardOps.setStepDone(b, BoardOps.projectTask(b, "Tax report")!!.steps[0].id, true)
        assertEquals(33, Projects.stats(b, b.projects.first { it.name == "Tax report" }).percent)
    }

    @Test
    fun pushingAProjectStepKeepsTheOrderAndCancellingFreesTheCell() {
        var b = Planner.plan(Board().add(NewTask("Lease", stepTitles = listOf("Read", "Comment", "Sign"))), monday).board
        val steps = b.tasks.single().steps
        assertEquals(listOf("2026-09-21", "2026-09-22", "2026-09-23"), steps.map { it.date })
        b = Planner.plan(BoardOps.pushStep(b, steps[1].id, monday), monday).board
        val open = b.tasks.single().steps.filter { !it.closed }
        assertEquals(listOf("Read", "Comment", "Sign"), open.map { it.title })
        assertTrue(open[1].date!! < open[2].date!!)

        b = BoardOps.cancelStep(b, open[2].id, monday)
        assertNull(b.tasks.single().steps.first { it.id == open[2].id }.date)
    }
}
