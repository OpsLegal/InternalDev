package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import java.time.LocalDate

/** How projects stand, and the moves that give a task one more cell (Extend). */
object Projects {

    /** Where a project ends and whether that lands too close to its deadline (one day of margin). */
    data class End(val end: LocalDate?, val open: Int, val late: Boolean, val deadline: LocalDate?)

    fun end(board: Board, name: String): End {
        val t = BoardOps.projectTask(board, name)
        val p = BoardOps.findProject(board, name)
        val open = t?.steps?.filter { !it.closed }.orEmpty()
        val last = open.mapNotNull { it.date?.let(LocalDate::parse) }.maxOrNull()
        val deadline = p?.deadline?.let(LocalDate::parse)
        val late = last != null && deadline != null && last.isAfter(deadline.minusDays(board.settings.deadlineBufferDays.toLong()))
        return End(last, open.size, late, deadline)
    }

    /** Progress of a project: steps done over steps kept (cancelled and pushed-away cells don't count). */
    data class Stats(
        val done: Int,
        val total: Int,
        val start: LocalDate?,
        val end: End,
        val next: Step?,
    ) {
        val percent: Int get() = if (total == 0) 0 else done * 100 / total
        val finished: Boolean get() = total > 0 && end.open == 0
    }

    fun stats(board: Board, project: Project): Stats {
        val steps = BoardOps.projectTask(board, project.name)?.steps.orEmpty().filter { it.outcome == null }
        return Stats(
            done = steps.count { it.done },
            total = steps.size,
            start = steps.mapNotNull { it.date?.let(LocalDate::parse) }.minOrNull(),
            end = end(board, project.name),
            next = steps.filter { !it.done && it.date != null }.minByOrNull { it.date!! },
        )
    }

    /** A parked idea: a project with no steps yet. Starting it means planning its steps. */
    fun isIdea(board: Board, project: Project): Boolean =
        BoardOps.projectTask(board, project.name)?.steps.orEmpty().none { it.outcome == null }

    /** A project that unlocks others is as important as the most important one it unlocks. */
    fun weight(board: Board, task: Task): Int {
        val project = BoardOps.findProject(board, task.project) ?: return task.priority.weight
        val seen = HashSet<String>()
        fun w(p: Project?): Int {
            if (p == null || !seen.add(p.name.lowercase())) return 0
            return maxOf(p.priority.weight, p.blocks.maxOfOrNull { w(BoardOps.findProject(board, it)) } ?: 0)
        }
        return w(project)
    }

    enum class Extension {
        /** One more cell of the same work. */
        MORE_EFFORT,

        /** Something needed to finish this task: it takes this cell, and this task moves on. */
        RELATED_TASK,
    }

    /** The result of Extend: the board with the new step, and which step now needs a cell on [day]. */
    data class Extended(val board: Board, val taskId: String, val stepToPlace: String, val day: LocalDate, val becameProject: Boolean)

    /**
     * Gives a task one more cell. A one-cell task becomes a project named after it (two levels: several cells
     * means a project). The step to place still needs a cell on [Extended.day]: place it with
     * [BoardOps.placeStep], or make room first with [movableOn].
     */
    fun extend(board: Board, stepId: String, how: Extension, relatedTitle: String, today: LocalDate): Extended? {
        val (original, step) = BoardOps.findStep(board, stepId) ?: return null
        val becameProject = !original.isProject
        var next = if (becameProject) BoardOps.toProject(board, original.id) else board
        val task = next.tasks.first { it.id == original.id }
        val current = task.steps.first { it.id == stepId }
        val from = current.date?.let(LocalDate::parse)?.takeIf { !it.isBefore(today) } ?: today
        val workDays = next.settings.workDays
        return when (how) {
            Extension.MORE_EFFORT -> {
                val extra = Step(
                    id = BoardOps.newId(), title = "${current.title} (cont.)",
                    kind = current.kind, effort = current.effort, description = current.description, values = current.values,
                )
                next = BoardOps.insertStep(next, task.id, stepId, extra, before = false)
                val t = next.tasks.first { it.id == task.id }
                Extended(next, task.id, extra.id, nextFreeDayFor(t, nextWorkday(from, workDays), workDays), becameProject)
            }
            Extension.RELATED_TASK -> {
                val title = relatedTitle.trim()
                require(title.isNotEmpty()) { "Say what the related task is." }
                // The related task takes this cell; this task moves on to a later day.
                val onTable = current.date != null && !from.isBefore(today) && current.date == from.toString()
                val related = Step(
                    id = BoardOps.newId(), title = title,
                    date = if (onTable) current.date else null, slot = if (onTable) current.slot else null, pinned = onTable,
                    notBefore = if (onTable) null else today.toString(),
                )
                next = BoardOps.insertStep(next, task.id, stepId, related, before = true)
                if (onTable) next = BoardOps.mapStep(next, stepId) { it.copy(date = null, slot = null, pinned = false) }
                val t = next.tasks.first { it.id == task.id }
                Extended(next, task.id, stepId, nextFreeDayFor(t, nextWorkday(from, workDays), workDays), becameProject)
            }
        }
    }

    /** The next working day after [day]. */
    fun nextWorkday(day: LocalDate, workDays: List<Int>): LocalDate {
        var d = day.plusDays(1)
        while (d.dayOfWeek.value !in workDays) d = d.plusDays(1)
        return d
    }

    /** From [day] on, the first working day where this task has no other cell (one cell per task per day). */
    fun nextFreeDayFor(task: Task, day: LocalDate, workDays: List<Int>): LocalDate {
        var d = day
        while (d.dayOfWeek.value !in workDays || task.steps.any { it.date == d.toString() }) d = d.plusDays(1)
        return d
    }

    /**
     * The least important cell of a day that could move to make room: not done or grey, not a meeting or a
     * deadline, not placed by hand, not part of [exceptTaskId].
     */
    fun movableOn(board: Board, day: LocalDate, exceptTaskId: String?, today: LocalDate): Pair<Task, Step>? {
        val weights = Planner.effectiveWeights(board)
        return board.tasks.asSequence()
            .filter { it.id != exceptTaskId }
            .flatMap { t -> t.steps.asSequence().map { t to it } }
            .filter { (t, st) ->
                st.date == day.toString() && !st.closed && !st.pinned &&
                    t.kindOf(st) == TaskKind.TASK && t.fixedDateOf(st) == null
            }
            .minByOrNull { (t, _) -> Planner.urgency(t, weights, today, Values.score(board, t)) }
    }

    /** True when [day] has no free cell. */
    fun isFull(board: Board, day: LocalDate): Boolean =
        board.tasks.flatMap { it.steps }.count { it.date == day.toString() && it.slot != null } >= SLOTS_PER_DAY

    /** Frees [victim]'s cell (it goes back to the planner from the next day) and puts [stepId] there. */
    fun makeRoom(board: Board, victimStepId: String, stepId: String, day: LocalDate): Board {
        val (_, victim) = BoardOps.findStep(board, victimStepId) ?: return board
        val slot = victim.slot
        var next = BoardOps.unschedule(board, victimStepId, day.plusDays(1).toString())
        next = BoardOps.mapStep(next, stepId) { it.copy(date = day.toString(), slot = slot, pinned = true) }
        return next
    }

    /** Values this step counts for: its own, its task's and its project's. */
    fun valueNames(board: Board, task: Task, step: Step): List<String> =
        (step.values + task.values + (BoardOps.findProject(board, task.project)?.values ?: emptyList())).distinct()
}
