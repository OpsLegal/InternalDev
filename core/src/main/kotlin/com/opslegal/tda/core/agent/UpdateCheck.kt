package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.BoardOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * A check: what arrived on the phone since the last one goes to the user's own AI with their table,
 * and comes back as proposals. The AI only proposes; nothing changes before the user taps Apply.
 */
object UpdateCheck {

    /** At most this many items per check, newest first, so a check stays small and cheap. */
    const val MAX_ITEMS = 30

    suspend fun run(provider: LlmProvider, board: Board, items: List<Incoming>, today: LocalDate, now: String): List<Update> {
        if (items.isEmpty()) return emptyList()
        val batch = items.sortedByDescending { it.at }.take(MAX_ITEMS)
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt(board, batch, today))), emptyList()).text
        return parse(reply, batch, now)
    }

    fun prompt(board: Board, items: List<Incoming>, today: LocalDate): String = buildString {
        val c = board.checks
        appendLine("You check what arrived on a Docket 5 user's phone and propose changes to their table (5 cells a day).")
        appendLine("Today is $today (${today.dayOfWeek.name.lowercase()}).")
        appendLine()
        append(AgentTools.describe(board, today, 21))
        appendLine()
        appendLine("ARRIVED SINCE THE LAST CHECK (id | source | from | text):")
        items.forEach { appendLine("${it.id} | ${it.source} | ${it.from.take(80)} | ${it.text.replace('\n', ' ').take(400)}") }
        appendLine()
        appendLine("Propose an update only for items that change something in the table: a meeting moved or added, a step now done,")
        appendLine("new work for a project, a new or changed deadline. Ignore newsletters, ads, social media, receipts and chit-chat.")
        appendLine("Most items need nothing: returning no update is normal. One update per item at most; group nothing.")
        val urgent = listOfNotNull(
            "it is due today or tomorrow".takeIf { c.urgentToday },
            "it blocks a project".takeIf { c.urgentBlocks },
            ("it comes from a key contact (${c.keyContacts.joinToString()})").takeIf { c.urgentKey && c.keyContacts.isNotEmpty() },
        )
        appendLine("urgent = true only when " + (urgent.ifEmpty { listOf("never") }).joinToString(" or ") + ".")
        appendLine("Never propose to send or answer anything.")
        appendLine("""Reply with only: {"updates":[{"item":"<id>","summary":"<one short sentence: what you propose>","project":"<project name or empty>","urgent":false,"actions":[...]}]}""")
        appendLine("Actions (use the step ids and project names shown above, dates as YYYY-MM-DD):")
        appendLine("""- {"type":"add","title":"...","description":"...","project":"<existing project or empty>","kind":"TASK|MEETING|DEADLINE","date":"<optional day>"}""")
        appendLine("""- {"type":"done","step":"<step id>"}""")
        appendLine("""- {"type":"move","step":"<step id>","date":"..."}""")
        appendLine("""- {"type":"deadline","project":"<name>","date":"..."}""")
        appendLine("Write the summary in the language of the item.")
    }

    fun parse(reply: String, items: List<Incoming>, now: String): List<Update> {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        val list = root["updates"] as? JsonArray ?: return emptyList()
        val byId = items.associateBy { it.id }
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val item = byId[o.str("item")] ?: return@mapNotNull null
            val actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { a -> (a as? JsonObject)?.let(::action) }
            val summary = o.str("summary")
            if (actions.isEmpty() || summary.isBlank()) return@mapNotNull null
            Update(
                id = BoardOps.newId(), source = item.source, from = item.from, text = item.text.take(400), summary = summary,
                project = o.str("project"), actions = actions,
                urgent = o["urgent"]?.jsonPrimitive?.booleanOrNull ?: false, createdAt = now,
            )
        }
    }

    private fun action(o: JsonObject): UpdateAction? {
        val type = o.str("type").lowercase()
        if (type !in setOf("add", "done", "move", "deadline")) return null
        return UpdateAction(
            type = type, project = o.str("project"), step = o.str("step"), title = o.str("title"),
            description = o.str("description"),
            kind = o.str("kind").uppercase().let { k -> TaskKind.entries.firstOrNull { it.name == k } },
            date = o.str("date").ifBlank { null },
        )
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
}
