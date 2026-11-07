package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.AfterCall
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.Park
import com.opslegal.tda.core.model.Progress
import com.opslegal.tda.core.model.Update
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The front door: everything that arrived, one card each, one decision each (Done, Today, Next action, Park…). By rules,
 * on the phone, no AI: who an email speaks to, the mailbox it came to, flagged emails, parked and in-progress cards.
 */
object FrontDoor {
    private val GREET = Regex("^\\s*(?:hi|hello|hey|dear|bonjour|bonsoir|salut|allo|allô|cher|chère|chers|hola)\\s+(?:(?:à|a|to)\\s+)?([\\p{L}'’-]+)", RegexOption.IGNORE_CASE)
    private val BARE = Regex("^\\s*(\\p{Lu}[\\p{Ll}'’-]+)\\s*,")
    private val GROUP = setOf("all", "tous", "toutes", "team", "equipe", "everyone", "both", "tout", "everybody", "folks")

    /** Who the message speaks to by its first words: "me", "all", another person's name, or "" (no greeting / name unknown). */
    fun addressedTo(text: String, firstName: String, cc: Boolean): String {
        val m = GREET.find(text) ?: (if (cc) BARE.find(text) else null) ?: return ""
        val n = Paperwork.fold(m.groupValues[1])
        if (n in GROUP) return "all"
        val me = Paperwork.fold(firstName.trim())
        if (me.isBlank()) return ""
        return if (n == me) "me" else m.groupValues[1]
    }

    /** Written to someone else: the user is only in the loop. */
    fun inLoop(board: Board, u: Update): Boolean = addressedTo(u.text, board.about.firstName, u.cc).let { it.isNotBlank() && it != "me" && it != "all" }

    /** The area of an email forwarded from another mailbox: the one learned for that address, else the home area. */
    fun forwardedArea(board: Board, u: Update): String? {
        if (u.to.isBlank()) return null
        val list = Areas.all(board.settings)
        return Rules.valueOf(board, "address", u.to)?.takeIf { v -> list.any { it.id == v } } ?: (list.firstOrNull { !it.work } ?: list.first()).id
    }

    fun learnAddress(board: Board, address: String, area: String, today: LocalDate): Board =
        Rules.learn(board, "address", address, area, "forwarded here: you chose its area", today)

    /** Flagged emails become cards by rule (title, sender, date, first lines), once per email; no AI. */
    fun flaggedCards(board: Board, items: List<Incoming>, now: String): Board {
        val known = board.updates.map { it.mailId }.filter { it.isNotBlank() }.toSet()
        val fresh = items.filter { it.flagged && it.mailId.isNotBlank() && it.mailId !in known }.map { i ->
            val subject = i.text.substringBefore(" — ").take(80)
            Update(BoardOps.newId(), i.source, i.from, i.text.take(400), i.text.substringAfter(" — ", i.text).split(Regex("(?<=[.!?])\\s")).first().take(200),
                createdAt = now, mailId = i.mailId, cc = i.cc, flagged = true, to = i.to, title = subject, at = i.at)
        }
        return if (fresh.isEmpty()) board else board.copy(updates = board.updates + fresh)
    }

    fun parked(u: Update, now: LocalDateTime): Boolean {
        val p = u.park ?: return false
        val today = now.toLocalDate().toString()
        return p.day > today || (p.day == today && p.time.isNotBlank() && now.toLocalTime().toString().take(5) < p.time)
    }

    fun park(board: Board, id: String, park: Park): Board = board.copy(updates = board.updates.map { if (it.id == id) it.copy(park = park) else it })

    /** The next check-in time after [now] ("HH:mm", today) or null when none is left today. */
    fun nextCheckin(board: Board, now: LocalDateTime): String? = board.checks.times.sorted().firstOrNull { it > now.toLocalTime().toString().take(5) }

