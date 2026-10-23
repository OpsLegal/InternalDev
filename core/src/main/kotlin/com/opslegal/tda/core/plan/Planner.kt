package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A step the planner could not place, with the reason in plain words. */
data class Unplaced(val taskId: String, val stepId: String, val reason: String)

/** A step that got a cell, but after its task's deadline (minus the safety buffer). */
data class LateStep(val taskId: String, val stepId: String, val date: String, val deadline: String)

data class PlanResult(
    val board: Board,
    val unplaced: List<Unplaced> = emptyList(),
    val late: List<LateStep> = emptyList(),
)

/**
 * Deterministic scheduling of task steps into the 5-cell day rows.
 *
 * The planner is conservative on purpose: cells that are already on the table stay
 * where they are, so the user's plan does not reshuffle under their feet. Only
 * unscheduled steps are placed, most urgent task first, earliest free cell first,
 * and never two steps of the same task on the same day.
 */
object Planner {

    /**
     * A task inherits the priority of the most important task it blocks, recursively.
     * This is what makes "get the client's instructions" as urgent as the filing it unlocks.
     */
    fun effectiveWeights(board: Board): Map<String, Int> {
        val byId = board.tasks.associateBy { it.id }
        val memo = HashMap<String, Int>()
        fun weight(id: String, visiting: Set<String>): Int {
            memo[id]?.let { return it }
            val task = byId[id] ?: return 0
            // A project's task weighs what its project weighs (projects inherit from the ones they unlock).
            val own = maxOf(task.priority.weight, Projects.weight(board, task))
            if (id in visiting) return own
            val inherited = task.blocks.maxOfOrNull { weight(it, visiting + id) } ?: 0
            return maxOf(own, inherited).also { memo[id] = it }
        }
        board.tasks.forEach { weight(it.id, emptySet()) }
        return memo
    }

    /** Most heavy cells in one day: more and the day gets put off. */
    const val MAX_HEAVY_PER_DAY = 2

    /**
     * Higher is more urgent. Combines inherited priority, how close the deadline is, and
     * [valueScore]: the weights of what the task serves for the user (Brand, Money, Family...).
     */
    fun urgency(task: Task, weights: Map<String, Int>, today: LocalDate, valueScore: Int = 0): Int {
        var score = (weights[task.id] ?: task.priority.weight) * 100 + valueScore * 25
        if (task.fixedDate != null) score += 1_000
        task.deadline?.let {
            val daysLeft = ChronoUnit.DAYS.between(today, LocalDate.parse(it)).toInt()
            val remaining = task.steps.count { s -> !s.closed }
            // Less slack (days left per remaining step) means more urgent.
            score += (90 - (daysLeft - remaining)).coerceIn(0, 90)
        }
        return score
    }

    /** Unfinished steps from past days go back to the pool so [plan] can re-place them. */

