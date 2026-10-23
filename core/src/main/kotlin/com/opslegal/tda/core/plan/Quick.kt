package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.QuickThing
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import java.time.LocalDate

/**
 * Quick things: 15 minutes or less (an email, a call to set something up). They never take a cell: each day has a
 * ⚡ line under its big cells, up to [com.opslegal.tda.core.model.PlannerSettings.quickMax] of them, ticked one by one.
 * What is left undone comes to today the next morning, never red.
 */
object Quick {
    /** Adds each title to [day] (today at the earliest), or the next day with room. Returns the board and the days used. */
    fun add(board: Board, titles: List<String>, day: LocalDate, today: LocalDate): Pair<Board, List<LocalDate>> {
        var b = board
        val days = mutableListOf<LocalDate>()
        for (t in titles.map { it.trim() }.filter { it.isNotEmpty() }) {
            var d = if (day.isBefore(today)) today else day
            while (b.quick.count { it.date == d.toString() && !it.done } >= b.settings.quickMax.coerceAtLeast(1)) d = d.plusDays(1)
            b = b.copy(quick = b.quick + QuickThing(BoardOps.newId(), t, d.toString()))
            days += d
        }
        return b to days
    }

    fun tick(board: Board, id: String, done: Boolean): Board = board.copy(quick = board.quick.map { if (it.id == id) it.copy(done = done) else it })

    fun remove(board: Board, id: String): Board = board.copy(quick = board.quick.filterNot { it.id == id })

    /** What is left on [from] goes to [to] (or the next day with room). */
    fun moveLeft(board: Board, from: LocalDate, to: LocalDate, today: LocalDate): Board {
        val left = board.quick.filter { it.date == from.toString() && !it.done }
        val rest = board.copy(quick = board.quick - left.toSet())
        return add(rest, left.map { it.title }, to, today).first
    }

    /** Every morning: what was left on a past day comes to today; done ones older than 60 days are let go. */
    fun rollover(board: Board, today: LocalDate): Board {
        val t = today.toString()
        val keep = today.minusDays(60).toString()
        val moved = board.quick.mapNotNull {
            when {
                it.done && it.date < keep -> null
                !it.done && it.date < t -> it.copy(date = t)
                else -> it
            }
        }
        return if (moved == board.quick) board else board.copy(quick = moved)
    }

    /**
     * "Let it learn": the big cells a day follow what the user really finishes, the average of done cells on the days
     * they worked in the last 4 weeks (at least 10 such days), rounded, between 2 and 5.
     */
    fun learned(board: Board, today: LocalDate): Int? {
        val from = today.minusDays(28).toString()
        val t = today.toString()
        val perDay = board.tasks.flatMap { it.steps }.filter { it.done && it.date != null && it.date >= from && it.date < t }.groupingBy { it.date!! }.eachCount()
        if (perDay.size < 10) return null
        return Math.round(perDay.values.average()).toInt().coerceIn(2, SLOTS_PER_DAY)
    }

    fun learn(board: Board, today: LocalDate): Board {
        if (!board.settings.bigLearn) return board
        val n = learned(board, today) ?: return board
        return if (n == board.settings.bigPerDay) board else board.copy(settings = board.settings.copy(bigPerDay = n))
    }
}
