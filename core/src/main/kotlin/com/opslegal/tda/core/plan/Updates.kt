package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.model.UpdateStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Applying the changes proposed by an update. Nothing here runs without the user's Apply. */
object Updates {

    /** How many handled (applied or dismissed) updates are kept, for the record. */
    const val KEEP_HANDLED = 20

    /**
     * The board with [update]'s changes and the update marked applied, or null when it no longer applies
     * (a step it names is gone or already closed, the project was renamed...). The caller lets the planner run.
     */
    fun apply(board: Board, update: Update, today: LocalDate, events: List<CalendarEvent> = emptyList()): Board? {
        var next = board
        for (action in update.actions) {
            // A meeting that is in the calendar: placed from the calendar (its own date), and never twice.
            val event = action.takeIf { it.type == "add" && it.kind == TaskKind.MEETING }?.let { CalendarCells.findEvent(it.title, it.date, events) }
            next = if (event != null) CalendarCells.add(next, event, today) else applyAction(next, action, today) ?: return null
        }
        return setStatus(next, update.id, UpdateStatus.APPLIED)
    }

    fun setStatus(board: Board, id: String, status: UpdateStatus): Board =
        prune(board.copy(updates = board.updates.map { if (it.id == id) it.copy(status = status) else it }))

    /** The reply was saved as a draft or not needed: it leaves the Replies pile. */
    /** How many of the user's own replies are kept as examples of how they write. */
    const val STYLE_EXAMPLES = 5

    /** A reply the user sent or saved, as they wrote or corrected it: an example of their style for the next ones. */
    fun rememberReply(board: Board, text: String): Board {
        val t = text.trim().take(600)
        if (t.length < 10) return board
        return board.copy(replyStyle = (board.replyStyle.filter { it != t } + t).takeLast(STYLE_EXAMPLES))
    }

    fun setReplied(board: Board, id: String): Board =
        prune(board.copy(updates = board.updates.map { if (it.id == id) it.copy(replied = true) else it }))

    private fun handled(u: Update) = (u.status != UpdateStatus.NEW || u.actions.isEmpty()) && (!u.needsReply || u.replied)

    private fun prune(board: Board): Board {
        val drop = board.updates.filter(::handled).dropLast(KEEP_HANDLED).map { it.id }.toSet()
        return board.copy(updates = board.updates.filter { it.id !in drop })
    }

    /**
     * Adds new proposals, skipping any that repeats one still waiting (same source, sender and text). What comes
     * from the same origin (the same email, the same chat, or the same person asking and needing an answer) is
     * one card: the answer to write and the change to the table together, never two cards for one thing.
     */
    fun add(board: Board, fresh0: List<Update>): Board {
        var updates = board.updates
        // A safety net: an "add" about something already on the table becomes a change of that cell.
        // A bill already paid (an expense, a payment seen on the phone) is not proposed again; a sender the user ignores brings nothing.
        val today = java.time.LocalDate.now()
        val fresh = fresh0.filter { u -> board.lookIgnore.none { u.from.contains(it, ignoreCase = true) } }
            .map { u -> u.copy(actions = u.actions.filterNot { it.type == "bill" && Bills.alreadyPaid(board, it.title, it.amount, today) }.map { a -> sameAsOpen(board, a) ?: a }) }
            .filter { it.actions.isNotEmpty() || fresh0.first { f -> f.id == it.id }.actions.isEmpty() }
        for (f0 in fresh) {
            val open = updates.filter(::waits)
            // Already on the table (any day), or already proposed by a card still waiting: never a second cell.
            val proposed = open.filter { it.status == UpdateStatus.NEW }.flatMap { u -> u.actions.filter { it.type == "add" } }
            val keep = f0.actions.filterNot { a -> a.type == "add" && (alreadyOnTable(board.copy(updates = updates), a.title, a.project) != null || proposed.any { sameThing(it.title, a.title) }) }
            if (keep.isEmpty() && f0.actions.isNotEmpty() && !f0.needsReply) continue
            val f = f0.copy(actions = keep)
            // The same message seen twice (the Outlook notification and the mail check; a chat read twice): one card.
            val twin = open.lastOrNull { looksSame(it, f) }
            if (twin != null) { updates = updates.map { if (it.id == twin.id) merge(it, f) else it }; continue }
            if (open.any { (sameOrigin(it, f) || (it.source == f.source && it.from == f.from && it.text == f.text)) && covers(it, f) }) continue
            val same = open.lastOrNull { sameOrigin(it, f) }
            updates = if (same == null) updates + f else updates.map { if (it.id == same.id) merge(it, f) else it }
        }
        return prune(board.copy(updates = updates))
    }

