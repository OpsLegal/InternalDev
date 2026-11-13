package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate

/**
 * ↻ Replan: big tasks left undone (red) come back without piling up on tomorrow. By rules on the phone, no AI:
 * at most one old task a day, and only on a day still under what the user really finishes (their 4-week average,
 * else the day's big cells). A project's steps keep their order; its later steps follow. Red records stay as records.
 */
object Replan {
    data class Item(
        val stepId: String, val title: String, val missedOn: String, val project: String, val to: LocalDate?,
        /** Looks like 15 minutes or less: offered as a quick thing (to the pool). */
        val quick: Boolean = false,
        /** Who it involves ("office", "others", "alone", ""): an office or someone else only on office days. */
        val who: String = "",
        /** The area is not sure: asked once, and learned. */
        val askArea: Boolean = false, val taskId: String = "",
        /** Why it is placed there ("📦 Deliveries by due date · due Nov 20"), and whether the user picked the day. */
        val why: String = "", val picked: Boolean = false, val emergency: Boolean = false,
    )
    data class Plan(val items: List<Item>, val week: List<Pair<LocalDate, Int>>, val late: List<Pair<String, Projects.End>>)

    /** Undone big cells from past days, still waiting (not done, not sent on), oldest first. */
    fun waiting(board: Board, today: LocalDate): List<Pair<com.opslegal.tda.core.model.Task, com.opslegal.tda.core.model.Step>> =
        board.tasks.flatMap { t -> t.steps.filter { BoardOps.isMissed(it, today) && it.outcome == null }.map { t to it } }.sortedBy { it.second.date }

    /** Projects already set to end after their deadline (stuck): named, with their end, for a way out with the assistant. */
    fun stuck(board: Board): List<Pair<String, Projects.End>> =
        board.projects.map { it.name to Projects.end(board, it.name) }.filter { it.second.late && it.second.open > 0 }

    /** What the user really finishes in a day: the learned average when there is one, never more than the day's big cells. */
    fun realistic(board: Board, today: LocalDate): Int = minOf(board.cellsPerDay, Quick.learned(board, today) ?: board.cellsPerDay).coerceAtLeast(1)

    private fun bigOn(board: Board, day: LocalDate): Int =
        board.tasks.flatMap { it.steps }.count { it.date == day.toString() && it.slot != null && it.slot < board.cellsPerDay && it.outcome == null }

    /** The proposal: each waiting cell's new day ([later] pushes one item's day further), and the days after it. */
    fun propose(board: Board, today: LocalDate, later: Map<String, Int> = emptyMap(), skip: Set<String> = emptySet(), picks: Map<String, LocalDate> = emptyMap()): Plan {
        val cap = realistic(board, today)
        val weights = Planner.effectiveWeights(board)
        // Most important first (the planner's own order), from tomorrow, into days with room: never in the past, never piled up.
        val items = waiting(board, today).filter { it.second.id !in skip }
            .sortedBy { (t, s) -> Order.key(board, t, s, today, weights) }
        val extra = HashMap<LocalDate, Int>()
        val lastOfTask = HashMap<String, LocalDate>()
        val out = items.map { (t, s) ->
            var d = today.plusDays(1)
            // A project's step never goes before the step that comes first.
            t.steps.takeWhile { it.id != s.id }.filter { !it.closed }.mapNotNull { it.date?.let(LocalDate::parse) }.maxOrNull()?.let { if (!it.isBefore(d)) d = it.plusDays(1) }
            lastOfTask[t.id]?.let { if (!it.isBefore(d)) d = it.plusDays(1) }
            var skips = later[s.id] ?: 0
            var found: LocalDate? = null
            for (i in 0 until board.settings.horizonDays) {
                val office = !Labels.weekdayOnly(t, s) || (d.dayOfWeek.value in board.settings.officeDays && !Holidays.isOff(d, board.settings))
                val ok = Areas.canPlan(board, t, d) && office && bigOn(board, d) + (extra[d] ?: 0) < cap
                if (ok) { if (skips == 0) { found = d; break } else skips-- }
                d = d.plusDays(1)
            }
            // A day the user picked wins (from tomorrow on).
            picks[s.id]?.takeIf { it.isAfter(today) }?.let { found = it }
            found?.let { extra[it] = (extra[it] ?: 0) + 1; lastOfTask[t.id] = it }
            Item(s.id, Planner.cellTitle(t, s), s.date!!, t.project, found, quick = Labels.sizeOf(s.title.ifBlank { t.title }) == "quick" && !t.isProject,
                who = Labels.who(t, s), askArea = !Areas.sure(board, t) && Areas.all(board.settings).size > 1, taskId = t.id,
                why = Order.label(board, t, s, today), picked = picks[s.id]?.isAfter(today) == true, emergency = Order.group(board, t, s, today) == "emergency")
        }
        val week = (1..7L).map { today.plusDays(it) }.map { it to bigOn(board, it) + (extra[it] ?: 0) }
        val after = apply(board, out, today)
        val late = stuck(after)
        return Plan(out, week, late)
    }

    /**
     * Puts each item on its day (pinned, a red record stays on the missed day); a project's later steps that are not
     * pinned go back to the planner so they follow. Items without a day are left as they are.
     */
    fun apply(board: Board, items: List<Item>, today: LocalDate): Board {
        var b = board
        for (it in items) {
            val day = it.to ?: continue
            b = BoardOps.leaveMissed(b, it.stepId, today)
            val (t, _) = BoardOps.findStep(b, it.stepId) ?: continue
            val used = b.tasks.flatMap { x -> x.steps }.filter { s -> s.date == day.toString() && s.id != it.stepId }.mapNotNull { s -> s.slot }.toSet()
            val slot = (0 until b.cellsPerDay).firstOrNull { s -> s !in used } ?: (0 until SLOTS_PER_DAY).firstOrNull { s -> s !in used } ?: continue
            b = BoardOps.mapStep(b, it.stepId) { s -> s.copy(date = day.toString(), slot = slot, pinned = true, notBefore = null, pushLog = s.pushLog + today.toString()) }
            if (t.isProject) {
                val i = t.steps.indexOfFirst { s -> s.id == it.stepId }
                val follow = t.steps.drop(i + 1).filter { s -> !s.closed && !s.pinned && t.fixedDateOf(s) == null && (s.date ?: "9") <= day.toString() }.map { s -> s.id }.toSet()
                b = BoardOps.updateTask(b, t.id) { x -> x.copy(steps = x.steps.map { s -> if (s.id in follow) s.copy(date = null, slot = null, notBefore = day.plusDays(1).toString()) else s }) }
            }
        }
        return Planner.plan(b, today).board
    }
}
