package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.EditedStep
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeadlineTest {
    // A Wednesday.
    private val today = LocalDate.parse("2026-09-30")
    private fun steps(vararg titles: String) = titles.map { EditedStep(null, it) }
    private fun dates(b: Board, name: String) = BoardOps.projectTask(b, name)!!.steps.map { it.date }

    @Test
    fun aCloseDeadlinePutsSeveralStepsOnTheSameDay() {
        val saved = Projects.save(Board(), Project("Report", Priority.HIGH, today.plusDays(1).toString()), null,
            steps("Reread", "Outline", "Findings", "Roadmap"), today)
        assertEquals(List(4) { today.toString() }, dates(saved.board, "Report"))
        assertTrue(saved.moved.isEmpty())
    }

    @Test
    fun lessUrgentWorkMovesLaterToMakeRoomBeforeTheDeadline() {
        var b = Board()
        repeat(5) { i -> b = BoardOps.addTaskOn(b, NewTask("Errand $i", priority = Priority.LOW), today, today).first }
        b = b.copy(tasks = b.tasks.map { t -> t.copy(steps = t.steps.map { it.copy(pinned = false) }) })
        b = Planner.plan(b, today).board
        val saved = Projects.save(b, Project("Report", Priority.CRITICAL, today.plusDays(1).toString()), null, steps("Draft", "Send"), today)
        assertEquals(listOf(today.toString(), today.toString()), dates(saved.board, "Report"))
        assertEquals(2, saved.moved.size)
        // The moved errands still have a cell, later.
        assertTrue(saved.board.tasks.filter { !it.isProject }.all { t -> t.steps.all { it.date != null } })
    }

    @Test
    fun aDayChosenInTheFormIsKept() {
        val friday = today.plusDays(2)
        val saved = Projects.save(Board(), Project("Book"), null, listOf(EditedStep(null, "Outline"), EditedStep(null, "Chapter 1", friday.toString())), today)
        assertEquals(friday.toString(), dates(saved.board, "Book")[1])
    }

    @Test
    fun whenTheDayBeforeIsFullTheDeadlineDayIsUsedToo() {
        val saved = Projects.save(Board(), Project("Report", Priority.CRITICAL, today.plusDays(1).toString()), null,
            steps("1", "2", "3", "4", "5", "6", "7"), today)
        val d = dates(saved.board, "Report")
        assertEquals(5, d.count { it == today.toString() })
        assertEquals(2, d.count { it == today.plusDays(1).toString() })
    }
}