    /** The same message from two roads: the same sender, both emails or both chats, and nearly the same words. */
    fun looksSame(a: Update, b: Update): Boolean {
        fun road(src: String) = if (src in EMAIL || src == "outlook" || src == "gmail" || src == "email") "mail" else src
        if (road(a.source) != road(b.source)) return false
        val wa = sender(a.from); val wb = sender(b.from)
        if (wa.isBlank() || !fold(wa).let { x -> fold(wb).let { y -> x.contains(y) || y.contains(x) } }) return false
        return sameThing(a.text.take(300), b.text.take(300)) || sameThing(a.summary, b.summary)
    }

    private fun waits(u: Update) = (u.status == UpdateStatus.NEW && u.actions.isNotEmpty()) || (u.needsReply && !u.replied)

    /** [old] already holds everything [new] asks, from the same words: nothing to add. */
    private fun covers(old: Update, new: Update): Boolean {
        val pending = if (old.status == UpdateStatus.NEW) old.actions else emptyList()
        return old.text == new.text && (!new.needsReply || (old.needsReply && !old.replied)) && new.actions.all { a -> pending.any { sameAction(it, a) } }
    }

    private fun sameAction(a: UpdateAction, b: UpdateAction) = a.type == b.type && a.title.equals(b.title, true) && a.step == b.step

    /** The same email, the same chat, or the same person where one card is an answer and the other a change. */
    fun sameOrigin(a: Update, b: Update): Boolean = when {
        a.mailId.isNotBlank() && a.mailId == b.mailId -> true
        a.chatId.isNotBlank() && a.chatId == b.chatId -> true
        else -> {
            val who = sender(a.from)
            val oneEach = (a.needsReply && b.actions.isNotEmpty()) || (b.needsReply && a.actions.isNotEmpty())
            who.isNotBlank() && who.equals(sender(b.from), ignoreCase = true) && oneEach
        }
    }

    /** One card from two: the newest words, the answer if either needs one, every change still to apply. */
    fun merge(old: Update, new: Update): Update {
        val pending = if (old.status == UpdateStatus.NEW) old.actions else emptyList()
        val actions = pending + new.actions.filter { a -> pending.none { sameAction(it, a) } }
        val reply = (old.needsReply && !old.replied) || new.needsReply
        val summaries = listOf(old.summary, new.summary.takeUnless { old.summary.contains(it, ignoreCase = true) }.orEmpty()).filter { it.isNotBlank() }
        return old.copy(
            text = new.text, thread = new.thread.ifEmpty { old.thread }, summary = summaries.joinToString(" + ").take(300),
            project = old.project.ifBlank { new.project }, actions = actions, urgent = old.urgent || new.urgent,
            status = if (actions.isNotEmpty()) UpdateStatus.NEW else old.status,
            needsReply = reply, replied = if (reply) false else old.replied,
            meeting = old.meeting.ifBlank { new.meeting }, mailId = old.mailId.ifBlank { new.mailId },
            chatId = old.chatId.ifBlank { new.chatId }, cc = old.cc && new.cc,
            due = listOf(old.due, new.due).filter { it.isNotBlank() }.minOrNull().orEmpty(),
            title = old.title.ifBlank { new.title }, at = listOf(old.at, new.at).filter { it.isNotBlank() }.minOrNull().orEmpty(),
        )
    }

