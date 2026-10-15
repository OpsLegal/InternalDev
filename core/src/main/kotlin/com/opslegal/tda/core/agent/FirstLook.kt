package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Updates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * The first look: right after email or messages connect, the last 30 days become one list to tick: projects in the
 * making (grouped from several emails and messages), things the user was asked to do, bills to pay. The AI reads and
 * groups; nothing is added until the user taps (one item, or all). Nothing is added twice: a project that looks like
 * one the user has asks to join it or keep it separate; a task or a bill already there starts unticked.
 */
object FirstLook {
    data class P(val name: String, val from: String, val deadline: LocalDate?, val intention: String, val steps: List<String>)
    data class T(val title: String, val from: String, val project: String, val date: LocalDate?)
    data class B(val vendor: String, val amount: Double?, val due: LocalDate?, val category: String, val note: String, val from: String)
    data class Found(val projects: List<P>, val tasks: List<T>, val bills: List<B>, val read: Int, val skipped: Int) {
        val isEmpty: Boolean get() = projects.isEmpty() && tasks.isEmpty() && bills.isEmpty()
    }

    const val MAX_ITEMS = 80

    fun prompt(board: Board, items: List<Incoming>, today: LocalDate): String = buildString {
        appendLine("You help a new Docket 5 user (5 cells a day) get organized from their last 30 days of email and messages.")
        appendLine("Today is $today (${today.dayOfWeek.name.lowercase()}).")
        appendLine("DATES (copy from here): " + (0L..60L).joinToString(", ") { today.plusDays(it).let { d -> "${d.dayOfWeek.name.take(3).lowercase()} $d" } })
        appendLine()
        append(AgentTools.describe(board, today, 21))
        appendLine()
        appendLine("THE LAST 30 DAYS (id | source | from | date | text):")
        items.forEach { appendLine("${it.id} | ${it.source} | ${it.from.take(60)} | ${it.at.take(10)} | ${it.text.replace('\n', ' ').take(300)}") }
        appendLine()
        appendLine("Find only what still waits for the user (skip newsletters, ads, receipts of things paid, thanks, anything done or answered):")
        appendLine("- PROJECTS in the making: one subject spread over several items (a renovation, a file, a trip, a deal): a short name, where it")
        appendLine("  comes from (\"6 emails with Marc Bélanger · last Oct 12\"), a deadline when one is said, the intention in one sentence, and 2 to 6")
        appendLine("  steps in their order, each a concrete action of about 1.5 h or less.")
        appendLine("- TASKS: one thing the user was asked to do (or promised), not part of a project above; a project name only when it belongs")
        appendLine("  to one the user already has; a date only when one was asked.")
        appendLine("- BILLS the user must pay (electricity, phone, insurance, taxes, a supplier): who, total, due date, category among ${Bills.categories.joinToString()}.")
        appendLine("Never propose what the table, the projects or the calendar above already cover. Appointments are not tasks: leave them out.")
        appendLine("Use the language of the items for names and steps.")
        appendLine("""Reply with only {"projects":[{"name":"","from":"","deadline":"YYYY-MM-DD or empty","intention":"","steps":[""]}],"tasks":[{"title":"","from":"","project":"","date":""}],""")
        appendLine(""""bills":[{"vendor":"","amount":0,"due":"YYYY-MM-DD","category":"","note":"","from":""}],"skipped":<how many items needed nothing>}""")
    }

