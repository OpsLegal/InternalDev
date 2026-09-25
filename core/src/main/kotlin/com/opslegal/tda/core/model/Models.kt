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
    /**
     * The explanation behind the title: what it is, why, any context. The cell only shows the
     * title (which can stay discreet); the assistant always reads this.
     */
    val description: String = "",
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
    /** Nothing left to do: every step is done, pushed (and replaced) or cancelled. */
    val isDone: Boolean get() = steps.isNotEmpty() && steps.all { it.closed }
}

/** How a cell ended without being done. Both show grey in the table. */
@Serializable
enum class Outcome {
    /** Moved to a later day; a new step was created for it. */
    PUSHED,

    /** Will not be done. */
    CANCELLED,
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
    /** Pushed or cancelled (grey). Null while the step is still to do or done. */
    val outcome: Outcome? = null,
    /** The planner will not place this step before this ISO date (used when a cell is pushed). */
    val notBefore: String? = null,
) {
    /** Done, pushed or cancelled: nothing more to do in this cell. */
    val closed: Boolean get() = done || outcome != null
}

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

/** When the assistant must repeat what it understood and wait for a yes before changing the table. */
@Serializable
enum class ConfirmationPolicy {
    /** Every change, even marking a cell done. */
    ALWAYS,

    /** Only changes that are hard to see or undo: moving, deleting, rescheduling, editing, rules. */
    IMPORTANT,

    /** Act directly. */
    NEVER,
}

/** How the assistant listens, talks and checks its understanding. Editable in Settings. */
@Serializable
data class ConversationSettings(
    val confirmation: ConfirmationPolicy = ConfirmationPolicy.ALWAYS,
    /** Ask one short question when the request is ambiguous instead of guessing. */
    val askWhenUnsure: Boolean = true,
    /** Main language (BCP 47 tag): used when the phone can't tell which language you speak. */
    val voiceLanguage: String = "en-US",
    /** Other languages you speak. You can start any conversation in any of them. */
    val otherLanguages: List<String> = emptyList(),
    /** Read the assistant's answers aloud when you spoke to it. */
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Start listening again after every answer, for a hands-free conversation. */
    val handsFree: Boolean = false,
    /** How long you can pause before the assistant considers you finished. */
    val pauseSeconds: Float = 3f,
    /** Wait twice as long when the sentence sounds unfinished ("...and", "...because"). */
    val waitWhenUnfinished: Boolean = true,
    /** Saying one of these at the end hands the turn over immediately. */
    val endPhrases: List<String> = listOf("go ahead", "that's all", "over to you", "vas-y", "c'est tout", "à toi"),
    val yesWords: List<String> = listOf(
        "yes", "yeah", "yep", "ok", "okay", "sure", "correct", "right", "exactly", "do it", "go ahead", "perfect",
        "no problem", "why not",
        "oui", "ouais", "d'accord", "exact", "parfait", "vas-y", "c'est ça", "c'est bon", "pas de problème", "pourquoi pas",
    ),
    val noWords: List<String> = listOf(
        "no", "nope", "not", "wait", "stop", "cancel", "wrong",
        "non", "pas", "attends", "annule", "faux",
    ),
) {
    /** Main language first, then the others. */
    val languages: List<String> get() = (listOf(voiceLanguage) + otherLanguages).distinct()
}

/** Everything the app persists. Serialized as one JSON document. */
@Serializable
data class Board(
    val tasks: List<Task> = emptyList(),
    val rules: List<AssistantRule> = emptyList(),
    val settings: PlannerSettings = PlannerSettings(),
    val conversation: ConversationSettings = ConversationSettings(),
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
    val outcome: Outcome? = null,
)

/** One line of the table: a day and its five cells (null = free cell). */
data class DayRow(
    val date: String,
    val label: String,
    val cells: List<Cell?>,
) {
    val filled: Int get() = cells.count { it != null }
    val completed: Int get() = cells.count { it?.done == true }

    /** Cells still waiting to be done (not yellow, not grey). */
    val open: Int get() = cells.count { it != null && !it.done && it.outcome == null }

    /** The "all yellow" day: something was done and nothing is left open (grey cells count as settled). */
    val allDone: Boolean get() = completed > 0 && open == 0
}
