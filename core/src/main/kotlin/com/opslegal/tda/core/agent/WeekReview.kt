package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.LogEntry
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.ReviewState
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The weekly review: 3 facts measured by the app (never guessed), then 1 pattern and 1 suggestion from the AI,
 * written with a short coach playbook. One change a week; the next review checks whether it helped.
 */
object WeekReview {

    /** Facts for one week. Cells "moved" were pushed ahead of time or not done on their day (red). */
    data class Facts(
        val monday: LocalDate,
        val until: LocalDate,
        val done: Int,
        val planned: Int,
        val moved: Int,
        val morningDone: Int,
        val morningAll: Int,
        val afternoonDone: Int,
        val afternoonAll: Int,
        val movedTitles: List<String>,
        val movedHeavy: Int,
        val previousPct: Int?,
        val projects: List<String>,
        /** How each running project moved this week, for the review's Projects part. */
        val projectMoves: List<ProjectMove> = emptyList(),
        /** Cells of the last 30 days still red (not done, nothing decided). */
        val stillRed: Int = 0,
        /** Why cells moved, in the user's words ("title: why"), from Push with a reason. */
        val reasons: List<String> = emptyList(),
        val hardestDay: Pair<LocalDate, String>?,
    ) {
        val pct: Int get() = if (planned == 0) 0 else done * 100 / planned
    }

    /** One project's week: steps done in it, where it stands, and whether it is on track. */
    data class ProjectMove(val name: String, val doneThisWeek: Int, val percent: Int, val status: String, val stalled: Boolean)

    data class Advice(val pattern: String, val suggestion: String, val rule: String, val check: String, val lastWeek: String)

