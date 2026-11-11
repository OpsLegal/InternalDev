package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Asset
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Doc
import com.opslegal.tda.core.model.Expense
import java.time.LocalDate

/**
 * 🏷 My things: what the user owns (car, home, rental unit, bike, watch, art…). Each one keeps its documents, expenses,
 * upkeep reminders and tasks, like a project that never ends. By rules, on the phone, no AI.
 */
object Assets {
    val TYPES = listOf("car" to "🚗 Car", "bike" to "🚲 Bike", "home" to "🏠 Home", "rental" to "🏢 Rental unit", "watch" to "⌚ Watch", "art" to "🖼 Art", "other" to "📦 Other")
    fun icon(a: Asset): String = TYPES.firstOrNull { it.first == a.type }?.second?.substringBefore(' ') ?: "📦"
    fun label(a: Asset): String = "${icon(a)} ${a.name}"

    fun save(board: Board, a: Asset): Board =
        board.copy(assets = if (board.assets.any { it.id == a.id }) board.assets.map { if (it.id == a.id) a else it } else board.assets + a)

    fun remove(board: Board, id: String): Board = board.copy(assets = board.assets.filter { it.id != id })

    fun addDoc(board: Board, id: String, doc: Doc): Board = board.copy(assets = board.assets.map { if (it.id == id) it.copy(docs = it.docs + doc) else it })

    fun addExpense(board: Board, id: String, e: Expense): Board = board.copy(assets = board.assets.map { if (it.id == id) it.copy(expenses = it.expenses + e) else it })

    /** Spent on it this year. */
    fun spent(a: Asset, today: LocalDate): Double = a.expenses.filter { it.date.take(4) == today.year.toString() }.sumOf { it.amount }

    /** Its open tasks. */
    fun tasks(board: Board, a: Asset) = board.tasks.filter { it.asset == a.id && it.steps.any { s -> !s.closed } }

    /**
     * Upkeep, by rule: a care due within 7 days becomes a quick thing with that deadline (once), and its next date moves
     * on by its interval.
     */
    fun upkeepDue(board: Board, today: LocalDate): Board {
        var b = board
        for (a in board.assets) for (u in a.upkeep) {
            val next = runCatching { LocalDate.parse(u.next) }.getOrNull() ?: continue
            if (next.isAfter(today.plusDays(7)) || u.made == u.next) continue
            b = Quick.add(b, listOf("🔧 ${u.what} · ${a.name}"), null, today, due = u.next).first
            b = b.copy(quick = b.quick.mapIndexed { i, q -> if (i == b.quick.lastIndex) q.copy(asset = a.id) else q })
            val moved = next.plusMonths(u.every.coerceAtLeast(1).toLong()).toString()
            b = b.copy(assets = b.assets.map { x -> if (x.id != a.id) x else x.copy(upkeep = x.upkeep.map { if (it.id == u.id) it.copy(made = u.next, next = moved) else it }) })
        }
        return b
    }

    /** The thing a page or a store surely belongs to: its name's first word on it, or a car word for the only car. */
    fun guess(board: Board, text: String): Asset? {
        val t = " ${Paperwork.fold(text)} "
        board.assets.firstOrNull { a -> Paperwork.fold(a.name).split(Regex("[^a-z0-9]+")).firstOrNull { it.length >= 3 }?.let { t.contains(" $it") } == true }?.let { return it }
        val cars = board.assets.filter { it.type == "car" }
        if (cars.size == 1 && Regex("\\b(auto|car|vehicle|voiture|vehicule|saaq|plate|plaque|tire|pneu)\\b").containsMatchIn(t)) return cars.single()
        return null
    }
}
