package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Value

/** "I am…" tags: as many as fit the person. Each attribute weighs the highest of its tags; hand-set weights win. */
object Tags {
    private fun w(home: Int, admin: Int, career: Int, money: Int, invest: Int, rel: Int, health: Int, joy: Int, learning: Int = 0) =
        linkedMapOf("Home" to home, "Admin" to admin, "Career" to career, "Money" to money, "Invest" to invest,
            "Relations" to rel, "Health" to health, "Joy" to joy).also { if (learning > 0) it["Learning"] = learning }

    val presets: Map<String, Map<String, Int>> = linkedMapOf(
        "Parent" to w(2, 2, 2, 2, 1, 3, 2, 2),
        "Founder" to w(1, 1, 3, 2, 3, 2, 2, 1),
        "Investor" to w(1, 2, 1, 3, 3, 1, 1, 1),
        "Lawyer" to w(1, 2, 3, 3, 1, 2, 2, 1, learning = 2),
        "Employee" to w(1, 1, 3, 2, 1, 2, 2, 2, learning = 2),
        "Passionate" to w(1, 1, 3, 1, 1, 2, 2, 3),
    )
    private val mins = mapOf("Parent" to mapOf("Relations" to 4))

    fun all(board: Board): Map<String, Map<String, Int>> = presets + board.customTags

    /** The attributes from the tags (Neutral without any), the user's own weights on top; meanings and minimums kept. */
    fun apply(board: Board): Board {
        val tags = board.tags.mapNotNull { all(board)[it] }
        val base = Gbn.profiles.getValue("gbn-lawyer").second.associateBy { it.name } + Gbn.profiles.getValue("gbn-neutral").second.associateBy { it.name }
        val names = mutableListOf("Home", "Admin", "Career", "Money", "Invest", "Relations", "Health", "Joy")
        if (tags.any { (it["Learning"] ?: 0) > 0 }) names.add(5, "Learning")
        val prev = board.values.associateBy { it.name }
        board.values.filter { it.name !in base }.forEach { if (it.name !in names) names += it.name }
        val values = names.map { n ->
            val b = base[n] ?: prev.getValue(n)
            val fromTags = if (tags.isEmpty()) 2 else maxOf(1, tags.maxOf { it[n] ?: 0 })
            val min = board.tags.mapNotNull { mins[it]?.get(n) }.maxOrNull()
            Value(n, (board.adjust[n] ?: fromTags).coerceIn(0, 3), prev[n]?.meaning?.ifBlank { null } ?: b.meaning,
                prev[n]?.minPerWeek ?: min, b.bucket.ifBlank { Gbn.GROUND })
        }
        return board.copy(values = values)
    }

    fun toggle(board: Board, tag: String): Board =
        apply(board.copy(tags = if (tag in board.tags) board.tags - tag else board.tags + tag, gbn = true))
}
