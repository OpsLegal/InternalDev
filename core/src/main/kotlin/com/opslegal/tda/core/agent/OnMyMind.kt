package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Shopping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * On my mind: what the user wrote down becomes tasks, project steps, a Quick things cell, ideas or things to buy,
 * ordered by what matters to them. Nothing stays a list. New cells only take free cells: what is already in the
 * table or the calendar never moves for them.
 */
object OnMyMind {

    enum class Kind { TASK, STEP, QUICK, IDEA, BUY, DROP }

    data class Sorted(
        val text: String, val kind: Kind, val title: String, val project: String = "", val rank: Int = 99,
        val due: String? = null, val work: Boolean = true,
    )

    /** Small things per Quick things cell. */
    const val QUICK_PER_CELL = 6

    /** After this hour, nothing new lands today: the rest of the day is already spoken for. */
    const val LAST_HOUR_TODAY = 14

    suspend fun sort(provider: LlmProvider, board: Board, today: LocalDate): List<Sorted> {
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt(board, today))), emptyList()).text
        return parse(reply, board)
    }

    fun prompt(board: Board, today: LocalDate): String = buildString {
        appendLine("You organize what a Docket 5 user wrote down. Today is $today (${today.dayOfWeek.name.lowercase()}).")
        append(AgentTools.describe(board, today, 14))
        appendLine()
        appendLine("WHAT THEY WROTE (one per line):")
        board.mind.forEachIndexed { i, m -> appendLine("${i + 1}. ${m.text}") }
        appendLine()
        appendLine("For each line, what it becomes:")
        appendLine("- \"step\": it belongs to one of their projects above (\"project\" exactly as named).")
        appendLine("- \"task\": one focused piece of work, about 1.5 h or less. If it is something they put off, make the title a")
        appendLine("  small concrete first step (\"Open the file and write the outline\").")
        appendLine("- \"quick\": a 5 to 15 minute thing (a call, a short email, a form); they are grouped into one cell.")
        appendLine("- \"idea\": vague or someday. \"buy\": something to buy. \"drop\": not worth doing (rare, be honest).")
        appendLine("\"work\": true for work, false for personal life (family, home, health, leisure).")
        appendLine("\"rank\": 1 = do first: deadlines, what blocks a project, then their values and weights.")
        appendLine("\"due\": only when a date is said (YYYY-MM-DD).")
        appendLine("""Reply with only {"items":[{"line":1,"kind":"task","title":"...","project":"","rank":1,"due":"","work":true}]}""")
    }

    fun parse(reply: String, board: Board): List<Sorted> {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        val root = if (start >= 0 && end > start) runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() else null
        val found = (root?.get("items") as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val line = o.int("line") ?: return@mapNotNull null
            val item = board.mind.getOrNull(line - 1) ?: return@mapNotNull null
            Sorted(
                text = item.text,
                kind = Kind.entries.firstOrNull { it.name == o.str("kind").uppercase() } ?: Kind.TASK,
                title = o.str("title").ifBlank { item.text },
                project = o.str("project"),
                rank = o.int("rank") ?: 99,
                due = o.str("due").takeIf { runCatching { LocalDate.parse(it) }.isSuccess },
                work = o["work"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }.distinctBy { it.text }
        // Anything the AI skipped still becomes a task: nothing written down is lost.
        val missed = board.mind.filter { m -> found.none { it.text == m.text } }.map { Sorted(it.text, Kind.TASK, it.text) }
        return (found + missed).sortedBy { it.rank }
    }

    /**
     * Places the sorted items and empties the list. New cells start today when there is still time and room
     * (otherwise tomorrow) and only fill free cells, so the day already planned does not move.
     */
    fun place(board: Board, items: List<Sorted>, now: LocalDateTime): Board {
        val today = now.toLocalDate()
        val start = if (now.hour < LAST_HOUR_TODAY) today else today.plusDays(1)
        var b = board
        val quick = mutableListOf<Sorted>()
        for (it in items.sortedBy { it.rank }) {
            when (it.kind) {
                Kind.DROP -> Unit
                Kind.BUY -> b = Shopping.add(b, listOf(it.title), work = it.work, needBy = it.due).first
                Kind.IDEA -> if (BoardOps.findProject(b, it.title) == null) {
                    b = b.copy(projects = b.projects + Project(BoardOps.uniqueProjectName(b, it.title.take(40)), notes = it.text))
                }
                Kind.QUICK -> quick += it
                Kind.TASK, Kind.STEP -> {
                    val project = if (it.kind == Kind.STEP) BoardOps.findProject(b, it.project)?.name.orEmpty() else ""
                    val added = BoardOps.add(b, BoardOps.NewTask(it.title, description = it.text.takeIf { t -> t != it.title }.orEmpty(), project = project, deadline = it.due), today)
                    b = startFrom(added.board, added.task.id, added.steps.map { s -> s.id }.toSet(), start, personal = !it.work && project.isEmpty())
                }
            }
        }
        quick.chunked(QUICK_PER_CELL).forEach { batch ->
            val added = BoardOps.add(
                b,
                BoardOps.NewTask("Quick things (${batch.size})", description = batch.joinToString("\n") { "• ${it.title}" }, effort = Effort.LIGHT),
                today,
            )
            b = startFrom(added.board, added.task.id, added.steps.map { it.id }.toSet(), start, personal = batch.none { it.work })
        }
        return Planner.plan(b.copy(mind = emptyList()), today).board
    }

    private fun startFrom(board: Board, taskId: String, stepIds: Set<String>, start: LocalDate, personal: Boolean): Board =
        BoardOps.updateTask(board, taskId) { t ->
            t.copy(
                personal = t.personal || personal,
                steps = t.steps.map { s -> if (s.id in stepIds) s.copy(notBefore = start.toString()) else s },
            )
        }

    private fun JsonObject.str(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
    private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}
