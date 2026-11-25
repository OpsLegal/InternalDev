package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate

/**
 * 📅 Plan it (swipe up in the feed): the same choice on every card. Today (when today is full, what moves out is said
 * before the tap), the next days with room, or a date picked. A quick thing goes on a day's ⚡ line; a cell in a day.
 */
object PlanIt {
    enum class Kind { QUICK, CELL }

    /** Room left on [day]: ⚡ places for quick things, big cells for cells. */
    fun free(board: Board, kind: Kind, day: LocalDate, today: LocalDate): Int = when (kind) {
        Kind.QUICK -> board.settings.quickPerDay.coerceAtLeast(1) - Quick.onDay(board, day, today).count { !it.done }
        Kind.CELL -> board.cellsPerDay - board.tasks.flatMap { it.steps }.count { it.date == day.toString() && it.slot != null && it.slot < board.cellsPerDay }
    }

    /** The next [n] days with room, from tomorrow ([not] = the day it is already on). */
    fun soon(board: Board, kind: Kind, today: LocalDate, not: LocalDate? = null, n: Int = 2): List<LocalDate> =
        generateSequence(today.plusDays(1)) { it.plusDays(1) }.take(60).filter { it != not && free(board, kind, it, today) > 0 }.take(n).toList()

    /** What leaves today to make room for it, null when today has room: the ⚡ thing that matters least, or the loosest cell. */
    fun todayCost(board: Board, kind: Kind, today: LocalDate, owner: String = ""): String? {
        if (free(board, kind, today, today) > 0) return null
        return when (kind) {
            Kind.QUICK -> Quick.onDay(board, today, today).filter { !it.done && it.id != owner }.minByOrNull { Quick.score(it, today) }?.title
            Kind.CELL -> victim(board, today, owner)?.let { (t, s) -> Planner.cellTitle(t, s) }
        }
    }

    private fun victim(board: Board, day: LocalDate, owner: String) = board.tasks.filter { t -> t.steps.none { it.id == owner } }
        .flatMap { t -> t.steps.map { t to it } }
        .filter { (t, s) -> s.date == day.toString() && s.slot != null && !s.done && s.outcome == null && !s.pinned && t.kindOf(s) == TaskKind.TASK && t.fixedDateOf(s) == null }
        .maxByOrNull { it.second.slot ?: 0 }

    /** A quick thing on [day]. Today when full: the one that matters least moves to the next day with room. */
    fun quickTo(board: Board, id: String, day: LocalDate, today: LocalDate): Board {
        if (day != today) return Quick.edit(board, id) { it.copy(date = day.toString()) }.let { b -> b.copy(quickPick = b.quickPick?.let { p -> p.copy(ids = p.ids - id) }) }
        var b = Quick.keep(board, today)
        if (free(b, Kind.QUICK, today, today) <= 0) {
            Quick.onDay(b, today, today).filter { !it.done && it.id != id }.minByOrNull { Quick.score(it, today) }?.let { out ->
                val to = soon(b, Kind.QUICK, today).firstOrNull() ?: today.plusDays(1)
                b = Quick.edit(b, out.id) { it.copy(date = to.toString()) }.let { x -> x.copy(quickPick = x.quickPick?.let { p -> p.copy(ids = p.ids - out.id) }) }
            }
        }
        b = Quick.edit(b, id) { it.copy(date = today.toString()) }
        return Quick.swap(b, "", today, with = id)
    }

    /** A cell on [day], pinned. A full day: its loosest cell goes back to the planner, from the day after. */
    fun cellTo(board: Board, stepId: String, day: LocalDate, today: LocalDate): Board {
        var b = BoardOps.mapStep(board, stepId) { it.copy(outcome = null) }
        val placed = BoardOps.moveStep(b, stepId, day) ?: victim(b, day, stepId)?.let { (_, v) ->
            BoardOps.moveStep(BoardOps.unschedule(b, v.id, day.plusDays(1).toString()), stepId, day)
        }
        // Only fixed cells that day: it takes a spare cell (the user picked this day).
        b = placed ?: run {
            val used = b.tasks.flatMap { it.steps }.filter { it.id != stepId && it.date == day.toString() }.mapNotNull { it.slot }.toSet()
            (0 until SLOTS_PER_DAY).firstOrNull { it !in used }?.let { slot -> BoardOps.mapStep(b, stepId) { it.copy(date = day.toString(), slot = slot, pinned = true) } }
                ?: BoardOps.mapStep(b, stepId) { it.copy(date = null, slot = null, pinned = false, notBefore = day.toString()) }
        }
        return Planner.plan(b, today, resort = false).board
    }
}