    /**
     * ➜ Next action: one concrete step, as a quick thing ([where] "quick") or a task / project step ("task", with [project]
     * for a project; a new name creates it), on [day] when given. The card closes and waits "in progress" until it is done.
     */
    fun next(board: Board, id: String, text: String, where: String, project: String, day: LocalDate?, area: String, today: LocalDate): Board {
        val u = board.updates.firstOrNull { it.id == id } ?: return board
        val title = text.trim().ifBlank { return board }
        var b = board
        val prog = if (where == "quick") {
            b = Quick.add(b, listOf(title), day, today, area = area).first
            val q = b.quick.last()
            Progress("quick", q.id, title, "⚡ quick thing" + (day?.let { " · $it" } ?: ""))
        } else {
            val spec = BoardOps.NewTask(title, listOf(u.summary, u.comment).filter { it.isNotBlank() }.joinToString(" "), project = project.trim(), area = area)
            val (b2, task) = if (day != null) BoardOps.addTaskOn(b, spec, day, today).let { it.first to it.second } else BoardOps.addTask(b, spec, today)
            b = b2
            val st = task.steps.lastOrNull { it.title.equals(title, ignoreCase = true) } ?: task.steps.last()
            Progress("step", st.id, title, if (project.isNotBlank()) "📁 ${project.trim()}" else "📋 task")
        }
        b = Updates.dismiss(b, id, "done", u.comment, project.ifBlank { null }, today)
        return b.copy(updates = b.updates.map { if (it.id == id) it.copy(prog = prog) else it })
    }

    /** In progress: cards turned into an action not done yet. One whose action is done (or gone) closes by itself. */
    fun inProgress(board: Board): List<Update> = board.updates.filter { it.prog != null && !it.prog.closed && !progDone(board, it.prog) }

    private fun progDone(board: Board, p: Progress): Boolean = when (p.type) {
        "quick" -> board.quick.firstOrNull { it.id == p.id }?.done ?: true
        else -> board.tasks.flatMap { it.steps }.firstOrNull { it.id == p.id }?.let { it.done || it.closed } ?: true
    }

    /** Done from the card: its action is ticked done too. [drop]: not needed any more (the action stays as it is). */
    fun closeProgress(board: Board, id: String, markDone: Boolean, today: LocalDate): Board {
        val u = board.updates.firstOrNull { it.id == id } ?: return board
        val p = u.prog ?: return board
        var b = board
        if (markDone) b = if (p.type == "quick") Quick.tick(b, p.id, true, today) else BoardOps.mapStep(b, p.id) { it.copy(done = true, outcome = null) }
        return b.copy(updates = b.updates.map { if (it.id == id) it.copy(prog = p.copy(closed = true)) else it })
    }

    /** After a call: each line said becomes a quick thing or a project step ("" = quick thing); the call leaves the list. */
    fun afterCall(board: Board, callId: String, lines: List<Pair<String, String>>, today: LocalDate): Board {
        val c = board.afterCalls.firstOrNull { it.id == callId } ?: return board
        var b = board.copy(afterCalls = board.afterCalls.filter { it.id != callId })
        for ((text, project) in lines.filter { it.first.isNotBlank() }) {
            b = if (project.isBlank()) Quick.add(b, listOf(text.trim()), null, today, area = Areas.guess(b, "${c.who} $text")).first
            else BoardOps.addTask(b, BoardOps.NewTask(text.trim(), "After the call with ${c.who}.", project = project.trim()), today).first
        }
        return b
    }

    /** The project a line said after a call surely belongs to: a project named in it, by its first word. */
    fun guessProject(board: Board, line: String): String {
        val l = " ${Paperwork.fold(line)} "
        return board.projects.map { it.name }.firstOrNull { n -> Paperwork.fold(n).split(" ").firstOrNull { it.length >= 4 }?.let { l.contains(it) } == true }.orEmpty()
    }

    /** A call ended: asked once, when it lasted at least a minute. The same person within 10 minutes is the same call. */
    fun callEnded(board: Board, who: String, mins: Int, at: String): Board {
        if (mins < 1 || who.isBlank()) return board
        if (board.afterCalls.any { Paperwork.fold(it.who) == Paperwork.fold(who) && it.at.take(13) == at.take(13) }) return board
        return board.copy(afterCalls = board.afterCalls + AfterCall(BoardOps.newId(), who.take(60), mins, at))
    }

    fun dropAfterCall(board: Board, id: String): Board = board.copy(afterCalls = board.afterCalls.filter { it.id != id })

    /** A VIP: always comes through at once (emails, messages, calls). */
    fun vip(board: Board, from: String): Boolean {
        val f = " ${Paperwork.fold(from)} "
        return board.checks.keyContacts.any { k -> Paperwork.fold(k).trim().let { it.length >= 2 && f.contains(" $it") } }
    }

    /** Still waiting in the front door (not handled, not parked): the check-in's count. */
    fun waiting(board: Board, now: LocalDateTime): List<Update> = Updates.inbox(board, now.toLocalDate()).filter { !parked(it, now) && it.prog == null }

}
