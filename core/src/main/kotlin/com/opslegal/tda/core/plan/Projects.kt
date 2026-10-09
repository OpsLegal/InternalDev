package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Outcome
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

    /** A later step fixed on a day (by the user, or a meeting) that a push would put before the pushed one. */
    data class Conflict(val stepId: String, val title: String, val date: LocalDate, val meeting: Boolean)
    data class PushImpact(val end: End, val day: LocalDate?, val conflicts: List<Conflict>)

    /** Before a push in a project: its new end, the pushed step's new day, and later fixed steps that would now come first. */
    fun pushImpact(board: Board, stepId: String, today: LocalDate, moves: Set<String> = emptySet()): PushImpact? {
        val (task, _) = BoardOps.findStep(board, stepId) ?: return null
        if (!task.isProject) return null
        val after = Planner.plan(BoardOps.pushStep(board, stepId, today, "", moves), today).board
        val t = after.tasks.firstOrNull { it.id == task.id } ?: return null
        val i = t.steps.indexOfFirst { it.id == stepId }.takeIf { it >= 0 } ?: return null
        val day = t.steps[i].date?.let(LocalDate::parse)
        val conflicts = t.steps.drop(i + 1).filter { s ->
            val d = s.date?.let(LocalDate::parse)
            !s.closed && d != null && (s.pinned || t.fixedDateOf(s) != null) && (day == null || !d.isAfter(day))
        }.map { Conflict(it.id, it.title, LocalDate.parse(it.date!!), t.kindOf(it) == com.opslegal.tda.core.model.TaskKind.MEETING) }
        return PushImpact(end(after, task.project), day, conflicts)
    }

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
        /** For a finished project, the day its last step was done: the bar stops there, not at today. */
        val finishedOn: LocalDate? = null,
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
            finishedOn = if (steps.isNotEmpty() && steps.all { it.done }) steps.mapNotNull { it.date?.let(LocalDate::parse) }.maxOrNull() else null,
        )
    }

    /** A parked idea: a project with no steps yet. Starting it means planning its steps. */
    fun isIdea(board: Board, project: Project): Boolean {
        val steps = BoardOps.projectTask(board, project.name)?.steps.orEmpty()
        return steps.none { it.outcome == null } || (isParked(board, project) && steps.none { it.outcome == null && !it.done })
    }

    fun isParked(board: Board, project: Project): Boolean =
        BoardOps.projectTask(board, project.name)?.steps.orEmpty().any { it.outcome == Outcome.PARKED }

    /** Park: the project's open steps leave the table and wait with the ideas. */
    fun park(board: Board, name: String, today: LocalDate): Board {
        val t = BoardOps.projectTask(board, name) ?: return board
        val parked = BoardOps.updateTask(board, t.id) { task ->
            task.copy(steps = task.steps.map { if (!it.closed) it.copy(outcome = Outcome.PARKED, date = null, slot = null, pinned = false) else it })
        }
        return Planner.plan(parked, today).board
    }

    /** Resume: the parked steps go back to the planner, into free cells only (nothing already planned moves). */
    fun resume(board: Board, name: String, today: LocalDate): Board {
        val t = BoardOps.projectTask(board, name) ?: return board
        val back = BoardOps.updateTask(board, t.id) { task ->
            task.copy(steps = task.steps.map { if (it.outcome == Outcome.PARKED) it.copy(outcome = null) else it })
        }
        return Planner.plan(back, today).board
    }

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

    /**
     * Extend = longer (the usual case): one more cell of the same work on the same day. Place it with [placeNear];
     * when the day is full, make room with [movableOn] / [makeRoom]. Another day is a split: [extend] with MORE_EFFORT.
     */
    fun longer(board: Board, stepId: String, today: LocalDate): Extended? {
        val (_, step) = BoardOps.findStep(board, stepId) ?: return null
        val ext = extend(board, stepId, Extension.MORE_EFFORT, "", today) ?: return null
        val day = step.date?.let(LocalDate::parse)?.takeIf { !it.isBefore(today) } ?: today
        return ext.copy(day = day)
    }

    /** The free cell of [day] closest to [near] (right after it first), or null when the day is full. */
    fun freeSlotNear(board: Board, day: LocalDate, near: Int?): Int? {
        val used = board.tasks.flatMap { it.steps }.filter { it.date == day.toString() }.mapNotNull { it.slot }.toSet()
        val free = (0 until SLOTS_PER_DAY).filter { it !in used }
        val n = near ?: return free.firstOrNull()
        return free.minByOrNull { if (it > n) (it - n) * 2 - 1 else (n - it) * 2 }
    }

    /** Puts [stepId] in the free cell of [day] closest to [near]; null when the day is full. */
    fun placeNear(board: Board, stepId: String, day: LocalDate, near: Int?): Board? {
        val slot = freeSlotNear(board, day, near) ?: return null
        return BoardOps.mapStep(board, stepId) { it.copy(date = day.toString(), slot = slot, pinned = true, notBefore = null) }
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

    /** A project after saving: the board, and the cells that moved later to make room before its deadline. */
    data class Saved(val board: Board, val moved: List<String>)

    /**
     * Saves a project and its steps as edited in the form, puts steps on the days the user chose, lets the
     * planner place the rest, then makes room before the deadline if steps would land after it.
     */
    fun save(board: Board, project: Project, previousName: String?, steps: List<BoardOps.EditedStep>, today: LocalDate): Saved {
        val name = project.name.trim()
        var b = BoardOps.saveProject(board, project, previousName)
        val list = steps.filter { it.title.isNotBlank() }
        b = BoardOps.setProjectSteps(b, name, list, today)
        BoardOps.projectTask(b, name)?.let { holder ->
            list.zip(holder.steps.filter { !it.closed }).forEach { (e, st) ->
                val day = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                if (st.date != day.toString()) b = pinStep(b, st.id, day, today)
            }
        }
        // A new area (My week): steps on a day it doesn't use go back to the planner.
        b = b.copy(tasks = b.tasks.map { t ->
            if (!t.project.equals(name, ignoreCase = true)) t
            else t.copy(steps = t.steps.map { st ->
                val d = st.date?.let(LocalDate::parse)
                if (d != null && !d.isBefore(today) && !st.closed && !st.pinned && t.fixedDateOf(st) == null && !Areas.canPlan(b, t, d)) st.copy(date = null, slot = null) else st
            })
        })
        b = Pacing.pace(b, name, today).board
        b = Planner.plan(b, today).board
        return makeRoomForDeadline(b, name, today)
    }

    /** Puts a step on [day] (the user's choice): in a free cell, or in the least important movable one. */
    fun pinStep(board: Board, stepId: String, day: LocalDate, today: LocalDate): Board {
        var b = BoardOps.mapStep(board, stepId) { it.copy(date = null, slot = null, pinned = false, notBefore = null) }
        BoardOps.moveStep(b, stepId, day)?.let { return it }
        val owner = BoardOps.findStep(b, stepId)?.first?.id
        val victim = movableOn(b, day, owner, today)
        return if (victim != null) makeRoom(b, victim.second.id, stepId, day)
        else BoardOps.mapStep(b, stepId) { it.copy(notBefore = day.toString()) }
    }

    /**
     * Steps of [name] that would land after its deadline (minus the buffer) take cells before it: free ones
     * first, else cells of less urgent work, which move later. Steps keep their order.
     */
    fun makeRoomForDeadline(board: Board, name: String, today: LocalDate): Saved {
        val project = BoardOps.findProject(board, name) ?: return Saved(board, emptyList())
        val deadline = project.deadline?.let(LocalDate::parse) ?: return Saved(board, emptyList())
        // Aim for the day before the deadline; when that can't hold everything, use the deadline day too.
        val aim = maxOf(today, deadline.minusDays(board.settings.deadlineBufferDays.toLong()))
        val first = roomBefore(board, project, aim, today)
        if (deadline.isBefore(today) || end(first.board, project.name).end?.isAfter(aim) != true) return first
        val second = roomBefore(first.board, project, deadline, today)
        return Saved(second.board, (first.moved + second.moved).distinct())
    }

    private fun roomBefore(board: Board, project: Project, limit: LocalDate, today: LocalDate): Saved {
        var b = board
        val moved = mutableListOf<String>()
        val holder = BoardOps.projectTask(b, project.name) ?: return Saved(board, emptyList())
        val weights = Planner.effectiveWeights(b)
        val mine = Planner.urgency(holder, weights, today, Values.score(b, holder))
        var from = today
        for (step in holder.steps) {
            if (step.closed) continue
            val day = step.date?.let(LocalDate::parse)
            if (day != null && !day.isAfter(limit)) { if (day > from) from = day; continue }
            var d = from
            while (!d.isAfter(limit)) {
                if (d.dayOfWeek.value in b.settings.workDays) {
                    val used = b.tasks.flatMap { it.steps }.filter { it.date == d.toString() && it.id != step.id }.mapNotNull { it.slot }.toSet()
                    val free = (0 until SLOTS_PER_DAY).firstOrNull { it !in used }
                    if (free != null) {
                        b = BoardOps.mapStep(b, step.id) { it.copy(date = d.toString(), slot = free, notBefore = null) }
                        break
                    }
                    val victim = movableOn(b, d, holder.id, today)
                    if (victim != null && Planner.urgency(victim.first, weights, today, Values.score(b, victim.first)) < mine) {
                        moved += Planner.cellTitle(victim.first, victim.second)
                        val slot = victim.second.slot
                        b = BoardOps.unschedule(b, victim.second.id, d.plusDays(1).toString())
                        b = BoardOps.mapStep(b, step.id) { it.copy(date = d.toString(), slot = slot, notBefore = null) }
                        break
                    }
                }
                d = d.plusDays(1)
            }
            if (!d.isAfter(limit)) from = d
        }
        if (moved.isEmpty() && b == board) return Saved(board, emptyList())
        return Saved(Planner.plan(b, today).board, moved)
    }
}
