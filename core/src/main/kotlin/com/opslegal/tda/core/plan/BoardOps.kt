package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import java.time.LocalDate
import java.util.UUID

/** Edits of the board used by both the UI and the assistant. All functions are pure. */
object BoardOps {

    fun newId(): String = UUID.randomUUID().toString().take(8)

    data class NewTask(
        val title: String,
        val description: String = "",
        val project: String = "",
        val priority: Priority = Priority.NORMAL,
        val deadline: String? = null,
        val fixedDate: String? = null,
        val stepTitles: List<String> = emptyList(),
        val blocks: List<String> = emptyList(),
        val impactNote: String = "",
        val minDaysBetweenSteps: Int = 1,
    )

    /** Adds the task with unscheduled steps. Call [Planner.plan] afterwards to place it. */
    fun addTask(board: Board, spec: NewTask, today: LocalDate): Pair<Board, Task> {
        spec.deadline?.let(LocalDate::parse)
        spec.fixedDate?.let(LocalDate::parse)
        val titles = spec.stepTitles.ifEmpty { listOf(spec.title) }
        val task = Task(
            id = newId(),
            title = spec.title.trim(),
            description = spec.description.trim(),
            project = spec.project.trim(),
            priority = spec.priority,
            deadline = spec.deadline,
            fixedDate = spec.fixedDate,
            blocks = spec.blocks.filter { id -> board.tasks.any { it.id == id } },
            impactNote = spec.impactNote,
            minDaysBetweenSteps = spec.minDaysBetweenSteps.coerceAtLeast(1),
            steps = titles.map { Step(id = newId(), title = it.trim()) },
            createdAt = today.toString(),
        )
        return board.copy(tasks = board.tasks + task) to task
    }

    fun updateTask(board: Board, taskId: String, change: (Task) -> Task): Board =
        board.copy(tasks = board.tasks.map { if (it.id == taskId) change(it) else it })

    fun deleteTask(board: Board, taskId: String): Board = board.copy(
        tasks = board.tasks.filter { it.id != taskId }
            .map { it.copy(blocks = it.blocks - taskId) },
    )

    fun setStepDone(board: Board, stepId: String, done: Boolean): Board =
        mapStep(board, stepId) { it.copy(done = done, outcome = null) }

    /** A tap in the widget: grey cells reopen, others flip between done and to do. */
    fun toggleStep(board: Board, stepId: String): Board = mapStep(board, stepId) {
        if (it.outcome != null) it.copy(outcome = null) else it.copy(done = !it.done)
    }

    /** Back to "to do" (undoes done, pushed or cancelled). A pushed step's replacement stays. */
    fun reopenStep(board: Board, stepId: String): Board = mapStep(board, stepId) { it.copy(done = false, outcome = null) }

    /**
     * Pushes a cell to a later day. Today or earlier, the cell stays grey as a record and a new
     * step is added for the work; on a future day the cell is simply freed. Call [Planner.plan]
     * afterwards to place the new step.
     */
    fun pushStep(board: Board, stepId: String, today: LocalDate): Board {
        val (task, step) = findStep(board, stepId) ?: return board
        val from = step.date?.let(LocalDate::parse) ?: today
        val notBefore = maxOf(from, today).plusDays(1).toString()
        if (from.isAfter(today)) {
            return mapStep(board, stepId) { it.copy(date = null, slot = null, pinned = false, notBefore = notBefore) }
        }
        val replacement = Step(id = newId(), title = step.title, notBefore = notBefore)
        return updateTask(board, task.id) { t ->
            val steps = t.steps.flatMap { if (it.id == stepId) listOf(it.copy(outcome = Outcome.PUSHED, done = false), replacement) else listOf(it) }
            t.copy(steps = steps)
        }
    }

    /** Cancels one cell: grey on today or past days, removed from future days. */
    fun cancelStep(board: Board, stepId: String, today: LocalDate): Board = mapStep(board, stepId) { cancel(it, today) }

    /** Cancels everything left in a task. Done cells stay yellow. */
    fun cancelTask(board: Board, taskId: String, today: LocalDate): Board = updateTask(board, taskId) { t ->
        t.copy(steps = t.steps.map { if (it.closed) it else cancel(it, today) })
    }

