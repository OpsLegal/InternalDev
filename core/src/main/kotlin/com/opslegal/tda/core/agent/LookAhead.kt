package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Updates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * The look ahead: the next 60 days of the table, reviewed. The same thing planned twice is found on the phone (no AI,
 * instant) and offered to merge; the assistant adds a few improvements (a better day, a clearer title, one no longer
 * needed, a point to discuss). Nothing changes until the user taps.
 */
object LookAhead {
    const val DAYS = 60L

    data class Cell(val id: String, val title: String, val date: String?, val project: String)
    data class Dup(val keep: Cell, val other: Cell)

    /** [type]: merge (with [other]), move (to [date]), rename (to [title]), drop, note (to discuss). */
    data class Idea(val type: String, val step: String, val why: String, val other: String = "", val date: String? = null, val title: String = "")

    /** Every cell still to do in the next 60 days, and those not planned yet. */
    fun cells(board: Board, today: LocalDate): List<Cell> {
        val end = today.plusDays(DAYS).toString(); val from = today.toString()
        return board.tasks.flatMap { t ->
            t.steps.filter { !it.closed && (it.date == null || it.date in from..end) }.map { s ->
                Cell(s.id, Planner.cellTitle(t, s), s.date, if (t.isProject) t.title else "")
            }
        }
    }

    private fun own(c: Cell) = if (c.project.isNotBlank()) c.title.removePrefix(c.project).trimStart(' ', '·', '-', ':') else c.title
    private fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    /** The same thing planned twice: two cells whose own words match (two steps of one project are compared on their own titles). */
    fun duplicates(board: Board, today: LocalDate): List<Dup> {
        val list = cells(board, today)
        val used = mutableSetOf<String>()
        val out = mutableListOf<Dup>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]; val b = list[j]
            if (a.id in used || b.id in used || key(a.id, b.id) in board.lookNotSame) continue
            if (!Updates.sameThing(own(a), own(b))) continue
            // Keep the one in a project, else the one planned first.
            val keepA = when {
                a.project.isNotBlank() != b.project.isNotBlank() -> a.project.isNotBlank()
                else -> (a.date ?: "9999") <= (b.date ?: "9999")
            }
            out += if (keepA) Dup(a, b) else Dup(b, a)
            used += a.id; used += b.id
        }
        return out
    }

    fun prompt(board: Board, today: LocalDate, dups: List<Dup>): String = buildString {
        val list = cells(board, today)
        appendLine("Today is $today. Review the user's plan for the next $DAYS days (cells of about 1.5 h, at most 5 a day) and suggest improvements.")
        appendLine("CELLS (id | day or unplanned | project | title):")
        list.take(150).forEach { c -> appendLine("${c.id} | ${c.date ?: "unplanned"} | ${c.project.ifBlank { "-" }} | ${c.title}") }
        val deadlines = board.projects.filter { it.deadline != null }.map { "${it.name}: ${it.deadline}" }
        if (deadlines.isNotEmpty()) appendLine("PROJECT DEADLINES: ${deadlines.joinToString("; ")}")
        if (dups.isNotEmpty()) appendLine("ALREADY FOUND as duplicates (do not repeat): " + dups.joinToString("; ") { "${it.keep.id}+${it.other.id}" })
        appendLine("Suggest at most 8, only clearly useful ones, in the user's language:")
        appendLine("- merge: two cells that are the same thing in other words (\"step\" = the one to keep, \"other\" = the one to remove).")
        appendLine("- move: a cell to a better day (\"date\" YYYY-MM-DD): after its project deadline, a step before the one it needs, a day with too much.")
        appendLine("- rename: a vague or unclear title (\"title\" = a clear one starting with a verb, max 50 characters).")
        appendLine("- drop: a cell plainly no longer needed (a past event, the same as another already done). Only when obvious.")
        appendLine("- note: something to discuss (a project with no next step, a deadline at risk, a week overloaded). \"step\" = the cell it is about.")
        appendLine("\"why\" = one short sentence. Never invent ids. If nothing is worth it, return an empty list.")
        append("Reply with only {\"ideas\": [{\"type\": string, \"step\": string, \"other\": string|null, \"date\": string|null, \"title\": string|null, \"why\": string}]}.")
    }

    fun parse(reply: String, board: Board, today: LocalDate): List<Idea> {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        val open = cells(board, today).map { it.id }.toSet()
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        return runCatching { root["ideas"]!!.jsonArray }.getOrDefault(emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o.s("type") ?: return@mapNotNull null
            val step = o.s("step")?.takeIf { it in open } ?: return@mapNotNull null
            val idea = Idea(type, step, o.s("why").orEmpty(), o.s("other").orEmpty(), o.s("date"), o.s("title").orEmpty())
            when (type) {
                "merge" -> idea.takeIf { it.other in open && it.other != step && key(step, it.other) !in board.lookNotSame }
                "move" -> idea.takeIf { runCatching { !LocalDate.parse(it.date) .isBefore(today) }.getOrDefault(false) }
                "rename" -> idea.takeIf { it.title.isNotBlank() }?.let { it.copy(title = it.title.take(60)) }
                "drop", "note" -> idea
                else -> null
            }
        }.distinctBy { listOf(it.type, it.step, it.other) }.take(8)
    }

    /** Merges two cells into one: [keep] stays, with what [drop] said in its details; [drop] is cancelled (its place freed). */
    fun merge(board: Board, keep: String, drop: String, today: LocalDate): Board {
        val (_, d) = BoardOps.findStep(board, drop) ?: return board
        if (BoardOps.findStep(board, keep) == null) return board
        val noted = BoardOps.mapStep(board, keep) { s ->
            val add = listOf("Merged: ${d.title}", d.description).filter { it.isNotBlank() }.joinToString(" · ")
            s.copy(description = listOf(s.description, add).filter { it.isNotBlank() }.joinToString("\n"))
        }
        return BoardOps.cancelStep(noted, drop, today)
    }

    /** "Not the same": never offered again. */
    fun notSame(board: Board, a: String, b: String): Board = board.copy(lookNotSame = (board.lookNotSame + key(a, b)).distinct())

    fun apply(board: Board, idea: Idea, today: LocalDate): Board? = when (idea.type) {
        "merge" -> merge(board, idea.step, idea.other, today)
        "move" -> idea.date?.let { BoardOps.moveStep(board, idea.step, LocalDate.parse(it)) }
        "rename" -> BoardOps.renameStep(board, idea.step, idea.title)
        "drop" -> BoardOps.cancelStep(board, idea.step, today)
        else -> board
    }

    fun titleOf(board: Board, id: String): String = BoardOps.findStep(board, id)?.let { (t, s) -> Planner.cellTitle(t, s) } ?: ""
}
