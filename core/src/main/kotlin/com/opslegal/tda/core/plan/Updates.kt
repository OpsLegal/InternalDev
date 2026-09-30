package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.model.UpdateStatus
import java.time.LocalDate

/** Applying the changes proposed by an update. Nothing here runs without the user's Apply. */
object Updates {

    /** How many handled (applied or dismissed) updates are kept, for the record. */
    const val KEEP_HANDLED = 20

    /**
     * The board with [update]'s changes and the update marked applied, or null when it no longer applies
     * (a step it names is gone or already closed, the project was renamed...). The caller lets the planner run.
     */
    fun apply(board: Board, update: Update, today: LocalDate): Board? {
        var next = board
        for (action in update.actions) next = applyAction(next, action, today) ?: return null
        return setStatus(next, update.id, UpdateStatus.APPLIED)
    }

    fun setStatus(board: Board, id: String, status: UpdateStatus): Board {
        val updates = board.updates.map { if (it.id == id) it.copy(status = status) else it }
        val handled = updates.filter { it.status != UpdateStatus.NEW }
        val drop = handled.dropLast(KEEP_HANDLED).map { it.id }.toSet()
        return board.copy(updates = updates.filter { it.id !in drop })
    }

    /** Adds new proposals, skipping any that repeats one still waiting (same source, sender and text). */
    fun add(board: Board, fresh: List<Update>): Board {
        val waiting = board.updates.filter { it.status == UpdateStatus.NEW }
        val new = fresh.filter { f -> waiting.none { it.source == f.source && it.from == f.from && it.text == f.text } }
        return board.copy(updates = board.updates + new)
    }

    /** Updates waiting for the user, urgent first. */
    fun waiting(board: Board): List<Update> = board.updates.filter { it.status == UpdateStatus.NEW }.sortedByDescending { it.urgent }

    private fun applyAction(board: Board, a: UpdateAction, today: LocalDate): Board? {
        val date = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when (a.type) {
            "add" -> {
                if (a.title.isBlank()) return null
                val spec = BoardOps.NewTask(
                    title = a.title.trim(), description = a.description.trim(),
                    project = a.project.trim().let { BoardOps.findProject(board, it)?.name ?: it },
                    kind = a.kind ?: TaskKind.TASK,
                    fixedDate = date?.takeIf { a.kind == TaskKind.MEETING }?.toString(),
                )
                if (date != null && !date.isBefore(today)) BoardOps.addTaskOn(board, spec, date, today).first
                else BoardOps.add(board, spec, today).board
            }
            "done" -> {
                val (_, step) = findOpenStep(board, a) ?: return null
                BoardOps.setStepDone(board, step.id, true)
            }
            "move" -> {
                if (date == null) return null
                val (task, step) = findOpenStep(board, a) ?: return null
                var next = board
                if (task.kindOf(step) == TaskKind.MEETING) next = BoardOps.mapStep(next, step.id) { it.copy(fixedDate = date.toString()) }
                BoardOps.moveStep(next, step.id, date) ?: BoardOps.unschedule(next, step.id, date.toString())
            }
            "deadline" -> {
                val project = BoardOps.findProject(board, a.project) ?: return null
                BoardOps.saveProject(board, project.copy(deadline = date?.toString()), project.name)
            }
            else -> null
        }
    }

    /** The step an action names: by id, or by its title within the project (still to do). */
    private fun findOpenStep(board: Board, a: UpdateAction) =
        BoardOps.findStep(board, a.step)?.takeIf { !it.second.closed }
            ?: BoardOps.projectTask(board, a.project)?.let { t ->
                t.steps.firstOrNull { !it.closed && it.title.equals(a.step.trim(), ignoreCase = true) }?.let { t to it }
            }
}
