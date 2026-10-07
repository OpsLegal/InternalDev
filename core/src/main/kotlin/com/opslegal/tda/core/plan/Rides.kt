package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.BuyItem
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Rider
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ride along: a quick thing (same place or activity, no preparation) done during a cell already planned, with no
 * cell of its own. Physical ones join only when the places are close enough (Settings: max detour).
 */
object Rides {

    /** A cell a new task could ride along with: open, planned from today on. */
    data class Host(val stepId: String, val title: String, val date: String, val where: String, val errands: Boolean)

    fun hosts(board: Board, today: LocalDate, days: Long = 14): List<Host> = board.tasks.flatMap { t ->
        t.steps.filter { s -> !s.closed && s.date != null && s.date >= today.toString() && s.date <= today.plusDays(days).toString() }
            .map { s -> Host(s.id, Planner.cellTitle(t, s), s.date!!, t.where, t.errands) }
    }.sortedBy { it.date }

    /** Adds the quick thing to the host cell; on an errands trip it goes on that trip's shopping list. */
    fun add(board: Board, hostStepId: String, title: String, description: String = "", where: String = ""): Board {
        val (task, _) = BoardOps.findStep(board, hostStepId) ?: return board
        if (task.errands) return board.copy(buy = board.buy + BuyItem(BoardOps.newId(), title.trim(), errand = task.id))
        val rider = Rider(BoardOps.newId(), title.trim(), description.trim(), where.trim())
        return BoardOps.mapStep(board, hostStepId) { it.copy(riders = it.riders + rider) }
    }

    fun setDone(board: Board, stepId: String, riderId: String, done: Boolean): Board =
        BoardOps.mapStep(board, stepId) { s -> s.copy(riders = s.riders.map { if (it.id == riderId) it.copy(done = done) else it }) }

    /** All the extras of a cell done (when the cell is). */
    fun allDone(board: Board, stepId: String): Board = BoardOps.mapStep(board, stepId) { s -> s.copy(riders = s.riders.map { it.copy(done = true) }) }

    /** Extras not done when their cell is closed or pushed: each becomes a small task of its own. Nothing is lost. */
    fun release(board: Board, stepId: String, today: LocalDate): Board {
        val (_, step) = BoardOps.findStep(board, stepId) ?: return board
        val open = step.riders.filter { !it.done }
        if (open.isEmpty()) return board
        var next = BoardOps.mapStep(board, stepId) { s -> s.copy(riders = s.riders.filter { it.done }) }
        open.forEach { r ->
            next = BoardOps.add(next, BoardOps.NewTask(r.title, description = r.description.ifBlank { r.title }, effort = Effort.LIGHT, where = r.where), today).board
        }
        return next
    }

    /** Straight-line distance in km between two points (latitude, longitude). */
    fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