    /**
     * Places every unscheduled, unfinished step. Tasks listed in [firstTaskIds] are placed
     * before all others, whatever their urgency (used when an urgent task must jump the queue).
     */
    fun plan(board0: Board, today: LocalDate, firstTaskIds: List<String> = emptyList()): PlanResult {
        val board = fit(board0, today)
        val settings = board.settings
        val perDay = board.cellsPerDay
        val occupied = HashMap<LocalDate, MutableSet<Int>>()
        for (task in board.tasks) for (step in task.steps) {
            val date = step.date ?: continue
            val slot = step.slot ?: continue
            occupied.getOrPut(LocalDate.parse(date)) { mutableSetOf() }.add(slot)
        }

        val weights = effectiveWeights(board)
        val ordered = board.tasks
            .filter { t -> t.steps.any { it.date == null && !it.closed } }
            .sortedWith(
                compareBy<Task> { t -> firstTaskIds.indexOf(t.id).let { if (it < 0) Int.MAX_VALUE else it } }
                    .thenByDescending { urgency(it, weights, today, Values.score(board, it)) }
                    .thenBy { it.createdAt },
            )

        val updated = board.tasks.associateBy { it.id }.toMutableMap()
        val unplaced = mutableListOf<Unplaced>()
        val late = mutableListOf<LateStep>()
        val lastDay = today.plusDays(settings.horizonDays.toLong())

        // Heavy cells already on the table, per day and column.
        val heavy = HashMap<LocalDate, MutableSet<Int>>()
        for (task in board.tasks) for (step in task.steps) {
            if (task.effortOf(step) != Effort.HEAVY || step.outcome != null) continue
            val date = step.date ?: continue
            val slot = step.slot ?: continue
            heavy.getOrPut(LocalDate.parse(date)) { mutableSetOf() }.add(slot)
        }

        // Calendar events not reviewed yet keep some cells free: what is in the calendar comes first.
        fun capacity(date: LocalDate) = perDay - (board.reserved[date.toString()] ?: 0).coerceIn(0, perDay)

        fun freeSlot(date: LocalDate): Int? {
            val used = occupied[date].orEmpty()
            if (used.size >= capacity(date)) return null
            return (0 until perDay).firstOrNull { it !in used }
        }

        // A fixed date (a meeting, a filing) may take a cell beyond the big ones: it can't go to another day.
        fun fixedSlot(date: LocalDate): Int? = freeSlot(date) ?: (0 until SLOTS_PER_DAY).firstOrNull { it !in occupied[date].orEmpty() }

        /** For heavy work: the first free cell of the day, but not right next to another heavy one. */
        fun heavySlot(date: LocalDate): Int? {
            val used = occupied[date].orEmpty()
            if (used.size >= capacity(date)) return null
            val hard = heavy[date].orEmpty()
            val free = (0 until perDay).filter { it !in used }
            return free.firstOrNull { it - 1 !in hard && it + 1 !in hard } ?: free.firstOrNull()
        }

        fun roomFor(task: Task, step: Step, date: LocalDate): Boolean =
            if (task.effortOf(step) == Effort.HEAVY) heavy[date].orEmpty().size < MAX_HEAVY_PER_DAY && freeSlot(date) != null
            else freeSlot(date) != null

        fun take(task: Task, step: Step, date: LocalDate): Int {
            val isHeavy = task.effortOf(step) == Effort.HEAVY
            val slot = if (isHeavy) heavySlot(date)!! else freeSlot(date)!!
            occupied.getOrPut(date) { mutableSetOf() }.add(slot)
            if (isHeavy) heavy.getOrPut(date) { mutableSetOf() }.add(slot)
            return slot
        }

        for (task in ordered) {
            val area = Areas.of(board, task)
            var gap = task.minDaysBetweenSteps.coerceAtLeast(1).toLong()
            // A close deadline: when one step a day can't fit before it, several steps go on the same day.
            task.deadline?.let { dl ->
                val limit = maxOf(today, LocalDate.parse(dl).minusDays(settings.deadlineBufferDays.toLong()))
                val days = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(limit) }
                    .count { Areas.canPlan(area, it, settings) }
                val open = task.steps.count { !it.closed && it.date == null }
                if (open > (days + gap - 1) / gap) gap = 0
            }
            val taskDays = task.steps.mapNotNull { it.date?.let(LocalDate::parse) }.toMutableSet()
            var earliest = today
            val newSteps = task.steps.map { step ->
                if (step.closed || step.date != null) {
                    step.date?.let { d -> LocalDate.parse(d).plusDays(gap).let { if (it > earliest) earliest = it } }
                    return@map step
                }

                val fixed = task.fixedDateOf(step)
                if (fixed != null) {
                    val day = LocalDate.parse(fixed)
                    val slot = fixedSlot(day)
                    if (slot == null) {
                        unplaced += Unplaced(task.id, step.id, "$fixed already has $SLOTS_PER_DAY tasks")
                        return@map step
                    }
                    taskDays += day
                    occupied.getOrPut(day) { mutableSetOf() }.add(slot)
                    return@map step.copy(date = day.toString(), slot = slot)
                }

                val from = step.notBefore?.let(LocalDate::parse)?.takeIf { it > earliest } ?: earliest
                // Each area stays on its own days (work off holidays). A bank or a public office: office days only, always.
                val office = Offices.needsOfficeDay(task, step)
                fun search(anyDay: Boolean, until: LocalDate): LocalDate {
                    var d = from
                    while (d <= until) {
                        val allowed = (anyDay || Areas.canPlan(area, d, settings)) &&
                            (!office || (d.dayOfWeek.value in settings.officeDays && !Holidays.isOff(d, settings))) &&
                            taskDays.none { ChronoUnit.DAYS.between(it, d).let { x -> x > -gap && x < gap } }
                        if (allowed && roomFor(task, step, d)) break
                        d = d.plusDays(1)
                    }
                    return d
                }
                var day = search(false, lastDay)
                // A deadline that work days can't meet: a weekend day before it, rather than missing it.
                task.deadline?.let { dl ->
                    val limit = LocalDate.parse(dl).minusDays(settings.deadlineBufferDays.toLong())
                    if (area.work && day.isAfter(limit) && !from.isAfter(limit)) {
                        val rescue = search(true, limit)
                        if (!rescue.isAfter(limit)) day = rescue
                    }
                }
                if (day > lastDay) {
                    unplaced += Unplaced(task.id, step.id, "no free cell in the next ${settings.horizonDays} days")
                    return@map step
                }
                val slot = take(task, step, day)
                taskDays += day
                earliest = day.plusDays(gap)
                task.deadline?.let { dl ->
                    val limit = LocalDate.parse(dl).minusDays(settings.deadlineBufferDays.toLong())
                    if (day.isAfter(limit)) late += LateStep(task.id, step.id, day.toString(), dl)
                }
                step.copy(date = day.toString(), slot = slot)
            }
            updated[task.id] = task.copy(steps = newSteps)
        }

