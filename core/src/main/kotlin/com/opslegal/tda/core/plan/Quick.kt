package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.QuickThing
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import java.time.LocalDate

/**
 * Quick things: 15 minutes or less (a call, a text, an appointment, a quick email). They never take a cell and nothing
 * waits on them, so they live in a pool and only have to be done before their deadline. Each day the user's number of
 * them is picked for the ⚡ line (closest deadline, then most impact, then the most demanding first); the user can swap
 * any of them. One with a set day stays on that day. Left undone: back to the pool, never red.
 */
object Quick {
    /**
     * Adds each title: on [day] when one is given (today at the earliest, or the next day with room), else to the pool.
     * Returns the board and the days used (null = the pool).
     */
    fun add(board: Board, titles: List<String>, day: LocalDate?, today: LocalDate, due: String = "", impact: Int = 2, effort: Int = 1, area: String = ""): Pair<Board, List<LocalDate?>> {
        var b = board
        val days = mutableListOf<LocalDate?>()
        for (t in titles.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (day == null) {
                b = b.copy(quick = b.quick + QuickThing(BoardOps.newId(), t, "", due = due, impact = impact, effort = effort, area = area))
                days += null; continue
            }
            var d = if (day.isBefore(today)) today else day
            while (b.quick.count { it.date == d.toString() && !it.done } >= b.settings.quickPerDay.coerceAtLeast(1) + 3) d = d.plusDays(1)
            val q = QuickThing(BoardOps.newId(), t, d.toString(), due = due, impact = impact, effort = effort, area = area)
            b = b.copy(quick = b.quick + q)
            // Set for today: it joins today's ⚡ line at once.
            if (d == today) b.quickPick?.takeIf { it.date == today.toString() }?.let { p -> b = b.copy(quickPick = p.copy(ids = p.ids + q.id)) }
            days += d
        }
        return b to days
    }

    /** Ticking keeps today's pick first, so a done one stays on today's line (and nothing new slips in). */
    fun tick(board: Board, id: String, done: Boolean, today: LocalDate = LocalDate.now()): Board =
        keep(board, today).let { b -> b.copy(quick = b.quick.map { if (it.id == id) it.copy(done = done, doneOn = if (done) today.toString() else "", check = false) else it }) }

    /** Written down on my mind: into the pool, its area guessed, marked to check (the user confirms it on the page). */
    fun jot(board: Board, texts: List<String>): Board = board.copy(quick = board.quick + texts.map { it.trim() }.filter { it.isNotEmpty() }.map {
        QuickThing(BoardOps.newId(), it, area = Areas.guess(board, it), effort = if (Labels.sizeOf(it) == "big") 3 else 1, check = true)
    })

    fun remove(board: Board, id: String): Board = board.copy(quick = board.quick.filterNot { it.id == id })

    fun edit(board: Board, id: String, change: (QuickThing) -> QuickThing): Board = board.copy(quick = board.quick.map { if (it.id == id) change(it).copy(check = false) else it })

    /** The order the pick follows: a close deadline weighs most, then how much it matters, then the most demanding first. */
    fun score(q: QuickThing, today: LocalDate): Int {
        val days = if (q.due.isBlank()) 60 else java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(q.due)).toInt()
        return (if (q.due.isBlank()) 0 else maxOf(0, 30 - days) * 4) + q.impact * 10 + q.effort * 3
    }

    fun pool(board: Board): List<QuickThing> = board.quick.filter { !it.done && it.date.isBlank() }

    /** Today's ⚡ line: the kept pick when it is today's, else the set-today ones and the best of the pool. */
    fun todayIds(board: Board, today: LocalDate): List<String> {
        val t = today.toString()
        board.quickPick?.takeIf { it.date == t }?.let { p -> return p.ids.filter { id -> board.quick.any { it.id == id } } }
        val fixed = board.quick.filter { it.date == t }.map { it.id }
        val picks = pool(board).sortedByDescending { score(it, today) }.take(maxOf(0, board.settings.quickPerDay - fixed.size)).map { it.id }
        return fixed + picks
    }

    /** Keeps today's pick (so a swap holds), or picks again when [again]. */
    fun keep(board: Board, today: LocalDate, again: Boolean = false): Board {
        val b = if (again) board.copy(quickPick = null) else board
        return b.copy(quickPick = com.opslegal.tda.core.model.QuickPick(today.toString(), todayIds(b, today)))
    }

    /** Not today: [id] leaves today's line, replaced by [with] or by the next best of the pool. */
    fun swap(board: Board, id: String, today: LocalDate, with: String? = null): Board {
        val b = keep(board, today)
        val ids = b.quickPick!!.ids.toMutableList()
        val i = ids.indexOf(id)
        val next = with ?: pool(b).filter { it.id !in ids }.maxByOrNull { score(it, today) }?.id
        if (i < 0) { if (with != null && with !in ids) ids += with } else if (next != null) ids[i] = next else ids.removeAt(i)
        return b.copy(quickPick = b.quickPick.copy(ids = ids))
    }

    /** All done: a few more for today. */
    fun more(board: Board, today: LocalDate): Board {
        val b = keep(board, today)
        val ids = b.quickPick!!.ids
        return b.copy(quickPick = b.quickPick.copy(ids = ids + pool(b).filter { it.id !in ids }.sortedByDescending { score(it, today) }.take(board.settings.quickPerDay).map { it.id }))
    }

    /** A day's ⚡ line: today the pick; another day what is set for it; a past day what was done that day. */
    fun onDay(board: Board, date: LocalDate, today: LocalDate): List<QuickThing> = when {
        date == today -> todayIds(board, today).mapNotNull { id -> board.quick.firstOrNull { it.id == id } }
        date.isBefore(today) -> board.quick.filter { it.done && (it.doneOn == date.toString() || (it.doneOn.isBlank() && it.date == date.toString())) }
        else -> board.quick.filter { it.date == date.toString() }
    }

    /** What is left on [from] goes to [to] (or the next day with room). */
    fun moveLeft(board: Board, from: LocalDate, to: LocalDate, today: LocalDate): Board {
        val left = onDay(board, from, today).filter { !it.done }
        val ids = left.map { it.id }.toSet()
        val rest = board.copy(quick = board.quick.map { if (it.id in ids) it.copy(date = to.toString()) else it },
            quickPick = board.quickPick?.let { p -> p.copy(ids = p.ids - ids) })
        return rest
    }

    /** Every morning: what was set on a past day and not done goes back to the pool; done ones older than 60 days are let go. */
    fun rollover(board: Board, today: LocalDate): Board {
        val t = today.toString()
        val keep = today.minusDays(60).toString()
        val moved = board.quick.mapNotNull {
            when {
                it.done && (it.doneOn.ifBlank { it.date }) < keep -> null
                !it.done && it.date.isNotBlank() && it.date < t -> it.copy(date = "")
                else -> it
            }
        }
        // The old "On my mind" notes join the pool once.
        val b = if (board.mind.isEmpty()) board else jot(board, board.mind.map { it.text }).copy(mind = emptyList())
        return if (moved == board.quick && b === board) board else b.copy(quick = moved + b.quick.drop(board.quick.size))
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
