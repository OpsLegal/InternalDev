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
    fun add(board: Board, fresh: List<Update>): Board {
        var updates = board.updates
        for (f in fresh) {
            val open = updates.filter(::waits)
            if (open.any { (sameOrigin(it, f) || (it.source == f.source && it.from == f.from && it.text == f.text)) && covers(it, f) }) continue
            val same = open.lastOrNull { sameOrigin(it, f) }
            updates = if (same == null) updates + f else updates.map { if (it.id == same.id) merge(it, f) else it }
        }
        return prune(board.copy(updates = updates))
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
                "move" -> "Moves a step to ${day(a.date) ?: "a later day"}."
                "done" -> "Marks a step done."
                "deadline" -> "Sets the deadline of ${a.project} to ${day(a.date) ?: "none"}."
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
    fun inbox(board: Board): List<Update> = board.updates
        .filter { u -> u.status != UpdateStatus.DISMISSED && ((u.status == UpdateStatus.NEW && u.actions.isNotEmpty()) || (u.needsReply && !u.replied)) }
        .distinctBy { it.id }
        .sortedWith(compareByDescending<Update> { it.urgent }.thenByDescending { it.at.ifBlank { it.createdAt } })

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
        return prune(b)
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
                val spec = BoardOps.NewTask(
                    title = a.title.trim(), description = a.description.trim(),
                    project = a.project.trim().let { BoardOps.findProject(board, it)?.name ?: it },
                    kind = a.kind ?: TaskKind.TASK,
                    fixedDate = date?.takeIf { a.kind == TaskKind.MEETING }?.toString(),
                )
                if (date != null && !date.isBefore(today)) BoardOps.addTaskOn(board, spec, date, today).first
                // A meeting needs its own date: never let the planner drop it on the next free day.
                else if (a.kind == TaskKind.MEETING) return null
                else BoardOps.add(board, spec, today).board
            }
            "done" -> {
                val (_, step) = findOpenStep(board, a) ?: return null
                BoardOps.setStepDone(board, step.id, true)
            }
            "move" -> {
                if (date == null) return null
                val (task, step) = findOpenStep(board, a) ?: return null
                var next = BoardOps.leaveMissed(board, step.id, today)
                if (task.kindOf(step) == TaskKind.MEETING) next = BoardOps.mapStep(next, step.id) { it.copy(fixedDate = date.toString()) }
                BoardOps.moveStep(next, step.id, date) ?: BoardOps.unschedule(next, step.id, date.toString())
            }
            "deadline" -> {
                val project = BoardOps.findProject(board, a.project) ?: return null
                BoardOps.saveProject(board, project.copy(deadline = date?.toString()), project.name)
            }
            else -> null
        }
    }

    /** The step an action names: by id, or by its title within the project (still to do). */
    private fun findOpenStep(board: Board, a: UpdateAction) =
        BoardOps.findStep(board, a.step)?.takeIf { !it.second.closed }
            ?: BoardOps.projectTask(board, a.project)?.let { t ->
                t.steps.firstOrNull { !it.closed && it.title.equals(a.step.trim(), ignoreCase = true) }?.let { t to it }
            }
}
