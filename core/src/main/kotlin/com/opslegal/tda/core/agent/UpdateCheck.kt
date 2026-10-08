package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Updates
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

    suspend fun run(
        provider: LlmProvider, board: Board, items: List<Incoming>, today: LocalDate, now: String,
        calendar: List<CalendarEvent> = emptyList(), sweep: Boolean = false,
    ): List<Update> {
        if (items.isEmpty()) return emptyList()
        val batch = items.sortedByDescending { it.at }.take(if (sweep) MAX_SWEEP else MAX_ITEMS)
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt(board, batch, today, calendar, sweep))), emptyList()).text
        return parse(reply, batch, now)
    }

    /** The weekly sweep reads more: everything still open from the last [SWEEP_DAYS] days. */
    const val MAX_SWEEP = 50
    const val SWEEP_DAYS = 30L

    fun prompt(board: Board, items: List<Incoming>, today: LocalDate, calendar: List<CalendarEvent> = emptyList(), sweep: Boolean = false): String = buildString {
        val c = board.checks
        appendLine("You check what arrived on a Docket 5 user's phone and propose changes to their table (5 cells a day).")
        appendLine("Today is $today (${today.dayOfWeek.name.lowercase()}).")
        appendLine()
        append(AgentTools.describe(board, today, 21))
        appendLine()
        if (calendar.isNotEmpty()) {
            appendLine("THE USER'S CALENDAR (next days):")
            calendar.take(40).forEach { appendLine("- ${it.describe()}") }
            appendLine()
        }
        val waiting = Updates.waiting(board)
        if (waiting.isNotEmpty()) {
            appendLine("ALREADY WAITING FOR THE USER (never propose these again):")
            waiting.take(20).forEach { appendLine("- ${it.from}: ${it.summary.take(140)}") }
            appendLine()
        }
        if (sweep) {
            appendLine("THE WEEKLY SWEEP: these items of the last $SWEEP_DAYS days are still open: chats where the person wrote last and the")
            appendLine("user never answered, and emails still unread. Bring back only those still holding a direct question or request to the")
            appendLine("user that nothing in the table, the calendar or the waiting list already covers. Say in the summary how long it has waited.")
            appendLine("Unread is not a request: newsletters, notifications, receipts and FYI stay out.")
            appendLine("STILL OPEN (id | source | from | text):")
        } else {
            appendLine("ARRIVED SINCE THE LAST CHECK (id | source | from | text):")
        }
        items.forEach { item ->
            appendLine("${item.id} | ${item.source}${if (item.cc) " (user only in CC)" else ""} | ${item.from.take(80)} | ${item.text.replace('\n', ' ').take(400)}")
            // A conversation: what the person wrote since the user's last reply, so a closing "ok" doesn't hide the request.
            item.thread.forEach { m -> appendLine("    ${if (m.fromMe) "USER" else m.sender.take(40)} (${m.time}): ${m.text.replace('\n', ' ').take(300)}") }
        }
        appendLine()
        appendLine("Propose an update only for items that change something in the table: a meeting moved or added, a step now done,")
        appendLine("new work for a project, a new or changed deadline. Ignore newsletters, ads, social media, receipts and chit-chat.")
        appendLine("Most items need nothing: returning no update is normal. One update per item at most; group nothing.")
        appendLine("One thing = one effort for the user: if an item is about something already in the table, in the calendar or already")
        appendLine("waiting (an invitation and its calendar event, a reminder of a meeting, a follow-up on a request already listed), propose")
        appendLine("nothing unless it changes something (a new time, a new deadline, a cancellation).")
        appendLine("An email where the user is only in CC is for information: propose only if it touches the table (e.g. a short check")
        appendLine("that someone else did what was asked), and its summary starts with what it means for the user.")
        appendLine("Decide only by these rules, for each item on its own. Cards the user put away before say nothing about new items:")
        appendLine("a similar message, task or meeting is proposed again whenever the rules say so.")
        val handled = board.updates.filter { it.handledAs == "done" }.takeLast(10)
        if (handled.isNotEmpty()) {
            appendLine("Already handled by the user or someone else (never propose these again; it does NOT mean such items are unimportant):")
            handled.forEach { appendLine("- ${it.from}: ${it.summary.take(140)}") }
        }
        val urgent = listOfNotNull(
            "it is due today or tomorrow".takeIf { c.urgentToday },
            "it blocks a project".takeIf { c.urgentBlocks },
            ("it comes from a key contact (${c.keyContacts.joinToString()})").takeIf { c.urgentKey && c.keyContacts.isNotEmpty() },
        )
        appendLine("urgent = true only when " + (urgent.ifEmpty { listOf("never") }).joinToString(" or ") + ".")
        // Unanswered emails and messages always show in the bell (Reply then sets up the reply assistant if it is off).
        run {
            appendLine("Also flag items where a person waits for an answer from the user: only a direct question or a direct request addressed")
            appendLine("to the user. Never for thanks, \"ok\", \"perfect\", \"I agree\", an emoji or any other acknowledgment, even if it")
            appendLine("is the last message; never when the user already answered and the person only acknowledges.")
            appendLine("Never for a meeting invitation or anything from a calendar: the user answers those in the calendar (accept, decline or")
            appendLine("propose a new time there).")
            appendLine("reading the whole conversation shown under the item: the last message may be a small word while the request is before it.")
            appendLine("\"reply\":true, even with no action. Not for automatic emails, newsletters, receipts or plain FYI.")
            appendLine("When a person asks the user to do something that becomes a task, set reply:true too, so the user can tell them it is taken into account.")
            appendLine("One item = ONE update: an answer and a task from the same item go together (reply:true AND the add action in the same")
            appendLine("update), never as two updates. If an answer for that person is ALREADY WAITING, put the task in an update for the")
            appendLine("same item anyway: the app joins them into one card.")
            appendLine("Not reply:true for an email where the user is only in CC, unless the user is asked by name.")
            appendLine("When the person says when they need it, \"due\": that date (YYYY-MM-DD).")
            appendLine("You never send or answer anything yourself: the user writes and sends every answer.")
        }
        appendLine("""Reply with only: {"updates":[{"item":"<id>","title":"<3 to 6 words: what it is about>","summary":"<one short sentence: what you propose>","project":"<project name or empty>","urgent":false,"reply":false,"meeting":"","due":"","actions":[...]}]}""")
        appendLine("Actions (use the step ids and project names shown above, dates as YYYY-MM-DD):")
        appendLine("""- {"type":"add","title":"...","description":"...","project":"<existing project or empty>","kind":"TASK|MEETING|DEADLINE","date":"<optional day>","effort":"LIGHT|NORMAL|HEAVY","intention":"<why it matters to the user, one sentence>","where":"<place or empty>"${if (board.gbn) ""","serve":{"<attribute>":1-3}""" else ""}}""")
        appendLine("For an add, fill everything the task form needs (title, description, kind, effort, intention, the day that respects their deadline${if (board.gbn) ", serve" else ""}): the user saves it as is.")
        if (board.gbn) append(com.opslegal.tda.core.plan.Gbn.prompt(board))
        appendLine("""- {"type":"done","step":"<step id>"}""")
        appendLine("""- {"type":"move","step":"<step id>","date":"..."}""")
        appendLine("""- {"type":"change","step":"<step id>","title":"<new title>","description":"<what changed, with place and time>","date":"<new day, or empty if the same>"}""")
        appendLine("""- {"type":"cancel","step":"<step id>"}  (only when the plan is dropped, not when it changes)""")
        appendLine("UPDATE, NEVER DUPLICATE: before any add, look in the table and the waiting list for a cell about the same thing (the same")
        appendLine("people, the same event, the same meeting, the same errand). If one exists, the item changes it: use change (new title, place,")
        appendLine("time, details) and/or move (new day) on that step, never a second add. Examples: friends first invited the user for drinks at")
        appendLine("their place, then everyone agreed on a restaurant at 19:00 -> change that cell to \"19:00 Restaurant <name>, Old Port\";")
        appendLine("a meeting moved from tomorrow to today -> move that meeting (and change its title if the time changed), never add one.")
        appendLine("Read the whole conversation: the LAST decision wins (place, time, day), earlier ideas are replaced.")
        appendLine("""- {"type":"deadline","project":"<name>","date":"..."}""")
        appendLine("For a meeting, \"date\" is the meeting's own date from the invitation (never the day the email arrived), and the")
        appendLine("title starts with its time (e.g. \"14:00 Call with CN\"). If the meeting is already in the calendar above, propose nothing.")
        appendLine("Write the summary in the language of the item, and say the day of the meeting in it.")
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
            // Invitations are answered in the calendar, never as a reply (whatever the AI says).
            val invitation = item.source == "calendar" || o.str("meeting").isNotBlank() ||
                INVITATION.containsMatchIn(item.text)
            val reply = (o["reply"]?.jsonPrimitive?.booleanOrNull ?: false) && !invitation
            if ((actions.isEmpty() && !reply) || summary.isBlank()) return@mapNotNull null
            Update(
                id = BoardOps.newId(), source = item.source, from = item.from, text = item.text.take(400), summary = summary,
                project = o.str("project"), actions = actions,
                urgent = o["urgent"]?.jsonPrimitive?.booleanOrNull ?: false, createdAt = now,
                needsReply = reply, mailId = item.mailId,
                chatId = item.chatId, thread = item.thread, cc = item.cc,
                due = o.str("due").takeIf { runCatching { LocalDate.parse(it) }.isSuccess }.orEmpty(),
                title = o.str("title").take(60), at = item.at,
            )
        }
    }

    /** Words of meeting invitations (Outlook, Teams, Google, Zoom), in English and French. */
    private val INVITATION = Regex(
        "(?i)(réunion microsoft teams|microsoft teams meeting|join the meeting|rejoindre la réunion|invitation:|invitation :|" +
            "accepted:|declined:|tentative:|accepté :|refusé :|zoom\\.us/j/|meet\\.google\\.com|calendar invitation|invitation au calendrier)",
    )

    private fun action(o: JsonObject): UpdateAction? {
        val type = o.str("type").lowercase()
        if (type !in setOf("add", "done", "move", "deadline", "change", "cancel")) return null
        return UpdateAction(
            type = type, project = o.str("project"), step = o.str("step"), title = o.str("title"),
            description = o.str("description"),
            kind = o.str("kind").uppercase().let { k -> TaskKind.entries.firstOrNull { it.name == k } },
            date = o.str("date").ifBlank { null },
            intention = o.str("intention"),
            serve = (o["serve"] as? JsonObject)?.mapNotNull { (k, v) -> runCatching { v.jsonPrimitive.contentOrNull?.toDouble()?.toInt() }.getOrNull()?.coerceIn(0, 3)?.takeIf { it > 0 }?.let { k to it } }?.toMap().orEmpty(),
            effort = o.str("effort").uppercase().let { e -> com.opslegal.tda.core.model.Effort.entries.firstOrNull { it.name == e } },
            where = o.str("where"),
        )
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
}
