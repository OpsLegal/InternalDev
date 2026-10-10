package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import kotlinx.serialization.Serializable

/** A past conversation with the assistant, kept when the user clears the chat, for the search. */
@Serializable
data class PastChat(val id: String, val at: String, val title: String, val lines: List<String>)

/**
 * One search for everything the assistant knows: tasks and project steps, projects (with their latest news), what
 * arrived (messages, emails), past conversations, the shopping list and routines. Every word typed must appear
 * (accents and case don't matter); the table's own items come first.
 */
object Search {
    /** [open]: "cell:<stepId>:<date>", "project:<name>", "bell", "cart", "routines", "expenses" or "past:<id>". */
    data class Hit(val kind: String, val icon: String, val title: String, val sub: String, val open: String, val score: Int)

    fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun all(board: Board, past: List<PastChat>, query: String): List<Hit> {
        val words = fold(query).split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        fun score(vararg parts: String?): Int {
            val t = fold(parts.filterNotNull().joinToString(" "))
            if (!words.all { t.contains(it) }) return 0
            return words.sumOf { w -> t.windowed(w.length).count { it == w } } + if (fold(parts.firstOrNull().orEmpty()).contains(words.joinToString(" "))) 5 else 0
        }
        val out = mutableListOf<Hit>()
        board.tasks.filter { !it.isProject && !it.errands }.forEach { t ->
            val st = t.steps.firstOrNull { !it.closed } ?: t.steps.lastOrNull() ?: return@forEach
            val n = score(t.title, t.description, t.intention, t.where)
            if (n > 0) out += Hit("Task", "☑️", t.title, listOfNotNull(st.date, "done".takeIf { st.done }, t.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                "cell:${st.id}:${st.date.orEmpty()}", n + 2)
        }
        board.projects.forEach { p ->
            val steps = BoardOps.projectTask(board, p.name)?.steps?.filter { it.outcome == null }.orEmpty()
            val n = score(p.name, p.intention, p.notes, steps.joinToString(" ") { it.title }, p.history.joinToString(" "))
            if (n > 0) out += Hit(if (Projects.isIdea(board, p)) "Idea" else "Project", "📁", p.name,
                listOfNotNull(p.intention.takeIf { it.isNotBlank() }, p.history.lastOrNull()?.let { "latest: $it" }).joinToString(" · "), "project:${p.name}", n + 3)
            steps.forEach { st ->
                val m = score(st.title, st.description)
                if (m > 0) out += Hit("Step", "▸", "${p.name} · ${st.title}", listOfNotNull(st.date, "done".takeIf { st.done }).joinToString(" · "), "cell:${st.id}:${st.date.orEmpty()}", m + 2)
            }
        }
        board.updates.forEach { u ->
            val n = score(u.title, u.from, u.summary, u.text, u.thread.joinToString(" ") { it.text })
            if (n > 0) out += Hit(u.source.replaceFirstChar { it.uppercase() }, if (u.source in Updates.EMAIL) "✉️" else "💬",
                u.title.ifBlank { u.summary.take(50) }, "${u.from} · ${u.summary}", "bell", n)
        }
        past.forEach { c ->
            val n = score(c.title, c.lines.joinToString(" "))
            val hit = c.lines.firstOrNull { l -> words.any { fold(l).contains(it) } } ?: c.lines.firstOrNull().orEmpty()
            if (n > 0) out += Hit("Conversation", "🗨️", c.title, "${c.at} · ${hit.take(90)}", "past:${c.id}", n)
        }
        board.buy.forEach { b -> score(b.text).takeIf { it > 0 }?.let { out += Hit("To buy", "🛒", b.text, if (b.done) "bought" else "on the list", "cart", it) } }
        board.routines.forEach { r -> score(r.title).takeIf { it > 0 }?.let { out += Hit("Routine", "🔁", r.title, "${r.days.size}× a week", "routines", it) } }
        // Documents and expenses, where they are kept.
        (board.projects.map { Triple(it.docs, it.expenses, it.name to "project:${it.name}") } +
            board.tasks.filter { !it.isProject }.map { t -> Triple(t.docs, t.expenses, t.title to (t.steps.firstOrNull { !it.closed } ?: t.steps.firstOrNull())?.let { "cell:${it.id}:${it.date.orEmpty()}" }.orEmpty()) })
            .forEach { (docs, exps, place) ->
                docs.forEach { d -> score(d.name, d.todo.joinToString(" ")).takeIf { it > 0 }?.let { out += Hit("Document", "📄", d.name, "with ${place.first}", place.second, it + 1) } }
                exps.forEach { e -> score(e.vendor, e.category, e.note, e.task).takeIf { it > 0 }?.let { out += Hit("Expense", "💲", "${e.vendor.ifBlank { e.category }} · ${Paperwork.money(e.amount)}", "${e.date} · ${place.first}", "expenses", it) } }
            }
        board.expenses.forEach { e -> score(e.vendor, e.category, e.note).takeIf { it > 0 }?.let { out += Hit("Expense", "💲", "${e.vendor.ifBlank { e.category }} · ${Paperwork.money(e.amount)}", e.date, "expenses", it) } }
        return out.sortedByDescending { it.score }.take(30)
    }

    /** The rules matching [query] (every word), with their place in the list. */
    fun rules(board: Board, query: String): List<Int> {
        val words = fold(query).split(Regex("\\s+")).filter { it.length >= 2 }
        val sorted = board.rules.sortedBy { it.order }
        return sorted.indices.filter { i -> words.isEmpty() || words.all { fold(sorted[i].text).contains(it) } }
    }
}
