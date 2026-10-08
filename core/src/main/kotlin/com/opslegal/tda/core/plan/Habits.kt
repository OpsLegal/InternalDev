package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.HabitFix
import com.opslegal.tda.core.model.Outcome
import java.time.LocalDate

/**
 * Habits to work on, the weekly review's coaching part, in Dale Carnegie's spirit: begin with what went well, call
 * attention to a pattern indirectly (as a question, never a reproach), let the person explain and save face, make the
 * fault seem easy to correct, and track it kindly. The app finds the facts; the user names the cause; a proven fix is
 * applied in one tap and checked at the next review.
 */
object Habits {

    /** A pattern seen in the facts: the same task moved again and again, or a project with no step done in a week. */
    data class Pattern(val taskId: String, val title: String, val kind: String, val count: Int, val important: Boolean) {
        /** The flag, as a question (never a verdict). */
        val question: String get() = when (kind) {
            "pushed" -> "“$title” has moved $count times. Is it still important to you?"
            "missed" -> "“$title” stayed undone on $count days. Is it still important to you?"
            else -> "“$title” had no step done this week. Is it still important to you?"
        }
    }

    /** What gets in the way, as the user would say it, and the fix that usually works for it. */
    data class Cause(val key: String, val label: String, val practice: String, val fix: String)

    val causes = listOf(
        Cause("big", "It's bigger than it looks", "Big tasks get pushed because the first step is unclear. Make it small enough to start without thinking.",
            "Start with 30 minutes only, in your first free morning cell"),
        Cause("start", "I don't know where to start", "When the start is foggy, the first step is to decide the steps: 10 minutes, pen and paper.",
            "First 10 minutes: list the 3 first moves, first morning cell"),
        Cause("waiting", "I'm waiting for someone or something", "A task that depends on someone moves until you ask. One short message unblocks it.",
            "A quick cell today to ask for what's missing; the task waits 2 days"),
        Cause("dread", "I dread it", "What we dread is best done first, when energy is high, with a small reward right after.",
            "First cell of the morning, before email; an easy cell right after"),
        Cause("energy", "No energy when it comes", "Hard work lands better where you finish things: your mornings.",
            "Moved to the first cell of the next work day"),
        Cause("notimportant", "It's not important anymore", "Letting go of what no longer matters is a decision, not a failure: it frees a cell for what does.",
            "Let it go: the cell is freed"),
    )

    /** Patterns to look at, most repeated first; tasks already being worked on (an open fix) are left alone. */
    fun patterns(board: Board, today: LocalDate): List<Pattern> {
        val working = board.habitFixes.filter { it.outcome.isEmpty() }.map { it.taskId }.toSet()
        val weights = Planner.effectiveWeights(board)
        val out = mutableListOf<Pattern>()
        board.tasks.filter { it.id !in working && it.steps.any { s -> !s.closed } }.forEach { t ->
            val important = (weights[t.id] ?: t.priority.weight) >= 3 || t.deadline != null
            val missed = t.steps.count { it.outcome == Outcome.MISSED && it.date != null && it.date >= today.minusDays(14).toString() }
            when {
                t.pushes >= 2 -> out += Pattern(t.id, t.title, "pushed", t.pushes, important)
                missed >= 2 -> out += Pattern(t.id, t.title, "missed", missed, important)
            }
        }
        board.projects.filter { !Projects.isIdea(board, it) }.forEach { p ->
            val h = BoardOps.projectTask(board, p.name) ?: return@forEach
            if (h.id in working || out.any { it.taskId == h.id }) return@forEach
            val week = today.minusDays(7).toString()
            val past = h.steps.filter { it.date != null && it.date >= week && it.date < today.toString() }
            if (past.isNotEmpty() && past.none { it.done } && h.steps.any { !it.closed }) out += Pattern(h.id, p.name, "stalled", past.size, true)
        }
        return out.sortedWith(compareByDescending<Pattern> { it.important }.thenByDescending { it.count }).take(3)
    }