    fun parse(reply: String, read: Int, today: LocalDate): Found {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        val o: JsonObject = (if (start < 0 || end <= start) null else runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull())
            ?: return Found(emptyList(), emptyList(), emptyList(), read, read)
        fun JsonObject.s(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
        fun day(s: String) = runCatching { LocalDate.parse(s) }.getOrNull()?.takeIf { !it.isBefore(today) }
        fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Found(
            list("projects").mapNotNull { p ->
                val steps = (p["steps"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()?.trim()?.takeIf { s -> s.isNotBlank() } }.take(8)
                if (p.s("name").isBlank() || steps.isEmpty()) null else P(p.s("name").take(60), p.s("from").take(120), day(p.s("deadline")), p.s("intention").take(200), steps)
            }.take(6),
            list("tasks").mapNotNull { t -> t.s("title").takeIf { it.isNotBlank() }?.let { T(it.take(80), t.s("from").take(120), t.s("project"), day(t.s("date"))) } }.take(12),
            list("bills").mapNotNull { b ->
                b.s("vendor").takeIf { it.isNotBlank() }?.let {
                    B(it.take(40), b.s("amount").replace(",", ".").replace(Regex("[^0-9.]"), "").toDoubleOrNull(), day(b.s("due")),
                        b.s("category").takeIf { c -> c in Bills.categories } ?: Bills.guessCategory(it), b.s("note").take(80), b.s("from").take(120))
                }
            }.take(10),
            read, o.s("skipped").toIntOrNull() ?: 0,
        )
    }

    private val STOP = setOf("with", "from", "about", "this", "that", "their", "your", "pour", "avec", "dans", "chez", "send", "call", "check", "faire")
    private fun words(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOP }

    /** The running project this one looks like (a shared name word of 5+ letters, or half its words), or null. */
    fun lookalikeProject(board: Board, name: String): Project? {
        val a = words(name)
        return board.projects.filter { !Projects.isIdea(board, it) }.firstOrNull { p ->
            val b = words(p.name); val common = a.filter { it in b }
            common.any { it.length >= 5 } || (common.isNotEmpty() && common.size.toDouble() / maxOf(a.size, b.size) >= 0.5)
        }
    }

    /** An open cell already about the same thing (two name words in common), or null. */
    fun lookalikeCell(board: Board, title: String): String? {
        val a = words(title); if (a.size < 2) return null
        for (t in board.tasks) for (s in t.steps) {
            if (s.closed) continue
            val name = Planner.cellTitle(t, s); val b = words(name)
            if (a.count { it in b } >= 2) return name
        }
        return null
    }

    fun billThere(board: Board, vendor: String): Boolean = Bills.open(board).any { it.vendor.equals(vendor, ignoreCase = true) }

    private fun noteLine(from: String, note: String) = "Found in your emails and messages: $from." + if (note.isNotBlank()) " Your note: $note" else ""

    /** A new project, its steps paced to its deadline; with [joinInto], its steps go to that project instead (the ones it doesn't have). */
    fun addProject(board: Board, p: P, joinInto: String?, note: String, today: LocalDate): Board {
        if (joinInto != null) {
            val host = BoardOps.findProject(board, joinInto) ?: return board
            val holder = BoardOps.projectTask(board, host.name)
            val have = holder?.steps.orEmpty().map { it.title.lowercase() }.toSet()
            val open = holder?.steps.orEmpty().filter { !it.closed }.map { BoardOps.EditedStep(it.id, it.title) }
            val more = p.steps.filter { it.lowercase() !in have }.map { BoardOps.EditedStep(null, it) }
            val withHistory = BoardOps.saveProject(board, host.copy(history = host.history + "$today · ${noteLine(p.from, note)}"), host.name)
            return Planner.plan(Projects.save(withHistory, withHistory.projects.first { it.name == host.name }, host.name, open + more, today).board, today).board
        }
        var name = p.name; var n = 2
        while (BoardOps.findProject(board, name) != null) name = "${p.name} (${n++})"
        val project = Project(name, deadline = p.deadline?.toString(), notes = noteLine(p.from, note), intention = p.intention)
        return Planner.plan(Projects.save(board, project, null, p.steps.map { BoardOps.EditedStep(null, it) }, today).board, today).board
    }

    /** A task in the next free cell, or on the day its note (or the request) names. */
    fun addTask(board: Board, t: T, note: String, today: LocalDate): Board {
        val project = t.project.takeIf { it.isNotBlank() }?.let { BoardOps.findProject(board, it)?.name }.orEmpty()
        val spec = BoardOps.NewTask(t.title, description = "From ${t.from}." + if (note.isNotBlank()) " Your note: $note" else "", project = project)
        val day = Updates.dayInNote(note, today) ?: t.date
        return if (day != null) BoardOps.addTaskOn(board, spec, day, today).first else Planner.plan(BoardOps.add(board, spec, today).board, today).board
    }

    fun addBill(board: Board, b: B, note: String, today: LocalDate): Board = Bills.add(board,
        Bill(BoardOps.newId(), b.vendor, b.amount, (b.due ?: today.plusDays(7)).toString(), b.category, listOf(b.note, note).filter { it.isNotBlank() }.joinToString(" · "), "email"))
}
