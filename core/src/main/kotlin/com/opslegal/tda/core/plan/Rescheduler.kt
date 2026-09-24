package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Task
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A cell that changes day when an option is applied. */
data class Move(
    val taskId: String,
    val stepId: String,
    val title: String,
    val from: String,
    val to: String?,
)

data class RescheduleOption(
    val id: String,
    val title: String,
    val explanation: String,
    val moves: List<Move>,
    /** Titles of tasks that end up past their deadline with this option. */
    val lateTasks: List<String>,
    /** Lower is better. Used to rank options. */
    val cost: Int,
    val board: Board,
)

/**
 * When an urgent task does not fit before its deadline, builds a short ranked list of
 * ways to make room. Nothing is applied: the user (or the assistant, with the user's
 * approval) picks one.
 */
object Rescheduler {

    /**
     * [board] must already contain [urgentTaskId] with its steps unscheduled.
     * Returns options sorted best first. If the task simply fits, a single
     * "fits as is" option is returned.
     */
    fun options(board: Board, urgentTaskId: String, today: LocalDate): List<RescheduleOption> {
        val urgent = board.tasks.first { it.id == urgentTaskId }
        val asIs = Planner.plan(board, today, listOf(urgentTaskId))
        val urgentLate = asIs.late.any { it.taskId == urgentTaskId } ||
            asIs.unplaced.any { it.taskId == urgentTaskId }
        if (!urgentLate) {
            return listOf(option("fits", "It fits without moving anything",
                "Every step of \"${urgent.title}\" finds a free cell before its deadline.", board, asIs, urgentTaskId))
        }

        val weights = Planner.effectiveWeights(board)
        val deadline = urgent.deadline?.let(LocalDate::parse) ?: today.plusDays(14)
        val candidates = board.tasks
            .filter { it.id != urgentTaskId && it.fixedDate == null }
            .flatMap { task -> task.steps.filter { s ->
                !s.done && !s.pinned && s.date != null &&
                    LocalDate.parse(s.date) in today..deadline
            }.map { task to it } }

        fun bumpCost(task: Task, stepDate: String): Int {
            var cost = (weights[task.id] ?: task.priority.weight) * 10
            if (task.blocks.isNotEmpty()) cost += 15
            task.deadline?.let {
                val slack = ChronoUnit.DAYS.between(LocalDate.parse(stepDate), LocalDate.parse(it))
                cost += when {
                    slack < 3 -> 60
                    slack < 10 -> 20
                    else -> 0
                }
            }
            return cost
        }

        val result = mutableListOf<RescheduleOption>()

        // A: bump the cheapest cells, whatever their task.
        val cheapest = candidates.sortedBy { (t, s) -> bumpCost(t, s.date!!) }
        makeRoom(board, urgentTaskId, today, cheapest.map { it.second.id })?.let { plan ->
            result += option("least-important", "Move the least important cells",
                "Pushes back the cells whose delay costs the least (lowest inherited priority, most slack before their own deadline).",
                board, plan, urgentTaskId)
        }

        // B: only bump tasks that have no deadline at all, so nothing else can become late.
        val noDeadline = cheapest.filter { (t, _) -> t.deadline == null && t.blocks.isEmpty() }
        makeRoom(board, urgentTaskId, today, noDeadline.map { it.second.id })?.let { plan ->
            result += option("no-deadline-only", "Only move tasks without a deadline",
                "Makes room using cells of open-ended tasks only, so no other deadline is put at risk.",
                board, plan, urgentTaskId)
        }

        // C: move nothing and accept that the urgent task finishes late.
        result += option("accept-late", "Keep the plan, finish \"${urgent.title}\" late",
            "Nothing already planned moves; the new task takes the next free cells even if that is past its deadline.",
            board, asIs, urgentTaskId)

        return result
            .distinctBy { o -> o.moves.map { it.stepId to it.to }.toSet() to o.lateTasks.toSet() }
            .sortedBy { it.cost }
    }

    /**
     * Frees the fewest cells from [victimOrder] (tried in that order) so every step of the
     * urgent task lands before its deadline. Returns null when even bumping all of them fails.
     */
    private fun makeRoom(
        board: Board,
        urgentTaskId: String,
        today: LocalDate,
        victimOrder: List<String>,
    ): PlanResult? {
        fun attempt(victims: Set<String>): PlanResult {
            val cleared = board.copy(tasks = board.tasks.map { t ->
                t.copy(steps = t.steps.map { s -> if (s.id in victims) s.copy(date = null, slot = null) else s })
            })
            return Planner.plan(cleared, today, listOf(urgentTaskId))
        }
        fun fits(p: PlanResult) = p.late.none { it.taskId == urgentTaskId } && p.unplaced.none { it.taskId == urgentTaskId }

        val chosen = LinkedHashSet<String>()
        var plan: PlanResult? = null
        for (victim in victimOrder) {
            chosen += victim
            val p = attempt(chosen)
            if (fits(p)) { plan = p; break }
        }
        if (plan == null) return null
        // Drop victims that turned out not to be needed.
        for (victim in chosen.toList().reversed()) {
            val p = attempt(chosen - victim)
            if (fits(p)) { chosen -= victim; plan = p }
        }
        return plan
    }

    private fun option(
        id: String,
        title: String,
        explanation: String,
        before: Board,
        plan: PlanResult,
        urgentTaskId: String,
    ): RescheduleOption {
        val beforeSteps = before.tasks.flatMap { t -> t.steps.map { it.id to (t to it) } }.toMap()
        val moves = plan.board.tasks.filter { it.id != urgentTaskId }.flatMap { t ->
            t.steps.mapNotNull { s ->
                val old = beforeSteps[s.id]?.second ?: return@mapNotNull null
                if (old.date != null && old.date != s.date) Move(t.id, s.id, t.title, old.date, s.date) else null
            }
        }
        val weights = Planner.effectiveWeights(plan.board)
        val lateTaskIds = (plan.late.map { it.taskId } + plan.unplaced.map { it.taskId }).toSet()
        val lateTasks = plan.board.tasks.filter { it.id in lateTaskIds }
        val lateDays = plan.late.sumOf { ChronoUnit.DAYS.between(LocalDate.parse(it.deadline), LocalDate.parse(it.date)).coerceAtLeast(1).toInt() }
        val cost = moves.sumOf { (weights[it.taskId] ?: 1) * 5 } +
            lateTasks.sumOf { (weights[it.id] ?: 1) * 100 } + lateDays * 10 +
            if (id == "accept-late") 1 else 0
        return RescheduleOption(id, title, explanation, moves, lateTasks.map { it.title }, cost, plan.board)
    }
}