    /**
     * What Apply will do, in plain words, shown on the card before the tap: which day each new cell lands on (from the
     * calendar when it is there), what moves, what is done. A meeting with no date says so.
     */
    fun describe(board: Board, update: Update, today: LocalDate, events: List<CalendarEvent> = emptyList()): List<String> =
        update.actions.map { a ->
            fun day(d: String?) = d?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${it.dayOfMonth}" }
            when (a.type) {
                "add" -> {
                    val event = if (a.kind == TaskKind.MEETING) CalendarCells.findEvent(a.title, a.date, events) else null
                    when {
                        event != null && CalendarCells.isAdded(board, event) -> "“${a.title}” is already in your day (${day(event.start.take(10))}): Apply just closes this."
                        event != null -> "Adds “${a.title}” on ${day(event.start.take(10))}, ${CalendarCells.whenText(event)} (from your calendar)."
                        a.kind == TaskKind.MEETING && a.date == null -> "Adds the meeting “${a.title}” without a date: check the date first (Discuss)."
                        a.date != null -> "Adds “${a.title}” on ${day(a.date)}."
                        else -> "Adds “${a.title}” to the next free cell."
                    }
                }
                "move" -> "Moves “${stepTitle(board, a)}” to ${day(a.date) ?: "a later day"}."
                "change" -> "Updates “${stepTitle(board, a)}”" + (if (a.title.isNotBlank()) " → “${a.title}”" else "") + (day(a.date)?.let { ", on $it" } ?: "") + "."
                "cancel" -> "Cancels “${stepTitle(board, a)}”."
                "done" -> "Marks a step done."
                "deadline" -> "Sets the deadline of ${a.project} to ${day(a.date) ?: "none"}."
                "event" -> "📅 To your calendar: ${a.title}, ${day(a.date) ?: "date to check"}" + (if (a.time.isNotBlank()) " ${a.time}" else "") +
                    when (a.repeat) { "weekly" -> ", every week"; "biweekly" -> ", every 2 weeks"; "monthly" -> ", every month"; else -> "" } + ". Not a cell: you decide later who goes."
                "bill" -> "Adds to To pay: ${a.title}" + (a.amount?.let { " %.2f $".format(Locale.CANADA, it) } ?: "") + (day(a.date)?.let { ", due $it" } ?: "") + "."
                else -> a.type
            }
        }

    /**
     * Puts a card away. [done]: already handled, by the user or anyone; it closes both piles. Otherwise "not this
     * time": it closes only [pile] ("tasks" or "replies"). Neither teaches the assistant to skip anything: what to
     * propose follows the rules, and a similar item next time is proposed again.
     */
    fun putAway(board: Board, id: String, done: Boolean, pile: String): Board {
        val u = board.updates.firstOrNull { it.id == id } ?: return board
        val changed = when {
            done -> u.copy(status = if (u.status == UpdateStatus.NEW) UpdateStatus.DISMISSED else u.status, replied = true, handledAs = "done")
            pile == "replies" -> u.copy(replied = true, handledAs = "not_now")
            else -> u.copy(status = UpdateStatus.DISMISSED, handledAs = "not_now")
        }
        return prune(board.copy(updates = board.updates.map { if (it.id == id) changed else it }))
    }

    /**
     * For the weekly sweep: what is still open and not already in hand. An item already waiting in the bell, or
     * already answered, applied or marked done, stays out; one put away "not this time" comes back.
     */
    fun sweepable(board: Board, items: List<com.opslegal.tda.core.model.Incoming>): List<com.opslegal.tda.core.model.Incoming> =
        items.filter { item ->
            board.updates.none { u ->
                val same = (item.mailId.isNotBlank() && u.mailId == item.mailId) || (item.chatId.isNotBlank() && u.chatId == item.chatId && u.text == item.text)
                same && (waits(u) || u.handledAs == "done" || (u.needsReply && u.replied && u.handledAs != "not_now") || u.status == UpdateStatus.APPLIED)
            }
        }

    /** "Sophie (client)" → "Sophie"; "Me Dubé → Julie" → "Me Dubé". */
    fun sender(from: String) = from.substringBefore(" (").substringBefore(" →").trim()

    /** The user answered these chats themselves (in Beeper, WhatsApp...): their cards leave the Replies pile. */
    fun answeredElsewhere(board: Board, chatIds: Set<String>): Board {
        if (chatIds.isEmpty()) return board
        return prune(board.copy(updates = board.updates.map { if (it.chatId in chatIds && it.needsReply) it.copy(replied = true) else it }))
    }

    /**
     * Changes to the table waiting for Apply or Dismiss, urgent first. A card that also needs an answer shows once,
     * in Replies (with its Apply); it comes here only after the answer is written or put away.
     */
    fun tasks(board: Board): List<Update> {
        val answering = replies(board).map { it.id }.toSet()
        return board.updates.filter { it.status == UpdateStatus.NEW && it.actions.isNotEmpty() && it.id !in answering }.sortedByDescending { it.urgent }
    }

    /** Answers waiting to be prepared, urgent first; none while the reply assistant is off. */
    fun replies(board: Board): List<Update> {
        val r = board.replies
        if (!r.on) return emptyList()
        return board.updates.filter { u ->
            // Invitations are answered in the calendar, never here.
            u.needsReply && !u.replied && u.meeting.isBlank() && u.source != "calendar" && when {
                u.source in EMAIL -> r.email
                else -> r.messages
            }
        }.sortedByDescending { it.urgent }
    }

