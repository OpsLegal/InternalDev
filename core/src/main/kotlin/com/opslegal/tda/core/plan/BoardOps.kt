package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
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
        val kind: TaskKind = TaskKind.TASK,
        val effort: Effort = Effort.NORMAL,
        /** The user picked the effort (not a default or the assistant's guess). */
        val effortByUser: Boolean = false,
        val values: List<String> = emptyList(),
    )

    /** A new task and the cells it added. For a project, [task] is the project's task and [steps] the new steps. */
    data class Added(val board: Board, val task: Task, val steps: List<Step>)

    /**
     * Two levels only. Without a project and with one step: a one-cell task. With a project, or with several
     * steps: the steps are appended, in order, to that project (created if needed, named after the title when
     * none is given). Call [Planner.plan] afterwards to place the new cells.
     */
    fun add(board: Board, spec: NewTask, today: LocalDate): Added {
        spec.deadline?.let(LocalDate::parse)
        spec.fixedDate?.let(LocalDate::parse)
        val titles = spec.stepTitles.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(spec.title.trim()) }
        val values = spec.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        var name = spec.project.trim()
        if (name.isEmpty() && titles.size > 1) name = uniqueProjectName(board, spec.title.trim())
        if (name.isEmpty()) {
            val task = Task(
                id = newId(),
                title = spec.title.trim(),
                description = spec.description.trim(),
                kind = spec.kind,
                effort = spec.effort,
                effortByUser = spec.effortByUser,
                values = values,
                priority = spec.priority,
                deadline = spec.deadline,
                fixedDate = spec.fixedDate,
                blocks = spec.blocks.filter { id -> board.tasks.any { it.id == id } },
                impactNote = spec.impactNote,
                steps = listOf(Step(id = newId(), title = titles.single())),
                createdAt = today.toString(),
            )
            return Added(board.copy(tasks = board.tasks + task), task, task.steps)
        }
        var next = board
        val project = findProject(next, name) ?: Project(
            name = name,
            priority = spec.priority,
            deadline = spec.deadline,
            notes = if (titles.size > 1) spec.description.trim() else "",
            values = values,
        ).also { next = next.copy(projects = next.projects + it) }
        val single = titles.size == 1
        val steps = titles.map { title ->
            Step(
                id = newId(),
                title = title,
                kind = spec.kind.takeIf { it != TaskKind.TASK },
                effort = spec.effort.takeIf { it != Effort.NORMAL },
                fixedDate = spec.fixedDate,
                description = if (single) spec.description.trim() else "",
                values = if (single) values else emptyList(),
            )
        }
        val holder = projectTask(next, project.name)
        val task = holder?.copy(steps = holder.steps + steps) ?: Task(
            id = newId(),
            title = project.name,
            description = project.notes,
            project = project.name,
            priority = project.priority,
            deadline = project.deadline,
            minDaysBetweenSteps = spec.minDaysBetweenSteps.coerceAtLeast(1),
            steps = steps,
            createdAt = today.toString(),
        )
        next = if (holder == null) next.copy(tasks = next.tasks + task) else next.copy(tasks = next.tasks.map { if (it.id == task.id) task else it })
        return Added(next, task, steps)
    }

    fun addTask(board: Board, spec: NewTask, today: LocalDate): Pair<Board, Task> = add(board, spec, today).let { it.board to it.task }

    /** The task holding a project's steps. */
    fun projectTask(board: Board, name: String): Task? =
        board.tasks.firstOrNull { it.isProject && it.project.equals(name, ignoreCase = true) && it.title.equals(it.project, ignoreCase = true) }

    fun uniqueProjectName(board: Board, name: String): String {
        var candidate = name
        var n = 2
        while (findProject(board, candidate) != null) candidate = "$name (${n++})"
        return candidate
    }

    /** A one-cell task that needs more cells becomes a project named after it. */
    fun toProject(board: Board, taskId: String): Board {
        val t = board.tasks.firstOrNull { it.id == taskId } ?: return board
        if (t.isProject) return board
        val name = uniqueProjectName(board, t.title)
        val project = Project(name, t.priority, t.deadline, t.description, t.values)
        val converted = t.copy(
            title = name, project = name, kind = TaskKind.TASK, effort = Effort.NORMAL, fixedDate = null,
            steps = t.steps.map { st ->
                st.copy(
                    kind = st.kind ?: t.kind.takeIf { it != TaskKind.TASK },
                    effort = st.effort ?: t.effort.takeIf { it != Effort.NORMAL },
                    fixedDate = st.fixedDate ?: t.fixedDate,
                )
            },
        )
        return board.copy(projects = board.projects + project, tasks = board.tasks.map { if (it.id == taskId) converted else it })
    }

    /**
     * Converts a board saved before the two levels: tasks tagged with a project become steps of that project,
     * and one-cell tasks with several steps become projects. Runs once (Board.version 2).
     */
    fun migrateToTwoLevels(board: Board, today: LocalDate): Board {
        if (board.version >= 2) return board
        var next = board
        for (t in board.tasks) {
            if (t.isProject && !t.title.equals(t.project, ignoreCase = true)) {
                val moved = t.steps.map { st ->
                    st.copy(
                        kind = st.kind ?: t.kind.takeIf { it != TaskKind.TASK },
                        effort = st.effort ?: t.effort.takeIf { it != Effort.NORMAL },
                        fixedDate = st.fixedDate ?: t.fixedDate,
                        title = if (t.steps.size > 1 && st.title != t.title) "${t.title}: ${st.title}" else t.title,
                        description = st.description.ifBlank { t.description },
                        values = (st.values + t.values).distinct(),
                    )
                }
                next = next.copy(tasks = next.tasks.filter { it.id != t.id })
                val holder = projectTask(next, t.project)
                next = if (holder != null) {
                    next.copy(tasks = next.tasks.map { if (it.id == holder.id) it.copy(steps = it.steps + moved) else it })
                } else {
                    val p = findProject(next, t.project) ?: Project(t.project).also { next = next.copy(projects = next.projects + it) }
                    next.copy(tasks = next.tasks + Task(newId(), p.name, p.notes, p.name, priority = p.priority, deadline = p.deadline, steps = moved, createdAt = t.createdAt.ifBlank { today.toString() }))
                }
            } else if (!t.isProject && t.steps.size > 1) {
                next = toProject(next, t.id)
            }
        }
        return next.copy(version = 2)
    }

    fun findProject(board: Board, name: String): Project? =
        name.trim().takeIf { it.isNotEmpty() }?.let { n -> board.projects.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    /** Every project name in use: saved projects first, then names only found on tasks. */
    fun projectNames(board: Board): List<String> =
        (board.projects.map { it.name } + board.tasks.map { it.project.trim() }.filter { it.isNotEmpty() })
            .distinctBy { it.lowercase() }

    /**
     * Adds or updates a project (matched by [previousName] or its name, ignoring case). Its tasks
     * take its priority, and any task due after the project deadline is brought back to it.
     */
    fun saveProject(board: Board, project: Project, previousName: String? = null): Board {
        val name = project.name.trim()
        require(name.isNotEmpty()) { "A project needs a name." }
        project.deadline?.let(LocalDate::parse)
        val saved = project.copy(name = name, notes = project.notes.trim())
        val old = (previousName ?: name).trim()
        val matches = { n: String -> n.trim().equals(old, ignoreCase = true) || n.trim().equals(name, ignoreCase = true) }
        val projects = board.projects.filterNot { matches(it.name) } + saved
        val tasks = board.tasks.map { t ->
            when {
                !matches(t.project) -> t
                matches(t.title) -> t.copy(title = name, project = name, description = saved.notes.ifBlank { t.description }, priority = saved.priority, deadline = saved.deadline)
                else -> t.copy(project = name, priority = saved.priority, deadline = earliest(t.deadline, saved.deadline))
            }
        }
        return board.copy(projects = projects, tasks = tasks)
    }

    private fun earliest(a: String?, b: String?): String? = listOfNotNull(a, b).minOrNull()

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
        val (task, _) = findStep(board, stepId) ?: return board
        // Pushed twice: it feels heavy. Unless the user set the effort, treat it as heavy from now on.
        return updateTask(movePushed(board, stepId, today), task.id) { t ->
            val pushes = t.pushes + 1
            val learned = t.copy(pushes = pushes, effort = if (pushes >= 2 && !t.effortByUser && !t.isProject) Effort.HEAVY else t.effort)
            if (!t.isProject) return@updateTask learned
            // Keep the project's order: later steps already placed on future days go back to the planner.
            val index = learned.steps.indexOfFirst { it.id == stepId }
            learned.copy(steps = learned.steps.mapIndexed { i, st ->
                val future = st.date?.let { LocalDate.parse(it).isAfter(today) } == true
                if (i > index && !st.closed && !st.pinned && future) st.copy(date = null, slot = null) else st
            })
        }
    }

    private fun movePushed(board: Board, stepId: String, today: LocalDate): Board {
        val (task, step) = findStep(board, stepId) ?: return board
        val from = step.date?.let(LocalDate::parse) ?: today
        val notBefore = maxOf(from, today).plusDays(1).toString()
        if (from.isAfter(today)) {
            return mapStep(board, stepId) { it.copy(date = null, slot = null, pinned = false, notBefore = notBefore) }
        }
        val replacement = Step(
            id = newId(), title = step.title, notBefore = notBefore,
            kind = step.kind, effort = step.effort, description = step.description, values = step.values,
        )
        return updateTask(board, task.id) { t ->
            val steps = t.steps.flatMap { if (it.id == stepId) listOf(it.copy(outcome = Outcome.PUSHED, done = false), replacement) else listOf(it) }
            t.copy(steps = steps)
        }
    }

    /** Cancels one cell and frees it: the step leaves the table (it stays in the history as cancelled). */
    fun cancelStep(board: Board, stepId: String, today: LocalDate): Board = mapStep(board, stepId) { cancel(it, today) }

    /** Cancels everything left in a task. Done cells stay yellow. */
    fun cancelTask(board: Board, taskId: String, today: LocalDate): Board = updateTask(board, taskId) { t ->
        t.copy(steps = t.steps.map { if (it.closed) it else cancel(it, today) })
    }

    @Suppress("UNUSED_PARAMETER")
    private fun cancel(step: Step, today: LocalDate): Step =
        step.copy(outcome = Outcome.CANCELLED, date = null, slot = null, pinned = false, done = false)

    /** Moves every open step of a project at least a week later. */
    fun pushProjectWeek(board: Board, name: String, today: LocalDate): Board {
        val t = projectTask(board, name) ?: return board
        return updateTask(board, t.id) { task ->
            task.copy(steps = task.steps.map { st ->
                if (st.closed) st else {
                    val from = st.date?.let(LocalDate::parse)?.takeIf { it.isAfter(today) } ?: today
                    st.copy(date = null, slot = null, pinned = false, notBefore = from.plusDays(7).toString())
                }
            })
        }
    }

    /** Renames one cell (the task keeps its own title). */
    fun renameStep(board: Board, stepId: String, title: String): Board =
        mapStep(board, stepId) { it.copy(title = title.trim()) }

    /**
     * Adds a task and puts its first step on [date]: in a free cell, or over a grey cell. Returns
     * false as the third value when the day is full; the planner then places it on the next free day.
     */
    fun addTaskOn(board: Board, spec: NewTask, date: LocalDate, today: LocalDate): Triple<Board, Task, Boolean> {
        val (withTask, task, added) = add(board, spec, today)
        val day = date.toString()
        val cellsThatDay = withTask.tasks.flatMap { it.steps }.filter { it.date == day && it.slot != null }
        val free = (0 until SLOTS_PER_DAY).firstOrNull { slot -> cellsThatDay.none { it.slot == slot } }
        val grey = cellsThatDay.firstOrNull { it.outcome != null }
        val slot = free ?: grey?.slot ?: return Triple(withTask, task, false)
        var next = withTask
        // A grey cell only keeps its record while nothing new needs its place.
        if (free == null && grey != null) next = mapStep(next, grey.id) { it.copy(date = null, slot = null) }
        val firstId = added.first().id
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

    /** Puts a step on a day, pinned, in a free cell. Returns null if the day is full. */
    fun placeStep(board: Board, stepId: String, date: LocalDate): Board? = moveStep(board, stepId, date)

    /** Takes a step off the table so the planner places it again, not before [notBefore]. */
    fun unschedule(board: Board, stepId: String, notBefore: String? = null): Board =
        mapStep(board, stepId) { it.copy(date = null, slot = null, pinned = false, notBefore = notBefore ?: it.notBefore) }

    /** Inserts a step into a task's list, before or after [anchorStepId]. */
    fun insertStep(board: Board, taskId: String, anchorStepId: String, step: Step, before: Boolean): Board = updateTask(board, taskId) { t ->
        val i = t.steps.indexOfFirst { it.id == anchorStepId }.coerceAtLeast(0)
        t.copy(steps = t.steps.toMutableList().apply { add(if (before) i else i + 1, step) })
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

    fun mapStep(board: Board, stepId: String, change: (Step) -> Step): Board = board.copy(
        tasks = board.tasks.map { t ->
            if (t.steps.none { it.id == stepId }) t else t.copy(steps = t.steps.map { if (it.id == stepId) change(it) else it })
        },
    )
}
