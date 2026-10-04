package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Lesson
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
    fun setReplied(board: Board, id: String): Board =
        prune(board.copy(updates = board.updates.map { if (it.id == id) it.copy(replied = true) else it }))

    private fun handled(u: Update) = (u.status != UpdateStatus.NEW || u.actions.isEmpty()) && (!u.needsReply || u.replied)

    private fun prune(board: Board): Board {
        val drop = board.updates.filter(::handled).dropLast(KEEP_HANDLED).map { it.id }.toSet()
        return board.copy(updates = board.updates.filter { it.id !in drop })
    }

    /**
     * Adds new proposals, skipping any that repeats one still waiting (same source, sender and text). A newer
     * answer to prepare for the same chat replaces the older one: one card per person.
     */
    fun add(board: Board, fresh: List<Update>): Board {
        val waiting = board.updates.filter { it.status == UpdateStatus.NEW || (it.needsReply && !it.replied) }
        val new = fresh.filter { f -> waiting.none { it.source == f.source && it.from == f.from && it.text == f.text } }
        val newChats = new.filter { it.needsReply && it.chatId.isNotBlank() }.map { it.chatId }.toSet()
        val kept = board.updates.map { if (it.chatId in newChats && it.needsReply && !it.replied) it.copy(replied = true) else it }
        return prune(board.copy(updates = kept + new))
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

    /** At most this many lessons are kept (and sent to the AI). */
    const val MAX_LESSONS = 30

    /**
     * Puts a card away with its reason. [done]: already handled, by the user or anyone; it closes both piles and
     * teaches nothing. Otherwise "not needed" for [pile] ("tasks" or "replies"): it closes that pile and becomes a
     * lesson, so similar items are skipped.
     */
    fun putAway(board: Board, id: String, done: Boolean, pile: String, lessonId: String): Board {
        val u = board.updates.firstOrNull { it.id == id } ?: return board
        val changed = when {
            done -> u.copy(status = if (u.status == UpdateStatus.NEW) UpdateStatus.DISMISSED else u.status, replied = true, handledAs = "done")
            pile == "replies" -> u.copy(replied = true, handledAs = "not_needed")
            else -> u.copy(status = UpdateStatus.DISMISSED, handledAs = "not_needed")
        }
        val next = prune(board.copy(updates = board.updates.map { if (it.id == id) changed else it }))
        if (done) return next
        val lesson = Lesson(lessonId, sender(u.from), u.source, if (pile == "replies") "no reply needed" else "nothing to do", u.summary.take(160))
        return next.copy(learned = (listOf(lesson) + next.learned).take(MAX_LESSONS))
    }

    fun forget(board: Board, lessonId: String): Board = board.copy(learned = board.learned.filter { it.id != lessonId })

    /** "Sophie (client)" → "Sophie"; "Me Dubé → Julie" → "Me Dubé". */
    fun sender(from: String) = from.substringBefore(" (").substringBefore(" →").trim()

    /** The user answered these chats themselves (in Beeper, WhatsApp...): their cards leave the Replies pile. */
    fun answeredElsewhere(board: Board, chatIds: Set<String>): Board {
        if (chatIds.isEmpty()) return board
        return prune(board.copy(updates = board.updates.map { if (it.chatId in chatIds && it.needsReply) it.copy(replied = true) else it }))
    }

    /** Changes to the table waiting for Apply or Dismiss, urgent first. */
    fun tasks(board: Board): List<Update> =
        board.updates.filter { it.status == UpdateStatus.NEW && it.actions.isNotEmpty() }.sortedByDescending { it.urgent }

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
                var next = board
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