    /** Everything behind the bell, each update once, urgent first. */
    /**
     * The bell's one list: every item that waits for the user (a change to the table, an answer, or both), each once,
     * urgent first, newest next. Unanswered emails and messages show even while the reply assistant is off.
     */
    /**
     * What a card asks, found on the table, by rules on the phone (no AI). [sure]: the same thing (the card can close by
     * itself); not sure: it looks alike and the user is asked once ("Is this the same?"). [done]: already done, not only
     * planned. [key]: the cell ("step:id") or quick thing ("quick:id").
     */
    data class Found(val what: String, val day: String, val done: Boolean, val sure: Boolean, val key: String)

    private fun candidates(board: Board, today: java.time.LocalDate): List<Pair<String, Found>> {
        val since = today.minusDays(30).toString()
        val steps = board.tasks.flatMap { t -> t.steps.filter { it.outcome == null && (!it.done || (it.date ?: "") >= since) }.map { s ->
            (if (t.isProject) s.title else t.title) to Found(Planner.cellTitle(t, s), s.date ?: "", s.done, true, "step:${s.id}") } }
        val quick = board.quick.filter { !it.done || it.date >= since }.map { it.title to Found("⚡ ${it.title}", it.date, it.done, true, "quick:${it.id}") }
        return steps + quick
    }

    /** Sure enough to close a card by itself: the same thing, and nearly the same words on both sides (70% of the longer). */
    private fun surelySame(x: String, y: String): Boolean {
        if (!sameThing(x, y)) return false
        val a = keyWords(x); val b = keyWords(y)
        return a.intersect(b).size * 10 >= maxOf(a.size, b.size) * 7
    }

    /** Not the same words but close: two key words in common and at least half of the shorter title. */
    private fun alike(x: String, y: String): Boolean {
        val a = keyWords(x); val b = keyWords(y)
        val common = a.intersect(b).size
        return common >= 2 && common * 2 >= minOf(a.size, b.size)
    }

    fun onTable(board: Board, u: Update, today: java.time.LocalDate = java.time.LocalDate.now()): Found? {
        // A "done" or "move" the table already shows: nothing left to do for this proposal.
        if (u.actions.isNotEmpty() && u.actions.all { it.type == "done" || it.type == "move" }) {
            val hits = u.actions.map { a ->
                val f = BoardOps.findStep(board, a.step) ?: BoardOps.projectTask(board, a.project)?.let { t -> t.steps.lastOrNull { it.title.equals(a.step.trim(), true) }?.let { t to it } }
                f?.let { (t, s) ->
                    val ok = if (a.type == "done") s.done else !s.closed && a.date != null && s.date == a.date
                    if (ok) Found(Planner.cellTitle(t, s), s.date ?: "", s.done, true, "step:${s.id}") else null
                }
            }
            return if (hits.all { it != null }) hits.first() else null
        }
        val adds = u.actions.filter { it.type == "add" }
        if (u.actions.isNotEmpty() && adds.size != u.actions.size) return null
        val wanted = adds.map { it.title }.ifEmpty { listOf(u.title) }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return null
        // Done before the message came: maybe a new request for the same thing (call mom again), so asked, never assumed.
        val arrived = u.at.ifBlank { u.createdAt }.take(10)
        val pool = candidates(board, today).filter { it.second.key !in u.notSame }
            .map { (t, f) -> t to if (f.done && arrived.isNotBlank() && f.day.isNotBlank() && f.day < arrived) f.copy(sure = false) else f }
        val hits = wanted.map { title ->
            pool.firstOrNull { surelySame(title, it.first) }?.second
                ?: pool.firstOrNull { sameThing(title, it.first) || alike(title, it.first) }?.second?.copy(sure = false)
        }
        if (hits.any { it == null }) return null
        return hits.filterNotNull().let { h -> h.firstOrNull { !it.sure } ?: h.first() }
    }