    /** The week to review: this week from Friday 1 pm to Sunday, last week on Monday morning. Null otherwise. */
    fun weekToReview(now: LocalDateTime): LocalDate? {
        val day = now.dayOfWeek
        val monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            day == DayOfWeek.FRIDAY && now.hour >= 13 -> monday
            day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY -> monday
            day == DayOfWeek.MONDAY && now.hour < 12 -> monday.minusWeeks(1)
            else -> null
        }
    }

    /** A review is waiting: it is review time and this week was not reviewed yet. */
    fun due(board: Board, now: LocalDateTime): Boolean = weekToReview(now)?.let { it.toString() != board.review.week } ?: false

    fun facts(board: Board, monday: LocalDate, today: LocalDate): Facts {
        val until = minOf(monday.plusDays(6), today)
        fun inWeek(d: String?, from: LocalDate = monday, to: LocalDate = until) =
            d != null && runCatching { LocalDate.parse(d) }.getOrNull()?.let { !it.isBefore(from) && !it.isAfter(to) } == true
        val cells = board.tasks.flatMap { t -> t.steps.map { t to it } }
            .filter { (t, s) -> inWeek(s.date) && s.slot != null && s.outcome == null && t.kindOf(s) != TaskKind.MEETING }
        // Not done on their day: still open on a past day, or the red record left when the work went on.
        val missed = board.tasks.flatMap { t ->
            t.steps.filter { s ->
                inWeek(s.date) && s.slot != null && t.kindOf(s) != TaskKind.MEETING &&
                    (s.outcome == Outcome.MISSED || BoardOps.isMissed(s, today))
            }.map { s -> LogEntry(s.date!!, s.slot, "missed", Planner.cellTitle(t, s), t.effortOf(s) == Effort.HEAVY, t.personal) }
        }
        val moved = board.log.filter { inWeek(it.date) && it.what != "reason" } + missed
        val reasons = board.log.filter { inWeek(it.date) && it.why.isNotBlank() }.map { "${it.title}: ${it.why}" }.distinct().take(10)
        val morning = { slot: Int? -> slot != null && slot <= 1 }
        val doneCells = cells.filter { it.second.done }
        // Last week, for the trend: done cells against done + moved.
        val prevMonday = monday.minusWeeks(1)
        val prevDone = board.tasks.flatMap { it.steps }.count { it.done && inWeek(it.date, prevMonday, prevMonday.plusDays(6)) }
        val prevMoved = board.log.count { it.what != "reason" && inWeek(it.date, prevMonday, prevMonday.plusDays(6)) } +
            board.tasks.flatMap { it.steps }.count { s -> inWeek(s.date, prevMonday, prevMonday.plusDays(6)) && (s.outcome == Outcome.MISSED || BoardOps.isMissed(s, today)) }
        val prevPct = if (prevDone + prevMoved >= 5) prevDone * 100 / (prevDone + prevMoved) else null
        // The hardest day: the most cells moved.
        val hardest = moved.groupBy { it.date }.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 2 }
            ?.let { (d, l) -> LocalDate.parse(d) to "${l.size} cells moved" }
        val running = board.projects.filter { !Projects.isIdea(board, it) }
        val moves = running.mapNotNull { p ->
            val stats = Projects.stats(board, p)
            val doneNow = BoardOps.projectTask(board, p.name)?.steps.orEmpty().count { it.done && it.outcome == null && inWeek(it.date) }
            // Finished before this week: nothing to say.
            if (stats.finished && doneNow == 0) return@mapNotNull null
            val end = stats.end
            val status = when {
                stats.finished -> "finished"
                end.late -> "at risk: ends ${end.end}, deadline ${end.deadline}"
                end.end == null -> "not planned yet"
                end.deadline != null -> "ends ${end.end}, deadline ${end.deadline}, on track"
                else -> "ends ${end.end}"
            }
            ProjectMove(p.name, doneNow, stats.percent, status, stalled = doneNow == 0 && !stats.finished)
        }.sortedWith(compareByDescending<ProjectMove> { it.status.startsWith("at risk") }.thenByDescending { it.stalled })
        val projects = moves.take(6).map { m ->
            "${m.name}: ${m.doneThisWeek} step${if (m.doneThisWeek == 1) "" else "s"} done this week, ${m.percent}% overall, ${m.status}" +
                if (m.stalled) " (no step done this week)" else ""
        }
        val monthAgo = today.minusDays(30).toString()
        val stillRed = board.tasks.sumOf { t -> t.steps.count { BoardOps.isMissed(it, today) && it.date!! >= monthAgo } }
        return Facts(
            monday, until,
            // What the week asked: cells done, plus cells that moved (a moved cell re-placed later counts once).
            done = doneCells.size, planned = doneCells.size + moved.size, moved = moved.size,
            morningDone = doneCells.count { morning(it.second.slot) },
            morningAll = doneCells.count { morning(it.second.slot) } + moved.count { morning(it.slot) },
            afternoonDone = doneCells.count { !morning(it.second.slot) },
            afternoonAll = doneCells.count { !morning(it.second.slot) } + moved.count { !morning(it.slot) },
            movedTitles = moved.map { it.title }.distinct().take(8),
            movedHeavy = moved.count { it.heavy },
            previousPct = prevPct,
            projects = projects,
            projectMoves = moves,
            stillRed = stillRed,
            reasons = reasons,
            hardestDay = hardest,
        )
    }

    /**
     * The coach playbook: what the AI applies, in plain words. Kept short and visible on purpose: it shapes how
     * the assistant talks to people.
     */
    const val PLAYBOOK = """COACH PLAYBOOK (apply, never lecture):
- Facts first: use only the numbers given. Never invent a pattern; if the data is thin, say so and suggest nothing big.
- One change at a time: exactly one suggestion, concrete, for next week, that the planner can follow as a rule.
- If-then plans beat intentions: write the suggestion as a trigger and an action ("Long reading goes in the first morning cell").
- Tiny first step for what keeps moving: a 10-minute start, not more willpower.
- People underestimate time: use their own slip (cells moved) to suggest buffers, not generic advice.
- Match energy: heavy work where their data shows they finish it.
- Reward right after effort: an easy or enjoyable cell after a heavy one.
- Projects: what blocks others first; detail only the next 2-3 steps; flag scope creep and deadlines at risk.
- Kindness after a miss: name a hard day plainly, without blame; self-forgiveness reduces procrastination. No streak guilt.
- Dale Carnegie, always: start with sincere appreciation of what went well; point to a pattern indirectly, as a question; let them save face;
  make the fix sound easy; praise the slightest improvement (a fix that worked); give them a fine reputation to live up to.
- A fix that slipped again: never blame; propose another way for the same cause, or a different cause.
- You are a coach, not a therapist: no diagnosis, no medical advice. They decide."""

    fun prompt(board: Board, f: Facts): String = buildString {
        appendLine(PLAYBOOK)
        appendLine()
        appendLine("THEIR WEEK (${f.monday} to ${f.until}), measured by the app:")
        appendLine("- cells done ${f.done} of ${f.planned} (${f.pct}%)" + (f.previousPct?.let { ", previous week $it%" } ?: ""))
        appendLine("- moved without being done: ${f.moved} (${f.movedHeavy} heavy): ${f.movedTitles.joinToString("; ")}")
        appendLine("- morning cells done ${f.morningDone} of ${f.morningAll}; afternoon cells done ${f.afternoonDone} of ${f.afternoonAll}")
        f.hardestDay?.let { appendLine("- hardest day: ${it.first.dayOfWeek} (${it.second})") }
        if (f.reasons.isNotEmpty()) appendLine("- why cells moved, in their words: ${f.reasons.joinToString("; ")}")
        com.opslegal.tda.core.plan.Habits.facts(board, f.until).takeIf { it.isNotBlank() }?.let { appendLine("HABITS (patterns the app found, and the fixes the user chose):"); append(it) }
        f.projects.forEach { appendLine("- project $it") }
        if (f.stillRed > 0) appendLine("- cells of the last 30 days still red (not done, nothing decided): ${f.stillRed}")
        appendLine("What matters to them: ${board.values.joinToString { "${it.name} ${it.weight}" }.ifBlank { "not set" }}. They put off: ${board.about.hard.joinToString().ifBlank { "-" }}.")
        val r = board.review
        if (r.suggestion.isNotBlank()) {
            appendLine("Last week's suggestion: \"${r.suggestion}\" — ${if (r.ruleId.isNotBlank()) "they tried it" else "they did not try it"}. Cells moved then: ${r.movedBefore}, now: ${f.moved}.")
        }
        if (r.declined.isNotEmpty()) appendLine("Never suggest again: ${r.declined.takeLast(10).joinToString("; ")}")
        appendLine()
        appendLine("Write in the language of their task titles, short and warm:")
        appendLine("\"pattern\": one sentence, the clearest pattern in the numbers (or that the data is too thin).")
        appendLine("\"suggestion\": one concrete change for next week, one or two sentences.")
        appendLine("\"rule\": the same change as a short rule the planner can follow.")
        appendLine("\"check\": how next week's review will see if it helped (one short sentence).")
        appendLine("\"lastWeek\": if there was a suggestion last week, keep it or drop it, with the numbers (one sentence); else empty.")
        appendLine("""Reply with only {"pattern":"","suggestion":"","rule":"","check":"","lastWeek":""}""")
    }

    suspend fun advise(provider: LlmProvider, board: Board, f: Facts): Advice {
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt(board, f))), emptyList()).text
        return parse(reply) ?: error("The review could not be written. Try again.")
    }

    fun parse(reply: String): Advice? {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
        if (s("suggestion").isBlank()) return null
        return Advice(s("pattern"), s("suggestion"), s("rule").ifBlank { s("suggestion") }, s("check"), s("lastWeek"))
    }

    /** Try it: the suggestion becomes a Playbook rule; the week is reviewed. */
    fun accept(board: Board, monday: LocalDate, f: Facts, a: Advice): Board {
        val withRule = if (RuleGuard.refuse(a.rule) == null) BoardOps.addRule(board, a.rule, by = "assistant", at = monday.toString()) else board
        val ruleId = withRule.rules.firstOrNull { it.text.equals(a.rule.trim(), ignoreCase = true) }?.id.orEmpty()
        return withRule.copy(review = board.review.copy(week = monday.toString(), suggestion = a.suggestion, ruleId = ruleId, movedBefore = f.moved))
    }

    /** Not for me: never proposed again; the week is reviewed. */
    fun decline(board: Board, monday: LocalDate, f: Facts, a: Advice): Board =
        board.copy(review = ReviewState(monday.toString(), a.suggestion, "", f.moved, (board.review.declined + a.suggestion).takeLast(20)))
}
