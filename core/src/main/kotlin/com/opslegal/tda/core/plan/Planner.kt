package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
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
     * This is what makes "file the tax report" as important as the refinancing it unlocks.
     */
    fun effectiveWeights(board: Board): Map<String, Int> {
        val byId = board.tasks.associateBy { it.id }
        val memo = HashMap<String, Int>()
        fun weight(id: String, visiting: Set<String>): Int {
            memo[id]?.let { return it }
            val task = byId[id] ?: return 0
            if (id in visiting) return task.priority.weight
            val inherited = task.blocks.maxOfOrNull { weight(it, visiting + id) } ?: 0
            return maxOf(task.priority.weight, inherited).also { memo[id] = it }
        }
        board.tasks.forEach { weight(it.id, emptySet()) }
        return memo
    }

    /** Higher is more urgent. Combines inherited priority and how close the deadline is. */
    fun urgency(task: Task, weights: Map<String, Int>, today: LocalDate): Int {
        var score = (weights[task.id] ?: task.priority.weight) * 100
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
    fun rollover(board: Board, today: LocalDate): Board = board.copy(
        tasks = board.tasks.map { task ->
            if (task.fixedDate != null) return@map task
            task.copy(steps = task.steps.map { step ->
                val date = step.date?.let(LocalDate::parse)
                if (!step.closed && date != null && date.isBefore(today)) {
                    step.copy(date = null, slot = null, pinned = false)
                } else step
            })
        },
    )

    /**
     * Places every unscheduled, unfinished step. Tasks listed in [firstTaskIds] are placed
     * before all others, whatever their urgency (used when an urgent task must jump the queue).
     */
    fun plan(board: Board, today: LocalDate, firstTaskIds: List<String> = emptyList()): PlanResult {
        val settings = board.settings
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
                    .thenByDescending { urgency(it, weights, today) }
                    .thenBy { it.createdAt },
            )

        val updated = board.tasks.associateBy { it.id }.toMutableMap()
        val unplaced = mutableListOf<Unplaced>()
        val late = mutableListOf<LateStep>()
        val lastDay = today.plusDays(settings.horizonDays.toLong())

        fun freeSlot(date: LocalDate): Int? {
            val used = occupied[date].orEmpty()
            return (0 until SLOTS_PER_DAY).firstOrNull { it !in used }
        }

        for (task in ordered) {
            val gap = task.minDaysBetweenSteps.coerceAtLeast(1).toLong()
            val taskDays = task.steps.mapNotNull { it.date?.let(LocalDate::parse) }.toMutableSet()
            var earliest = today
            val newSteps = task.steps.map { step ->
                if (step.closed || step.date != null) {
                    step.date?.let { d -> LocalDate.parse(d).plusDays(gap).let { if (it > earliest) earliest = it } }
                    return@map step
                }

                if (task.fixedDate != null) {
                    val day = LocalDate.parse(task.fixedDate)
                    val slot = freeSlot(day)
                    if (slot == null) {
                        unplaced += Unplaced(task.id, step.id, "${task.fixedDate} already has $SLOTS_PER_DAY tasks")
                        return@map step
                    }
                    occupied.getOrPut(day) { mutableSetOf() }.add(slot)
                    return@map step.copy(date = day.toString(), slot = slot)
                }

                var day = step.notBefore?.let(LocalDate::parse)?.takeIf { it > earliest } ?: earliest
                while (day <= lastDay) {
                    val allowed = day.dayOfWeek.value in settings.workDays &&
                        taskDays.none { ChronoUnit.DAYS.between(it, day).let { d -> d > -gap && d < gap } }
                    if (allowed && freeSlot(day) != null) break
                    day = day.plusDays(1)
                }
                if (day > lastDay) {
                    unplaced += Unplaced(task.id, step.id, "no free cell in the next ${settings.horizonDays} days")
                    return@map step
                }
                val slot = freeSlot(day)!!
                occupied.getOrPut(day) { mutableSetOf() }.add(slot)
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

    /** Rollover then plan: what the daily job runs every morning. */
    fun dailyRefresh(board: Board, today: LocalDate): PlanResult = plan(rollover(board, today), today)

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
                Cell(task.id, step.id, cellTitle(task, step), task.project, step.done, task.priority, step.outcome)
        }
        return (0 until days).map { from.plusDays(it.toLong()) }
            .filter { it.dayOfWeek.value in board.settings.workDays || byDate.containsKey(it.toString()) }
            .map { DayRow(it.toString(), DayLabel.of(it, language), byDate[it.toString()]?.toList() ?: List(SLOTS_PER_DAY) { null }) }
    }

    private fun cellTitle(task: Task, step: Step): String =
        if (task.steps.size <= 1 || step.title == task.title) task.title else "${task.title} · ${step.title}"
}