    /**
     * Cards the table already answers are closed once (they never come back when the cell is done later), and marked
     * so the user sees "closed: already on your table" with a way to bring them back. Only sure matches: a look-alike
     * is asked, never assumed. A card still waiting for an answer stays for the answer.
     */
    fun settle(board: Board, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Board {
        val today = now.toLocalDate()
        var changed = false
        val next = board.updates.map { u ->
            if (u.status != UpdateStatus.NEW || u.actions.isEmpty()) return@map u
            val f = onTable(board, u, today)?.takeIf { it.sure } ?: return@map u
            changed = true
            u.copy(status = UpdateStatus.APPLIED, cleared = if (f.done) "done" else "planned", clearedAt = now.withNano(0).toString())
        }
        return if (changed) board.copy(updates = next) else board
    }

    /** "Yes, it's the same": closed like a sure one. "No, it's different": that cell is never offered again for this card. */
    fun sameAnswer(board: Board, id: String, same: Boolean, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Board =
        board.copy(updates = board.updates.map { u ->
            if (u.id != id) return@map u
            val f = onTable(board, u, now.toLocalDate()) ?: return@map u
            if (same) u.copy(status = UpdateStatus.APPLIED, cleared = if (f.done) "done" else "planned", clearedAt = now.withNano(0).toString())
            else u.copy(notSame = u.notSame + f.key)
        })

    /** Closed by the phone today, newest first: shown once at the top of Notifications, each can come back. */
    fun clearedToday(board: Board, today: java.time.LocalDate): List<Update> =
        board.updates.filter { it.cleared.isNotBlank() && it.clearedAt.take(10) == today.toString() && it.status == UpdateStatus.APPLIED }.sortedByDescending { it.clearedAt }

    /** "Bring it back": the card returns, and what closed it is not taken for it again. */
    fun bringBack(board: Board, id: String, today: java.time.LocalDate): Board = board.copy(updates = board.updates.map { u ->
        if (u.id != id) return@map u
        val key = onTable(board.copy(updates = emptyList()), u, today)?.key
        u.copy(status = UpdateStatus.NEW, cleared = "", clearedAt = "", notSame = u.notSame + listOfNotNull(key))
    })

    fun inbox(board: Board): List<Update> = board.updates
        .filter { u -> u.status != UpdateStatus.DISMISSED && ((u.status == UpdateStatus.NEW && u.actions.isNotEmpty() && onTable(board, u)?.sure != true) || (u.needsReply && !u.replied)) }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<Update> { it.urgent }.thenByDescending { com.opslegal.tda.core.agent.Me.forMe(board, it) }.thenByDescending { it.at.ifBlank { it.createdAt } })

    /**
     * Dismiss with its reason: "done" (already handled, by anyone), "irrelevant" (nothing to keep), or "noted" (nothing
     * to do, worth knowing, like a CC). Done and noted go to the project's history, with the user's comment, so the
     * assistant knows the latest. Nothing teaches the assistant to skip similar items.
     */
    fun dismiss(board: Board, id: String, how: String, comment: String, project: String?, today: java.time.LocalDate): Board {
        val u = board.updates.firstOrNull { it.id == id } ?: return board
        val closed = u.copy(status = if (u.status == UpdateStatus.NEW) UpdateStatus.DISMISSED else u.status, replied = true, handledAs = how)
        var b = board.copy(updates = board.updates.map { if (it.id == id) closed else it })
        val name = (project ?: u.project).ifBlank { null }?.let { BoardOps.findProject(b, it)?.name }
        if (how != "irrelevant" && name != null) {
            val line = "$today · ${if (how == "done") "done" else "noted"} · ${u.from.substringBefore(" (")}: ${(u.title.ifBlank { u.summary }).take(120)}" +
                comment.trim().takeIf { it.isNotBlank() }?.let { " · “${it.take(200)}”" }.orEmpty()
            b = b.copy(projects = b.projects.map { if (it.name == name) it.copy(history = (it.history + line).takeLast(30)) else it })
        }
        val what = when (how) { "done" -> "done"; "irrelevant" -> "not relevant"; else -> "noted" }
        if (comment.isNotBlank()) b = notePerson(b, u.from, "${u.title.ifBlank { u.summary }.take(80)}: $what — ${comment.trim()}", today)
        return prune(b)
    }

    /**
     * After any change: a card whose proposal is already in the table (tasks with the same titles) closes by itself.
     * An answer it still waits for keeps it in the bell.
     */
    fun settleDone(board: Board): Board {
        val titles = board.tasks.flatMap { t -> listOf(t.title) + t.steps.map { it.title } }.filter { it.isNotBlank() }.map { fold(it).trim() }.toSet()
        var changed = false
        val updates = board.updates.map { u ->
            val adds = u.actions.filter { it.type == "add" }
            if (u.status == UpdateStatus.NEW && adds.isNotEmpty() && adds.size == u.actions.size && adds.all { fold(it.title).trim() in titles }) {
                changed = true; u.copy(status = UpdateStatus.APPLIED)
            } else u
        }
        return if (changed) board.copy(updates = updates) else board
    }

    /** Keeps the note the user typed on a card, so the reply, the task or the dismiss can use it. */
    fun setComment(board: Board, id: String, comment: String): Board =
        board.copy(updates = board.updates.map { if (it.id == id) it.copy(comment = comment.trim()) else it })

    /** What the user said about a person, newest last (8 kept): the assistant reads it before answering them again. */
    fun notePerson(board: Board, from: String, text: String, today: java.time.LocalDate): Board {
        val who = sender(from).substringBefore(" <").trim()
        if (text.isBlank() || who.isBlank()) return board
        return board.copy(people = board.people + (who to ((board.people[who].orEmpty()) + "$today · ${text.trim().take(300)}").takeLast(8)))
    }

    /** What the user noted lately about this card's sender. */
    fun aboutPerson(board: Board, from: String): List<String> = board.people[sender(from).substringBefore(" <").trim()].orEmpty()

    /** A day said in a note: "today", "tomorrow", "Thursday", « jeudi », "next week" (next Monday). Null when none. */
    fun dayInNote(text: String, today: java.time.LocalDate): java.time.LocalDate? {
        val t = fold(text)
        if (Regex("\\b(today|aujourd.?hui|ce jour)\\b").containsMatchIn(t)) return today
        if (Regex("\\b(tomorrow|demain)\\b").containsMatchIn(t)) return today.plusDays(1)
        if (Regex("\\b(next week|semaine prochaine)\\b").containsMatchIn(t)) return today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val names = listOf("monday" to "lundi", "tuesday" to "mardi", "wednesday" to "mercredi", "thursday" to "jeudi", "friday" to "vendredi", "saturday" to "samedi", "sunday" to "dimanche")
        val i = names.indexOfFirst { (en, fr) -> Regex("\\b($en|$fr)\\b").containsMatchIn(t) }
        return if (i < 0) null else today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.of(i + 1)))
    }

