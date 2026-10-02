package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.BuyItem
import com.opslegal.tda.core.model.Effort
import java.time.LocalDate

/** The To buy list: one list for home and work. Things never take a cell each; a trip is one Errands cell. */
object Shopping {

    fun open(board: Board): List<BuyItem> = board.buy.filter { !it.done }

    /** Worth a trip: 5 things or more, or one needed within 2 days, and no trip planned for them yet. */
    fun due(board: Board, today: LocalDate): Boolean {
        val waiting = open(board).filter { it.errand == null }
        return waiting.size >= 5 || waiting.any { it.needBy?.let { d -> !LocalDate.parse(d).isAfter(today.plusDays(2)) } == true }
    }

    /** Adds things (one per text), skipping what is already on the list. Returns the board and what was added. */
    fun add(board: Board, texts: List<String>, work: Boolean = false, needBy: String? = null): Pair<Board, List<String>> {
        var list = board.buy
        val added = mutableListOf<String>()
        for (raw in texts) {
            val text = raw.trim().trimEnd('.')
            if (text.isEmpty() || list.any { !it.done && it.text.equals(text, ignoreCase = true) }) continue
            list = list + BuyItem(BoardOps.newId(), text, work, needBy?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess })
            added += text
        }
        return board.copy(buy = list) to added
    }

    fun setDone(board: Board, id: String, done: Boolean): Board = board.copy(buy = board.buy.map { if (it.id == id) it.copy(done = done) else it })

    fun remove(board: Board, id: String): Board = board.copy(buy = board.buy.filterNot { it.id == id })

    fun clearBought(board: Board): Board = board.copy(buy = board.buy.filterNot { it.done })

    /**
     * One Errands cell for everything still to buy: on [day], or the day before the earliest "needed by", or the
     * first free cell. Returns the board (not yet planned) and the task id, or null when there is nothing to buy.
     */
    fun planTrip(board: Board, today: LocalDate, day: LocalDate? = null): Pair<Board, String>? {
        val items = open(board).filter { it.errand == null }
        if (items.isEmpty()) return null
        val spec = BoardOps.NewTask(
            title = "Errands · ${items.size} ${if (items.size > 1) "things" else "thing"}",
            description = "To buy: " + items.joinToString { it.text } + ".",
            effort = Effort.LIGHT,
        )
        val soon = items.mapNotNull { it.needBy?.let(LocalDate::parse) }.minOrNull()
        val target = day ?: soon?.minusDays(1)?.let { if (it.isBefore(today)) today else it }
        val (withTask, task) = if (target != null) BoardOps.addTaskOn(board, spec, target, today).let { it.first to it.second }
        else BoardOps.addTask(board, spec, today)
        val ids = items.map { it.id }.toSet()
        val next = withTask.copy(
            tasks = withTask.tasks.map { if (it.id == task.id) it.copy(errands = true) else it },
            buy = withTask.buy.map { if (it.id in ids) it.copy(errand = task.id) else it },
        )
        return next to task.id
    }
}
