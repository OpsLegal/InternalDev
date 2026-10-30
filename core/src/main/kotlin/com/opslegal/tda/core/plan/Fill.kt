package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.UpdateStatus
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate

/**
 * The widget's two big buttons. ⚡ fills today's quick things from the pool. 📥 fills today's free cells with big
 * work, in this order: tasks left undone (Replan), what a notification proposes, then each project's next step.
 * Project rules hold: a step never goes before the one that comes first, waits are kept, an office or someone
 * else only on office days, every task in its area's days.
 */
object Fill {
    /** Today's free big cells (calendar events not decided keep theirs). */
    fun free(board: Board, today: LocalDate): Int {
        val row = Planner.rows(board, today, 1, today = today).firstOrNull() ?: return 0
        return row.cells.take(board.cellsPerDay).count { it == null }
    }

    /** ⚡ Today's quick things: picked when none are, a few more once they are all done. */
    fun quick(board: Board, today: LocalDate): Board {
        val picks = Quick.onDay(board, today, today)
        return when {
            board.quickPick?.date != today.toString() || picks.isEmpty() -> Quick.keep(board, today, again = true)
            picks.all { it.done } -> Quick.more(board, today)
            else -> Quick.keep(board, today)
        }
    }

    /** 📥 Fills today's free cells; returns the board and what was placed (titles). */
    fun bigs(board: Board, today: LocalDate): Pair<Board, List<String>> {
        var b = board
        val placed = mutableListOf<String>()
        fun room() = free(b, today) > 0
        fun fits(t: Task, s: Step): Boolean {
            if (!Areas.canPlan(b, t, today)) return false
            if (Labels.weekdayOnly(t, s) && (today.dayOfWeek.value !in b.settings.officeDays || Holidays.isOff(today, b.settings))) return false
            if (s.notBefore != null && s.notBefore > today.toString()) return false
            // Project order: every step before it is done; its wait after the previous one is over.
            val before = t.steps.takeWhile { it.id != s.id }.filter { it.outcome == null }
            if (before.any { !it.done }) return false
            val prev = before.lastOrNull()
            if (s.waitDays > 0 && prev?.date != null && LocalDate.parse(prev.date).plusDays(s.waitDays.toLong()).isAfter(today)) return false
            return true
        }
        fun put(stepId: String): Boolean {
            val used = b.tasks.flatMap { it.steps }.filter { it.date == today.toString() && it.id != stepId }.mapNotNull { it.slot }.toSet()
            val slot = (0 until b.cellsPerDay).firstOrNull { it !in used } ?: return false
            b = BoardOps.leaveMissed(b, stepId, today)
            b = BoardOps.mapStep(b, stepId) { it.copy(date = today.toString(), slot = slot, pinned = true, notBefore = null) }
            return true
        }
        val weights = Planner.effectiveWeights(b)
        // 1. Tasks left undone on past days, the most urgent first.
        for ((t, s) in Replan.waiting(b, today).sortedByDescending { (t, _) -> Planner.urgency(t, weights, today, Values.score(b, t)) }) {
            if (!room()) break
            if (fits(t, s) && put(s.id)) placed += Planner.cellTitle(t, s)
        }
        // 2. What notifications propose (a task to add), urgent and nearest deadline first.
        for (u in Updates.inbox(b, today).filter { u -> u.status == UpdateStatus.NEW && u.actions.any { it.type == "add" && it.kind != com.opslegal.tda.core.model.TaskKind.MEETING } }) {
            if (!room()) break
            val ids = b.tasks.flatMap { it.steps }.map { it.id }.toSet()
            val next = Updates.apply(b, u, today) ?: continue
            val fresh = next.tasks.flatMap { t -> t.steps.map { t to it } }.filter { (_, s) -> s.id !in ids && !s.closed }
            val first = fresh.firstOrNull() ?: continue
            if (!fits(first.first, first.second)) continue
            b = next
            if (put(first.second.id)) placed += Planner.cellTitle(first.first, first.second)
        }
        // 3. Each project's next step, the most urgent project first.
        for (t in b.tasks.filter { it.isProject }.sortedByDescending { Planner.urgency(it, weights, today, Values.score(b, it)) }) {
            if (!room()) break
            val s = t.steps.firstOrNull { !it.closed && it.outcome == null } ?: continue
            if (s.date != null && s.date <= today.toString()) continue
            if (t.fixedDateOf(s) != null) continue
            if (fits(t, s) && put(s.id)) placed += Planner.cellTitle(t, s)
        }
        return Planner.plan(b, today).board to placed
    }
}