    /** The project a note names ("put it with Refinancing"), or null. */
    fun projectInNote(board: Board, text: String): String? {
        val t = fold(text); if (t.isBlank()) return null
        return board.projects.map { it.name }.filter { it.isNotBlank() && t.contains(fold(it)) }.maxByOrNull { it.length }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * The quick check when the bell opens: a card closes by itself when the user already answered.
     * Messages: the user wrote anything in the same chat after the person's message (they talked since, so it is
     * settled, even if not word for word). Emails and invitations: only a reply in that same email thread counts;
     * another email to the same person may be about something else, so it never closes the card.
     * [lastMine]: the user's latest message time per chat; [repliedMail]: email ids the user replied to.
     */
    fun settle(board: Board, lastMine: Map<String, String>, repliedMail: Set<String>): Pair<Board, Int> {
        var n = 0
        val updates = board.updates.map { u ->
            val open = u.status != UpdateStatus.DISMISSED && ((u.status == UpdateStatus.NEW && u.actions.isNotEmpty()) || (u.needsReply && !u.replied))
            if (!open) return@map u
            val since = u.thread.lastOrNull { !it.fromMe }?.time?.ifBlank { null } ?: u.at.ifBlank { u.createdAt }
            val chat = u.chatId.isNotBlank() && lastMine[u.chatId]?.let { it > since } == true
            val mail = u.mailId.isNotBlank() && u.mailId in repliedMail
            if (!chat && !mail) return@map u
            n++
            u.copy(status = if (u.status == UpdateStatus.NEW) UpdateStatus.DISMISSED else u.status, replied = true, handledAs = "answered")
        }
        return prune(board.copy(updates = updates)) to n
    }

    fun waiting(board: Board): List<Update> = (tasks(board) + replies(board)).distinctBy { it.id }.sortedByDescending { it.urgent }

    /** Sources answered by email (a draft in Outlook); every other source is a chat (Beeper). */
    val EMAIL = setOf("outlook", "gmail", "email")

    /**
     * Review time: one of the user's check times has passed today since they last opened the bell, and
     * something waits. The bell turns red then, even when nothing is urgent.
     */
    fun reviewDue(board: Board, now: LocalDateTime): Boolean {
        if (tasks(board).isEmpty() && replies(board).isEmpty()) return false
        val slot = board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
            .filter { !it.isAfter(now.toLocalTime()) }.maxOrNull() ?: return false
        val last = board.checks.lastReview?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return last == null || last.isBefore(now.toLocalDate().atTime(slot))
    }

