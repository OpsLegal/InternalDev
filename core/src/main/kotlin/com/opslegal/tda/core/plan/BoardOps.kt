package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.model.Board
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

    fun setStepDone(board: Board, stepId: String, done: Boolean): Board = mapStep(board, stepId) { it.copy(done = done) }

    fun toggleStep(board: Board, stepId: String): Board = mapStep(board, stepId) { it.copy(done = !it.done) }

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