    /** Applies the fix for [cause] to the task's next open cell; returns the board and the fix in plain words. */
    fun apply(board: Board, taskId: String, cause: String, words: String, today: LocalDate): Pair<Board, HabitFix>? {
        val task = board.tasks.firstOrNull { it.id == taskId } ?: return null
        val step = task.steps.firstOrNull { !it.closed } ?: return null
        val c = causes.firstOrNull { it.key == cause }
        val nextDay = generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { Holidays.isWorkDay(it, board.settings) }
        var b = board
        fun firstCell(day: LocalDate): String {
            // The first cell of the morning: free it if needed (the cell there goes to the planner).
            val there = b.tasks.flatMap { t -> t.steps.map { t to it } }.firstOrNull { (_, s) -> s.date == day.toString() && s.slot == 0 && s.id != step.id }
            if (there != null && !there.second.closed && !there.second.pinned) b = BoardOps.unschedule(b, there.second.id, day.toString())
            val freeSlot = if (there == null || (!there.second.closed && !there.second.pinned)) 0 else Projects.freeSlotNear(b, day, 0)
            b = if (freeSlot != null) BoardOps.mapStep(b, step.id) { it.copy(date = day.toString(), slot = freeSlot, pinned = true, notBefore = null) }
                else BoardOps.unschedule(b, step.id, day.toString())
            return "${day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${day.dayOfMonth}"
        }
        val fix = when (cause) {
            "big" -> { b = BoardOps.mapStep(b, step.id) { it.copy(title = "First 30 min · ${it.title}", effort = Effort.LIGHT) }; "30 minutes only, first cell ${firstCell(nextDay)}" }
            "start" -> { b = BoardOps.mapStep(b, step.id) { it.copy(title = "10 min: list the first moves · ${it.title}", effort = Effort.LIGHT) }; "10 minutes to list the first moves, first cell ${firstCell(nextDay)}" }
            "waiting" -> {
                b = BoardOps.add(b, BoardOps.NewTask("Ask for what “${task.title}” needs", "One short message to unblock it.", effort = Effort.LIGHT), today).board
                b = BoardOps.unschedule(b, step.id, today.plusDays(2).toString())
                "a quick cell to ask for what's missing; the task waits 2 days"
            }
            "dread" -> { b = BoardOps.mapStep(b, step.id) { it.copy(effort = Effort.HEAVY) }; "first cell ${firstCell(nextDay)}, before email, an easy cell right after" }
            "energy" -> "first cell ${firstCell(nextDay)}"
            "notimportant" -> { b = BoardOps.cancelStep(b, step.id, today); "let go: its cell is freed" }
            else -> { "your own way: “${words.take(80)}”" }
        }
        val h = HabitFix(BoardOps.newId(), taskId, task.title, cause, words.trim(), fix, today.toString(), task.pushes,
            outcome = if (cause == "notimportant") "dropped" else "")
        b = Planner.plan(b, today).board
        return b.copy(habitFixes = (b.habitFixes + h).takeLast(30)) to h
    }

    /** At each review: an open fix whose task got done is a success; one pushed again since needs another way. */
    fun track(board: Board): Board = board.copy(habitFixes = board.habitFixes.map { h ->
        if (h.outcome.isNotEmpty()) return@map h
        val t = board.tasks.firstOrNull { it.id == h.taskId }
        when {
            t == null || t.isDone -> h.copy(outcome = "done")
            t.pushes > h.pushesAtStart -> h.copy(outcome = "again")
            else -> h
        }
    })

    /** For the AI's weekly advice and the talk: the patterns and how the fixes went. */
    fun facts(board: Board, today: LocalDate): String = buildString {
        patterns(board, today).forEach { appendLine("- pattern: ${it.question}") }
        board.habitFixes.takeLast(6).forEach { h ->
            appendLine("- fix since ${h.since} for “${h.title}”: cause “${causes.firstOrNull { it.key == h.cause }?.label ?: h.words}”, fix: ${h.fix}, " +
                when (h.outcome) { "done" -> "it worked (done)"; "again" -> "it slipped again"; "dropped" -> "let go"; else -> "in progress" })
        }
    }
}