        val tasks = board.tasks.map { updated.getValue(it.id) }
        return PlanResult(board.copy(tasks = tasks), unplaced, late)
    }

    /**
     * Work planned from today on in a cell beyond the big ones (the number was lowered) goes back to the planner,
     * unless it is pinned or tied to its date. Done and past cells stay as they are: nothing is lost.
     */
    fun fit(board: Board, today: LocalDate): Board {
        val perDay = board.cellsPerDay
        val t = today.toString()
        if (board.tasks.none { task -> task.steps.any { s -> s.slot != null && s.slot >= perDay && (s.date ?: "") >= t } }) return board
        return board.copy(tasks = board.tasks.map { task ->
            task.copy(steps = task.steps.map { s ->
                if (s.slot != null && s.slot >= perDay && (s.date ?: "") >= t && !s.closed && !s.pinned && task.fixedDateOf(s) == null) s.copy(date = null, slot = null) else s
            })
        })
    }

    /** Rollover then plan: what the daily job runs every morning. Quick things left undone come to today. */
    fun dailyRefresh(board: Board, today: LocalDate): PlanResult = plan(Quick.rollover(Quick.learn(board, today), today), today)

    /**
     * The table rows starting at [from]. Non-working days are only shown when
     * something is scheduled on them.
     */
    fun rows(board: Board, from: LocalDate, days: Int, language: String = "en"): List<DayRow> {
        val byDate = HashMap<String, Array<Cell?>>()
        for (task in board.tasks) for (step in task.steps) {
            val date = step.date ?: continue
            val slot = step.slot ?: continue
            if (slot !in 0 until SLOTS_PER_DAY) continue
            byDate.getOrPut(date) { arrayOfNulls(SLOTS_PER_DAY) }[slot] =
                Cell(
                    task.id, step.id, cellTitle(task, step), task.project, step.done, task.priority, step.outcome,
                    task.kindOf(step), task.effortOf(step), inProject = task.isProject, extras = step.riders.count { !it.done },
                )
        }
        // A day shows its big cells; a day that still holds a cell beyond them (a meeting, a done one) shows it too.
        val perDay = board.cellsPerDay
        val quick = board.quick.groupBy { it.date }
        return (0 until days).map { from.plusDays(it.toLong()) }
            .filter { (board.settings.areas.isNotEmpty() && Areas.shown(it, board.settings)) || it.dayOfWeek.value in board.settings.workDays || byDate.containsKey(it.toString()) || quick.containsKey(it.toString()) }
            .map { d ->
                val cells = byDate[d.toString()]?.toList() ?: List(SLOTS_PER_DAY) { null }
                val width = maxOf(perDay, cells.indexOfLast { it != null } + 1)
                DayRow(d.toString(), DayLabel.of(d, language), cells.take(width), quick[d.toString()].orEmpty())
            }
    }

    /** A one-cell task shows its title; a project step shows "Project · step" unless the step already names it. */
    fun cellTitle(task: Task, step: Step): String = when {
        !task.isProject -> if (task.steps.size <= 1 || step.title == task.title) task.title else "${task.title} · ${step.title}"
        step.title.contains(task.project, ignoreCase = true) -> step.title
        else -> "${task.project} · ${step.title}"
    }
}
