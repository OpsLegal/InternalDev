package com.opslegal.tda.core.model

import kotlinx.serialization.Serializable

/** Number of task cells per day. The whole method is built around five. */
const val SLOTS_PER_DAY = 5

@Serializable
enum class Priority(val weight: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4),
}

/**
 * Something the user wants done. A task is made of one or more [Step]s; each step
 * fills exactly one cell of the table (one focused block of a few hours).
 */
@Serializable
data class Task(
    val id: String,
    val title: String,
    /** Free-form grouping, e.g. "Personal", "Refinancing", "OpsLegal". */
    val project: String = "",
    val priority: Priority = Priority.NORMAL,
    /** Hard deadline (ISO date). The last step must be scheduled on or before it. */
    val deadline: String? = null,
    /** For meetings and appointments: the only day this task can happen (ISO date). */
    val fixedDate: String? = null,
    /** Ids of tasks that cannot finish until this one is done. */
    val blocks: List<String> = emptyList(),
    /** Why delaying this hurts (money, other projects...). Shown to the assistant. */
    val impactNote: String = "",
    /** Minimum number of days between two steps of this task. 1 = next day. */
    val minDaysBetweenSteps: Int = 1,
    val steps: List<Step> = emptyList(),
    val createdAt: String = "",
) {
    val isDone: Boolean get() = steps.isNotEmpty() && steps.all { it.done }
}

@Serializable
data class Step(
    val id: String,
    val title: String,
    val done: Boolean = false,
    /** ISO date of the day this step is scheduled on, null when not yet placed. */
    val date: String? = null,
    /** Column 0..4 in the day row. */
    val slot: Int? = null,
    /** Set when the user pinned the step to a day; the planner will not move it. */
    val pinned: Boolean = false,
)

/**
 * A rule the assistant must follow. Rules are shown in the app and sent to the AI
 * in [order] (lower = more important). Users can edit, reorder and disable them.
 */
@Serializable
data class AssistantRule(
    val id: String,
    val text: String,
    val order: Int,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

@Serializable
data class PlannerSettings(
    /** ISO day-of-week numbers (1 = Monday .. 7 = Sunday) the planner may fill. */
    val workDays: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Days kept free of new work right before a deadline, as a safety margin. */
    val deadlineBufferDays: Int = 1,
    /** How far ahead the planner is allowed to place steps. */
    val horizonDays: Int = 120,
)

/** Everything the app persists. Serialized as one JSON document. */
@Serializable
data class Board(
    val tasks: List<Task> = emptyList(),
    val rules: List<AssistantRule> = emptyList(),
    val settings: PlannerSettings = PlannerSettings(),
    /** Notes the assistant keeps about the user's habits (its long-term memory). */
    val memory: List<String> = emptyList(),
    val version: Int = 1,
)

/** One cell of the table, flattened for display. */
data class Cell(
    val taskId: String,
    val stepId: String,
    val title: String,
    val project: String,
    val done: Boolean,
    val priority: Priority,
)

/** One line of the table: a day and its five cells (null = free cell). */
data class DayRow(
    val date: String,
    val label: String,
    val cells: List<Cell?>,
) {
    val filled: Int get() = cells.count { it != null }
    val completed: Int get() = cells.count { it?.done == true }

    /** The "all yellow" day: every scheduled cell is done and the row is not empty. */
    val allDone: Boolean get() = filled > 0 && completed == filled
}
