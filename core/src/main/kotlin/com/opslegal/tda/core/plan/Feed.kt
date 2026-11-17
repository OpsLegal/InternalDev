package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.FeedStat
import com.opslegal.tda.core.model.MissedCall
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.QuickThing
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.cellsPerDay
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 🎲 The feed: one card at a time. Swipe right acts now (do it here, or plan it at its best moment); reality first
 * (fresh notifications, today's quick things and cells, on my mind, parked projects), then, once real things are done,
 * the cards for the person: people, the weakest value, a method that works, a mindful minute, a dream, remember.
 * It always ends. By rules on the phone; the assistant only writes a reply or a draft when the user asks.
 */
object Feed {
    sealed class Card {
        data class Note(val u: Update) : Card()
        data class Call(val c: MissedCall) : Card()
        data class Quick(val q: QuickThing, val mind: Boolean) : Card()
        data class Cell(val task: Task, val step: Step) : Card()
        data class Parked(val p: Project) : Card()
        object Gate : Card()
        data class Reach(val name: String) : Card()
        object Thank : Card()
        data class Weak(val value: String, val pct: Int, val bucket: String, val q: Int) : Card()
        data class Method(val m: Way) : Card()
        object Breath : Card()
        data class DreamQ(val q: String) : Card()
        object Dream : Card()
        data class Remember(val text: String) : Card()
        object End : Card()
    }

    /** A method worth knowing: its quote, who said it, what it teaches, and what to try now ([tryIt]: two, frog, box, stack, draft, review). */
    data class Way(val quote: String, val by: String, val method: String, val tryIt: String)

    val METHODS = listOf(
        Way("If it takes less than two minutes, do it now.", "David Allen, Getting Things Done", "The 2-minute rule: tiny things done at once never pile up.", "two"),
        Way("Eat a live frog first thing in the morning, and nothing worse will happen to you the rest of the day.", "attributed to Mark Twain", "Eat the frog: the hardest task first, before the day takes your energy.", "frog"),
        Way("Work expands so as to fill the time available for its completion.", "C. Northcote Parkinson", "Timeboxing: give a task a fixed time, and it shrinks to fit.", "box"),
        Way("You do not rise to the level of your goals. You fall to the level of your systems.", "James Clear, Atomic Habits", "Habit stacking: tie a new habit to one you already have.", "stack"),
        Way("The key is not to prioritize what's on your schedule, but to schedule your priorities.", "Stephen Covey", "Big rocks first: your most important thing gets the first cell, not the leftovers.", "frog"),
        Way("Done is better than perfect.", "Sheryl Sandberg", "The ugly first draft: 15 minutes of imperfect progress beats waiting to be ready.", "draft"),
        Way("If you want a habit to stick, make it tiny.", "BJ Fogg, Tiny Habits", "Start so small you can't fail: 2 push-ups, one line, one email.", "stack"),
        Way("What gets measured gets managed.", "Peter Drucker", "The weekly review: 15 minutes on Sunday to see what moved and plan what's next.", "review"),
    )
    val DREAM_QS = listOf("What did you love doing as a kid?", "If you had a free year, what would you do?", "What do you regret not trying yet?", "What would you do if you knew you couldn't fail?")
    private val VALUE_STEPS = mapOf(
        "Health" to listOf("🚶 A 15-minute walk", "💧 2 big glasses of water", "😴 In bed by 22:30"),
        "Family" to listOf("📞 Call a family member", "🍽 Plan a family dinner", "🎲 20 minutes of play"),
        "Relationships" to listOf("💌 Write to a friend", "☕ Plan a coffee with someone", "🙏 Thank someone"),
        "Joy" to listOf("🎵 Your favourite album", "🌳 Time outside", "🎨 30 minutes on a hobby"),
        "Money" to listOf("💲 Check this month's spending", "🧾 Pay one bill", "📈 15 minutes on your savings"),
        "Reputation" to listOf("🗣 Share one win", "📝 Write 3 lines for your site", "🤝 Message a past client"),
        "Organized" to listOf("🗂 10 minutes of tidying", "📥 Empty one inbox", "🗓 Plan tomorrow"),
        "Methodic" to listOf("🗓 Plan tomorrow", "📋 Review your week", "🎯 One thing first tomorrow"),
    )
    fun steps(value: String): List<String> = VALUE_STEPS[value] ?: listOf("⏱ 10 minutes on it today", "🗓 A block for it this week", "🤝 Ask someone to join you")

    /** Inspiration comes after reality: at least this many things done (this round, or today). */
    const val EARN = 3

    /** What a small thing asks for, by its words: a call, a message or email, a document to prepare, or just doing it. */
    fun workKind(text: String): String {
        val t = Paperwork.fold(text)
        return when {
            Regex("\\b(call|appel|appeler|rappel|phone|telephon)").containsMatchIn(t) -> "call"
            Regex("\\b(reply|repond|email|e-mail|courriel|message|write to|ecrire a|confirm|confirmer|thank|remercier)").containsMatchIn(t) || Regex("\\b(send|envoyer)\\b.*\\b(to|a)\\b").containsMatchIn(t) -> "msg"
            Regex("\\b(prepare|preparer|draft|rediger|quote|devis|letter|lettre|report|rapport|document|proposal|offre|summary|resume|agenda|notes)").containsMatchIn(t) -> "doc"
            else -> "do"
        }
    }

    fun weekKey(today: LocalDate) = today.with(DayOfWeek.MONDAY).toString()
    fun stats(board: Board, today: LocalDate): FeedStat = board.feedStats[weekKey(today)] ?: FeedStat()
    fun count(board: Board, today: LocalDate, cleared: Int = 0, inspired: Int = 0, acted: Int = 0): Board {
        val s = stats(board, today)
        return board.copy(feedStats = board.feedStats + (weekKey(today) to s.copy(cleared = s.cleared + cleared, inspired = s.inspired + inspired, acted = s.acted + acted)))
    }
    fun doneToday(board: Board, today: LocalDate): Int =
        board.tasks.sumOf { t -> t.steps.count { it.done && it.date == today.toString() } } + board.quick.count { it.done && it.doneOn == today.toString() }

    /** The practical deck: fresh notifications and calls, today's quick things and cells, on my mind, parked; then the gate and the end. */
    fun deck(board: Board, inbox: List<Update>, today: LocalDate): List<Card> {
        val out = mutableListOf<Card>()
        inbox.filter { it.prog == null && it.source != CalendarAlerts.SOURCE }.take(8).forEach { out += Card.Note(it) }
        board.missed.take(3).forEach { out += Card.Call(it) }
        val picks = Quick.todayIds(board, today)
        Quick.onDay(board, today, today).filter { !it.done }.take(5).forEach { out += Card.Quick(it, false) }
        board.tasks.flatMap { t -> t.steps.filter { it.date == today.toString() && !it.closed && (it.slot ?: 99) < board.cellsPerDay }.map { Card.Cell(t, it) } }.forEach { out += it }
        Quick.pool(board).filter { it.id !in picks }.take(3).forEach { out += Card.Quick(it, true) }
        board.projects.filter { it.pause != null }.take(2).forEach { out += Card.Parked(it) }
        out += Card.Gate; out += Card.End
        return out
    }

    /** The cards for the person, once earned: people, the weakest value, a method, a mindful minute, a dream, remember. Soft first when tired or low. */
    fun deep(board: Board, today: LocalDate, round: Int, soft: Boolean): List<Card> {
        val out = mutableListOf<Card>()
        val vip = board.checks.keyContacts
        if (vip.isNotEmpty()) out += Card.Reach(vip[today.dayOfMonth % vip.size])
        out += Card.Thank
        val week = runCatching { Balance.of(board, today) }.getOrNull()
        week?.lines?.values?.minByOrNull { it.pct }?.let { l -> out += Card.Weak(l.value.name, (l.pct * 100).toInt(), l.value.bucket, round % 3) }
        out += Card.Method(method(board, round))
        out += Card.Breath
        if (board.projects.count { it.dream } < 3) out += Card.DreamQ(DREAM_QS[round % DREAM_QS.size])
        if (board.projects.any { it.dream }) out += Card.Dream
        val fin = board.projects.firstOrNull { p -> Projects.stats(board, p).let { it.total > 0 && it.finished } }
        out += Card.Remember(fin?.let { "You finished ${it.name}." } ?: "${week?.let { w -> w.lines.values.sumOf { it.done }.toInt() } ?: 0} cells done this week.")
        if (!soft) return out
        val first = out.filter { it is Card.Breath || it is Card.Reach || it == Card.Thank }
        return first + out.filter { it !in first }
    }

    /** The method that fits now: many pushes → eat the frog or timebox; few routines → habits; lots of small things → 2 minutes. */
    fun method(board: Board, round: Int): Way {
        val pushed = board.tasks.flatMap { it.steps }.count { !it.done && it.pushLog.size > 1 }
        val pick = when { pushed > 2 -> setOf("frog", "box"); board.routines.size < 3 -> setOf("stack"); Quick.pool(board).size > 6 -> setOf("two"); else -> setOf("draft", "review", "frog") }
        val list = METHODS.filter { it.tryIt in pick }
        return list[round % list.size]
    }

    /** Today's open cells in the planner's order (for the frog, the timebox, the ugly draft). */
    fun top(board: Board, today: LocalDate): List<Pair<Task, Step>> {
        val w = Planner.effectiveWeights(board)
        return board.tasks.flatMap { t -> t.steps.filter { !it.closed && it.date != null && it.date >= today.toString() }.map { t to it } }
            .sortedBy { (t, s) -> Order.key(board, t, s, today, w) }
    }

    /** The reply or draft the assistant writes from the user's main idea: only the message (or the task), the idea and two of their own replies. */
    fun draftPrompt(kind: String, from: String, text: String, title: String, idea: String, style: List<String>): String = buildString {
        if (kind == "reply") {
            appendLine("Write the user's reply to this message, from their main idea. Same language and tone as the message. Short, warm, professional. Never invent facts.")
            appendLine("Message from $from: \"\"\"${text.take(1200)}\"\"\"")
        } else {
            appendLine("Write a short ${if (kind == "doc") "document (clear headings or bullets, ready to send or paste)" else "message"} for this task, from the user's main idea. In the language of the task. Never invent facts; leave [brackets] where a fact is missing.")
            appendLine("Task: \"$title\"")
        }
        appendLine("The user's main idea: \"$idea\"")
        if (style.isNotEmpty()) { appendLine("How the user writes (style only, never reuse the content):"); style.takeLast(2).forEach { appendLine("---\n$it") } }
        appendLine("Reply with the text only: no subject line, no quotes around it.")
    }

    /** Without the assistant: the idea, shaped, for the user to finish. */
    fun fallback(kind: String, from: String, title: String, idea: String, me: String): String {
        val i = idea.trim().replaceFirstChar { it.uppercase() }
        return when (kind) {
            "reply" -> "Bonjour ${from.split(' ', '(', '<').first()},\n\n$i.\n\nMerci,\n$me"
            "doc" -> "$title\n\n" + idea.split(',', ';').joinToString("\n") { "• " + it.trim() } + "\n\n[To complete]"
            else -> "$i."
        }
    }
}
