package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.cellsPerDay
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import java.time.LocalDate

/**
 * Moving a cell by hand (hold it, then drop it on an empty cell). Only open cells from today on move; a fixed day
 * (a meeting, a date set with someone) stays. A project's step keeps its order: between its steps before and after.
 */
object Moves {
    data class Drop(val ok: Boolean, val why: String = "")

    /** Why this cell can't move, or null when it can. */
    fun cantMove(task: Task, step: Step, today: LocalDate): String? = when {
        step.closed -> "Done or kept as a record: it stays on its day."
        step.date == null || LocalDate.parse(step.date).isBefore(today) -> "A past day stays as your record: open it and tap Again later."
        step.fixedDate != null || task.fixedDate != null -> "Its day is fixed (a meeting or a date set with someone): open it to change it."
        else -> null
    }

    /** Can the step go on [date]? */
    fun canDrop(board: Board, stepId: String, date: LocalDate, today: LocalDate): Drop {
        val (task, step) = BoardOps.findStep(board, stepId) ?: return Drop(false)
        if (date.isBefore(today)) return Drop(false, "a past day")
        if (!Areas.canPlan(board, task, date)) return Drop(false, "not a day for this area")
        if (task.isProject) {
            val open = task.steps.filter { !it.closed }
            val i = open.indexOfFirst { it.id == stepId }
            val before = open.take(i).mapNotNull { it.date }.maxOrNull()
            val after = open.drop(i + 1).mapNotNull { it.date }.minOrNull()
            if (before != null && date.toString() < before) return Drop(false, "before the step that comes first")
            if (after != null && date.toString() > after) return Drop(false, "after the step that comes next")
        }
        if (cantMove(task, step, today) != null) return Drop(false, "this cell can't move")
        return Drop(true)
    }

    /**
     * Puts the step on [date], in cell [slot] when it is free (else the first free one), pinned there. Null if the day is full.
     * Moved later counts as a push in the week's balance; moved earlier is being ahead, never a cost.
     */
    fun drop(board: Board, stepId: String, date: LocalDate, slot: Int, today: LocalDate): Board? {
        if (!canDrop(board, stepId, date, today).ok) return null
        val used = board.tasks.flatMap { it.steps }.filter { it.id != stepId && it.date == date.toString() }.mapNotNull { it.slot }.toSet()
        val n = board.cellsPerDay
        val s = slot.takeIf { it !in used && it in 0 until n } ?: (0 until n).firstOrNull { it !in used } ?: return null
        return BoardOps.mapStep(board, stepId) {
            val later = it.date != null && date.toString() > it.date
            it.copy(date = date.toString(), slot = s, pinned = true, notBefore = null, pushLog = if (later) it.pushLog + today.toString() else it.pushLog)
        }
    }
}