    private fun applyAction(board: Board, a: UpdateAction, today: LocalDate): Board? {
        val date = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when (a.type) {
            "add" -> {
                if (a.title.isBlank()) return null
                // Applied from two cards, or added by hand meanwhile: it is already there, nothing to add twice.
                if (alreadyOnTable(board, a.title, a.project) != null) return board
                val spec = BoardOps.NewTask(
                    title = a.title.trim(), description = a.description.trim(),
                    project = a.project.trim().let { BoardOps.findProject(board, it)?.name ?: it },
                    kind = a.kind ?: TaskKind.TASK,
                    fixedDate = date?.takeIf { a.kind == TaskKind.MEETING }?.toString(),
                    effort = a.effort ?: com.opslegal.tda.core.model.Effort.NORMAL,
                    intention = a.intention, serve = a.serve, where = a.where,
                )
                if (date != null && !date.isBefore(today)) BoardOps.addTaskOn(board, spec, date, today).first
                // A meeting needs its own date: never let the planner drop it on the next free day.
                else if (a.kind == TaskKind.MEETING) return null
                else BoardOps.add(board, spec, today).board
            }
            "done" -> {
                val (_, step) = findOpenStep(board, a) ?: return null
                // A cell in the future can't be done yet: the proposal is wrong, nothing is applied.
                if (step.date != null && LocalDate.parse(step.date).isAfter(today)) return null
                BoardOps.setStepDone(board, step.id, true)
            }
            // A calendar event: it goes to the phone's calendar (the user saves it there), never to the table.
            "event" -> board
            "move" -> {
                if (date == null) return null
                val (task, step) = findOpenStep(board, a) ?: return null
                var next = BoardOps.leaveMissed(board, step.id, today)
                if (task.kindOf(step) == TaskKind.MEETING) next = BoardOps.mapStep(next, step.id) { it.copy(fixedDate = date.toString()) }
                BoardOps.moveStep(next, step.id, date) ?: BoardOps.unschedule(next, step.id, date.toString())
            }
            // A bill to pay (from an email): it goes to To pay, never in a cell of its own.
            "bill" -> {
                if (a.title.isBlank()) return null
                val due = date ?: today.plusDays(7)
                if (board.bills.any { it.paid == null && it.vendor.equals(a.title.trim(), ignoreCase = true) && it.due == due.toString() }) board
                else Bills.add(board, com.opslegal.tda.core.model.Bill(BoardOps.newId(), a.title.trim(), a.amount, due.toString(),
                    a.category.takeIf { it in Bills.categories } ?: Bills.guessCategory(a.title), a.description.take(80), "email"))
            }
            "deadline" -> {
                val project = BoardOps.findProject(board, a.project) ?: return null
                BoardOps.saveProject(board, project.copy(deadline = date?.toString()), project.name)
            }
            // The same thing, changed (a new place, time or day): the existing cell is updated, never doubled.
            "change" -> {
                val (task, step) = findOpenStep(board, a) ?: return null
                var next = board
                if (a.title.isNotBlank()) next = if (!task.isProject && task.steps.size <= 1)
                    BoardOps.updateTask(next, task.id) { t -> t.copy(title = a.title.trim(), steps = t.steps.map { if (it.id == step.id) it.copy(title = a.title.trim()) else it }) }
                    else BoardOps.renameStep(next, step.id, a.title)
                if (a.description.isNotBlank()) next = if (!task.isProject) BoardOps.updateTask(next, task.id) { it.copy(description = a.description.trim()) }
                    else BoardOps.mapStep(next, step.id) { it.copy(description = a.description.trim()) }
                if (date != null && date.toString() != step.date) {
                    next = BoardOps.leaveMissed(next, step.id, today)
                    if (task.kindOf(step) == TaskKind.MEETING || step.fixedDate != null || task.fixedDate != null) {
                        next = BoardOps.mapStep(next, step.id) { it.copy(fixedDate = date.toString()) }
                        if (task.fixedDate != null) next = BoardOps.updateTask(next, task.id) { it.copy(fixedDate = date.toString()) }
                    }
                    next = BoardOps.moveStep(next, step.id, date) ?: BoardOps.unschedule(next, step.id, date.toString())
                }
                next
            }
            "cancel" -> {
                val (_, step) = findOpenStep(board, a) ?: return null
                BoardOps.cancelStep(board, step.id, today)
            }
            else -> null
        }
    }

    private fun stepTitle(board: Board, a: UpdateAction) = BoardOps.findStep(board, a.step)?.let { (t, s) -> Planner.cellTitle(t, s) } ?: a.step