    private fun cancel(step: Step, today: LocalDate): Step {
        val future = step.date?.let { LocalDate.parse(it).isAfter(today) } ?: true
        return if (future) step.copy(outcome = Outcome.CANCELLED, date = null, slot = null, done = false)
        else step.copy(outcome = Outcome.CANCELLED, done = false)
    }

    /** Renames one cell (the task keeps its own title). */
    fun renameStep(board: Board, stepId: String, title: String): Board =
        mapStep(board, stepId) { it.copy(title = title.trim()) }

    /**
     * Adds a task and puts its first step on [date]: in a free cell, or over a grey cell. Returns
     * false as the third value when the day is full; the planner then places it on the next free day.
     */
    fun addTaskOn(board: Board, spec: NewTask, date: LocalDate, today: LocalDate): Triple<Board, Task, Boolean> {
        val (withTask, task) = addTask(board, spec, today)
        val day = date.toString()
        val cellsThatDay = withTask.tasks.flatMap { it.steps }.filter { it.date == day && it.slot != null }
        val free = (0 until SLOTS_PER_DAY).firstOrNull { slot -> cellsThatDay.none { it.slot == slot } }
        val grey = cellsThatDay.firstOrNull { it.outcome != null }
        val slot = free ?: grey?.slot ?: return Triple(withTask, task, false)
        var next = withTask
        // A grey cell only keeps its record while nothing new needs its place.
        if (free == null && grey != null) next = mapStep(next, grey.id) { it.copy(date = null, slot = null) }
        val firstId = task.steps.first().id
        next = mapStep(next, firstId) { it.copy(date = day, slot = slot, pinned = true) }
        return Triple(next, next.tasks.first { it.id == task.id }, true)
    }

    /** Pins a step to a day (and a free column). Returns null if that day is full. */
    fun moveStep(board: Board, stepId: String, date: LocalDate): Board? {
        val used = board.tasks.flatMap { it.steps }
            .filter { it.id != stepId && it.date == date.toString() }
            .mapNotNull { it.slot }.toSet()
        val slot = (0 until SLOTS_PER_DAY).firstOrNull { it !in used } ?: return null
        return mapStep(board, stepId) { it.copy(date = date.toString(), slot = slot, pinned = true) }
    }

    fun findStep(board: Board, stepId: String): Pair<Task, Step>? =
        board.tasks.firstNotNullOfOrNull { t -> t.steps.firstOrNull { it.id == stepId }?.let { t to it } }

    /** Removes finished tasks whose last cell is older than [keepDays] days. */
    fun archiveOld(board: Board, today: LocalDate, keepDays: Long = 60): Board = board.copy(
        tasks = board.tasks.filterNot { t ->
            t.isDone && t.steps.mapNotNull { it.date }.maxOrNull()
                ?.let { LocalDate.parse(it).isBefore(today.minusDays(keepDays)) } == true
        },
    )

    fun addRule(board: Board, text: String): Board {
        val order = (board.rules.maxOfOrNull { it.order } ?: 0) + 1
        return board.copy(rules = board.rules + AssistantRule(newId(), text.trim(), order))
    }

    fun updateRule(board: Board, ruleId: String, change: (AssistantRule) -> AssistantRule): Board =
        board.copy(rules = board.rules.map { if (it.id == ruleId) change(it) else it })

    fun deleteRule(board: Board, ruleId: String): Board =
        normalizeRules(board.copy(rules = board.rules.filter { it.id != ruleId }))

    /** Moves a rule up (-1) or down (+1) in priority. */
    fun moveRule(board: Board, ruleId: String, delta: Int): Board {
        val sorted = board.rules.sortedBy { it.order }.toMutableList()
        val index = sorted.indexOfFirst { it.id == ruleId }
        val target = index + delta
        if (index < 0 || target !in sorted.indices) return board
        sorted.add(target, sorted.removeAt(index))
        return board.copy(rules = sorted.mapIndexed { i, r -> r.copy(order = i + 1) })
    }

    private fun normalizeRules(board: Board): Board =
        board.copy(rules = board.rules.sortedBy { it.order }.mapIndexed { i, r -> r.copy(order = i + 1) })

    private fun mapStep(board: Board, stepId: String, change: (Step) -> Step): Board = board.copy(
        tasks = board.tasks.map { t ->
            if (t.steps.none { it.id == stepId }) t else t.copy(steps = t.steps.map { if (it.id == stepId) change(it) else it })
        },
    )
}
