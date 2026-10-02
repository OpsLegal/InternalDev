package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.model.UpdateStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    fun setStatus(board: Board, id: String, status: UpdateStatus): Board =
        prune(board.copy(updates = board.updates.map { if (it.id == id) it.copy(status = status) else it }))

    /** The reply was saved as a draft or not needed: it leaves the Replies pile. */
    fun setReplied(board: Board, id: String): Board =
        prune(board.copy(updates = board.updates.map { if (it.id == id) it.copy(replied = true) else it }))

    private fun handled(u: Update) = (u.status != UpdateStatus.NEW || u.actions.isEmpty()) && (!u.needsReply || u.replied)

    private fun prune(board: Board): Board {
        val drop = board.updates.filter(::handled).dropLast(KEEP_HANDLED).map { it.id }.toSet()
        return board.copy(updates = board.updates.filter { it.id !in drop })
    }

    /** Adds new proposals, skipping any that repeats one still waiting (same source, sender and text). */
    fun add(board: Board, fresh: List<Update>): Board {
        val waiting = board.updates.filter { it.status == UpdateStatus.NEW }
        val new = fresh.filter { f -> waiting.none { it.source == f.source && it.from == f.from && it.text == f.text } }
        return board.copy(updates = board.updates + new)
    }

    /** Changes to the table waiting for Apply or Dismiss, urgent first. */
    fun tasks(board: Board): List<Update> =
        board.updates.filter { it.status == UpdateStatus.NEW && it.actions.isNotEmpty() }.sortedByDescending { it.urgent }

    /** Answers waiting to be prepared, urgent first; none while the reply assistant is off. */
    fun replies(board: Board): List<Update> {
        val r = board.replies
        if (!r.on) return emptyList()
        return board.updates.filter { u ->
            u.needsReply && !u.replied && when {
                u.meeting.isNotBlank() -> r.meetings && r.email
                u.source in EMAIL -> r.email
                else -> r.messages
            }
        }.sortedByDescending { it.urgent }
    }

    /** Everything behind the bell, each update once, urgent first. */
    fun waiting(board: Board): List<Update> = (tasks(board) + replies(board)).distinctBy { it.id }.sortedByDescending { it.urgent }

    /** Sources answered by email (a draft in Outlook); every other source is a chat (Beeper). */
    val EMAIL = setOf("outlook", "gmail", "email")

    /**
     * Review time: one of the user's check times has passed today since they last opened the bell, and
     * something waits. The bell turns red then, even when nothing is urgent.
     */
    fun reviewDue(board: Board, now: LocalDateTime): Boolean {
        if (tasks(board).isEmpty() && replies(board).isEmpty()) return false
        val slot = board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
            .filter { !it.isAfter(now.toLocalTime()) }.maxOrNull() ?: return false
        val last = board.checks.lastReview?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return last == null || last.isBefore(now.toLocalDate().atTime(slot))
    }

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
