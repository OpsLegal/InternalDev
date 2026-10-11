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

    /**
     * Instant matching, on the phone (never a request to the assistant): case and accents don't matter, and each word
     * typed only has to appear somewhere ("ops act" finds "OPS - ACT - WORKSHOPS", "beneva" finds "Bénéva").
     */
    fun matches(query: String, text: String): Boolean {
        val t = fold(text).replace(Regex("[-_’']"), " ")
        return fold(query).replace(Regex("[-_’']"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }.all { t.contains(it) }
    }

    /** The names matching [query], best first: the name starts with it, then one of its words does, then anywhere. */
    fun ranked(query: String, names: List<String>): List<String> {
        val q = fold(query).trim()
        if (q.isEmpty()) return names
        fun rank(n: String) = fold(n).let { f -> if (f.startsWith(q)) 0 else if (f.split(Regex("[^a-z0-9]+")).any { it.startsWith(q) }) 1 else 2 }
        return names.filter { matches(query, it) }.sortedBy { rank(it) }
    }

    /** A Find result with its day (null when it has none) and its group: "Coming up", "Other" or "Past". */
    data class Found(val kind: String, val icon: String, val title: String, val sub: String, val open: String, val date: String?, val group: String)

    /**
     * Find, from the header: the table, the phone's calendar and the rest, coming up first (soonest first), then
     * what has no day, then the past (latest first). A day typed ("friday", "oct 20") comes first, to jump to it.
     */
    fun find(board: Board, events: List<com.opslegal.tda.core.agent.CalendarEvent>, query: String, today: java.time.LocalDate): List<Found> {
        val words = fold(query).split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val steps = board.tasks.flatMap { it.steps }.associateBy { it.id }
        val hits = all(board, emptyList(), query).filter { it.kind != "Conversation" }.map { h ->
            val date = if (h.open.startsWith("cell:")) steps[h.open.split(":")[1]]?.date else null
            Triple(h, date, h.open)
        }.toMutableList()
        events.filter { e -> matches(query, "${e.title} ${e.location}") }.forEach { e ->
            val d = e.start.take(10)
            hits += Triple(Hit("Calendar", "📅", e.title, if (e.allDay) "all day" else e.start.drop(11).take(5), "day:$d", 0), d, "day:$d")
        }
        val out = mutableListOf<Found>()
        dayIn(query, today)?.let { d ->
            val n = board.tasks.sumOf { t -> t.steps.count { it.date == d.toString() && it.outcome == null } }
            out += Found("Day", "🗓️", "Go to that day", "$n cell${if (n == 1) "" else "s"} planned", "day:$d", d.toString(), "Coming up")
        }
        val t = today.toString()
        out += hits.filter { it.second != null && it.second!! >= t }.sortedBy { it.second }.map { (h, d) -> Found(h.kind, h.icon, h.title, h.sub, h.open, d, "Coming up") }
        out += hits.filter { it.second == null }.map { (h) -> Found(h.kind, h.icon, h.title, h.sub, h.open, null, "Other") }
        out += hits.filter { it.second != null && it.second!! < t }.sortedByDescending { it.second }.map { (h, d) -> Found(h.kind, h.icon, h.title, h.sub, h.open, d, "Past") }
        return out.take(40)
    }

    /** A day typed in Find: "friday", "demain", "next week", or a date like "oct 20" / "20 oct" / "2026-10-20". */
    fun dayIn(query: String, today: java.time.LocalDate): java.time.LocalDate? {
        Updates.dayInNote(query, today)?.let { return it }
        runCatching { return java.time.LocalDate.parse(query.trim()) }
        val q = fold(query)
        val months = listOf("jan", "feb|fev", "mar", "apr|avr", "may|mai", "jun|juin", "jul|juil", "aug|aou", "sep", "oct", "nov", "dec")
        val m = months.indexOfFirst { Regex("\\b($it)").containsMatchIn(q) }.takeIf { it >= 0 } ?: return null
        val day = Regex("\\b(\\d{1,2})\\b").find(q)?.groupValues?.get(1)?.toInt() ?: return null
        val d = runCatching { java.time.LocalDate.of(today.year, m + 1, day) }.getOrNull() ?: return null
        return if (d < today.minusMonths(6)) d.plusYears(1) else d
    }

    /** The rules matching [query] (every word), with their place in the list. */
    fun rules(board: Board, query: String): List<Int> {
        val words = fold(query).split(Regex("\\s+")).filter { it.length >= 2 }
        val sorted = board.rules.sortedBy { it.order }
        return sorted.indices.filter { i -> words.isEmpty() || words.all { fold(sorted[i].text).contains(it) } }
    }
}