    /** Meaningful words, accents removed, a light stem (appeler/appel, calls/call), no small words. */
    private fun keyWords(t: String): Set<String> = fold(t).split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in STOP_FOLDED }.map { stem(it) }.toSet()
    /** "rappeler" = "appeler", "renvoyer" = "envoyer", "resend" = "send"; plurals off; the first 6 letters. */
    private fun stem(w0: String): String {
        var w = w0.removeSuffix("s")
        if (w.startsWith("rapp")) w = w.drop(1) else if (w.startsWith("re") && w.length > 6) w = w.drop(2)
        return if (w.length > 5) w.take(6) else w
    }
    private val STOP_FOLDED by lazy { (STOP + setOf("pour", "sur", "par", "aux", "ses", "son", "leur", "votre", "your", "about", "this", "that", "cette", "faire", "send", "re", "fwd", "tr")).map(::fold).toSet() }

    /** Two wordings of the same thing: every word of a short title (3 or fewer) is in the other, or most of a longer one's. */
    fun sameThing(x: String, y: String): Boolean {
        val a = keyWords(x); val b = keyWords(y)
        if (a.isEmpty() || b.isEmpty()) return false
        val common = a.intersect(b).size
        val small = minOf(a.size, b.size)
        // Short titles must match fully (a different verb is a different step); longer ones mostly.
        return when {
            small <= 1 -> a == b && a.first().length >= 5
            small <= 3 -> common == small
            else -> common * 10 >= small * 6
        }
    }

    /**
     * A cell still to do (any day, or not yet planned) that is already this task: compared on the step's own words, so two
     * different steps of one project never look alike just because they share the project's name.
     */
    fun alreadyOnTable(board: Board, title: String, project: String = ""): Pair<com.opslegal.tda.core.model.Task, com.opslegal.tda.core.model.Step>? {
        val p = BoardOps.findProject(board, project)?.name ?: project
        val own = keyWords(p)
        val wanted = keyWords(title) - own
        if (wanted.isEmpty()) return null
        return board.tasks.flatMap { t -> t.steps.filter { !it.closed }.map { t to it } }.firstOrNull { (t, s) ->
            val name = if (t.isProject) s.title else t.title
            val samePlace = p.isBlank() || !t.isProject || t.title.equals(p, ignoreCase = true)
            samePlace && sameThing(wanted.joinToString(" "), (keyWords(name) - keyWords(if (t.isProject) t.title else "")).joinToString(" "))
        }
    }

    private val STOP = setOf("with", "avec", "pour", "chez", "from", "dans", "the", "les", "des", "une", "and", "et", "meeting", "réunion", "call", "appel", "today", "tomorrow", "demain")
    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 && it !in STOP }.toSet()

    /**
     * An "add" that is the same thing as a cell still to do in the next two weeks (the same people or event: at least two
     * shared words, or most of its words) is turned into a "change" of that cell, keeping the new title, details and day.
     */
    fun sameAsOpen(board: Board, a: UpdateAction): UpdateAction? {
        if (a.type != "add" || a.title.isBlank()) return null
        val w = words(a.title + " " + a.description.take(80)).ifEmpty { return null }
        val today = LocalDate.now()
        val wanted = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // Close in time too: the same day give or take two (a meeting moved by a day, tonight's plan changing).
        fun near(d: LocalDate) = if (wanted != null) kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(wanted, d)) <= 2
            else !d.isBefore(today.minusDays(1)) && !d.isAfter(today.plusDays(3))
        val match = board.tasks.flatMap { t -> t.steps.map { t to it } }
            .filter { (_, s) -> !s.closed && s.date != null && near(LocalDate.parse(s.date)) }
            .map { (t, s) -> Triple(t, s, words(Planner.cellTitle(t, s) + " " + t.description.take(80)).intersect(w).size) }
            .filter { (t, s, n) -> val tw = words(Planner.cellTitle(t, s)); n >= 2 || (tw.isNotEmpty() && n * 2 >= tw.size && n >= 1 && tw.size <= 2) }
            .maxByOrNull { it.third } ?: return null
        return UpdateAction("change", step = match.second.id, title = a.title, description = a.description, date = a.date?.takeIf { it != match.second.date })
    }

    /** The step an action names: by id, or by its title within the project (still to do). */
    private fun findOpenStep(board: Board, a: UpdateAction) =
        BoardOps.findStep(board, a.step)?.takeIf { !it.second.closed }
            ?: BoardOps.projectTask(board, a.project)?.let { t ->
                t.steps.firstOrNull { !it.closed && it.title.equals(a.step.trim(), ignoreCase = true) }?.let { t to it }
            }
}
