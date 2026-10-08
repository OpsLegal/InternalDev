package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.LogEntry
import com.opslegal.tda.core.model.SLOTS_PER_DAY
import com.opslegal.tda.core.model.TaskKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The assistant speaks first: free cells today while work waits later (sooner is better), cells pushed again and
 * again, projects at risk or that could end sooner, a week with no Nourish. One row each, with its actions.
 */
object Flags {
    data class Flag(val id: String, val kind: String, val icon: String, val title: String, val sub: String, val actions: List<Pair<String, String>>, val red: Boolean = false)

    val notNowReasons = listOf("Something unplanned came up", "Bigger than it looks", "Time off and pleasure")

    private fun day(d: LocalDate, today: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth +
        (if (d.isAfter(today.plusDays(21))) " " + d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) else "")

    fun all(board: Board, today: LocalDate): List<Flag> {
        val out = mutableListOf<Flag>()
        val used = board.tasks.flatMap { it.steps }.filter { it.date == today.toString() && it.slot != null }.mapNotNull { it.slot }.toSet()
        val free = SLOTS_PER_DAY - used.size
        if (Holidays.isWorkDay(today, board.settings) && free > 0) {
            board.tasks.filter { !it.isProject && !it.errands && it.kind == TaskKind.TASK && it.fixedDate == null }
                .flatMap { t -> t.steps.filter { s -> !s.closed && s.date != null && LocalDate.parse(s.date).isAfter(today) && (s.notBefore == null || !LocalDate.parse(s.notBefore).isAfter(today)) }.map { t to it } }
                .sortedBy { it.second.date }.take(minOf(3, free))
                .forEach { (t, s) ->
                    out += Flag("now:${s.id}", "task", "⚡", Planner.cellTitle(t, s),
                        "planned ${day(LocalDate.parse(s.date), today)} · $free free cell${if (free > 1) "s" else ""} today: sooner is better",
                        listOf("now:${s.id}" to "Do it today", "notnow:now:${s.id}" to "Not now"))
                }
        }
        board.tasks.filter { it.pushes >= 2 && it.steps.any { s -> !s.closed } }.forEach { t ->
            out += Flag("push:${t.id}", "task", "↷", t.title, "pushed ${t.pushes} times: a smaller first step?",
                listOf("split:${t.id}" to "Make it smaller", "talk:${t.id}" to "Talk about it", "notnow:push:${t.id}" to "Not now"))
        }
        board.projects.filter { !Projects.isIdea(board, it) }.forEach { p ->
            val e = Projects.end(board, p.name)
            val h = BoardOps.projectTask(board, p.name) ?: return@forEach
            if (e.open == 0) return@forEach
            val intention = if (p.intention.isNotBlank()) "“${p.intention}” · " else ""
            if (e.late && e.end != null && e.deadline != null) {
                out += Flag("risk:${p.name}", "project", "⚠", "${p.name}: at risk", "${intention}ends ${day(e.end, today)}, deadline ${day(e.deadline, today)}",
                    listOf("popen:${p.name}" to "Open", "pai:${p.name}" to "Ask AI"), red = true)
                return@forEach
            }
            val movable = sooner(board, p.name, today)
            if (e.deadline != null && e.end != null && movable > 0 && p.name !in board.keepDates)
                out += Flag("soon:${p.name}", "project", "📁", p.name,
                    "${intention}ends ${day(e.end, today)}, deadline ${day(e.deadline, today)} · $movable step${if (movable > 1) "s don't" else " doesn't"} wait for anyone: could end sooner",
                    listOf("sooner:${p.name}" to "Bring forward", "keep:${p.name}" to "Keep: the date is set"))
        }
        if (board.gbn) {
            val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val week = board.tasks.flatMap { t -> t.steps.filter { s -> s.outcome == null && s.date?.let { LocalDate.parse(it) }?.let { !it.isBefore(mon) && !it.isAfter(mon.plusDays(6)) } == true }.map { t } }
            val nourish = week.any { t -> Gbn.levelsOf(board, t).keys.any { k -> board.values.any { it.name == k && it.bucket == Gbn.NOURISH } } }
            if (week.size >= 5 && !nourish)
                out += Flag("nourish:$mon", "balance", "●", "No Nourish cell this week", "People, health, joy: one cell keeps the week balanced",
                    listOf("addone:nourish" to "Add one", "notnow:nourish:$mon" to "Not now"))
        }
        return out.filter { board.notNow[it.id] != today.toString() }
    }

    /** Steps of a project that wait only for their paced date (no one else), so they could come sooner. */
    fun sooner(board: Board, name: String, today: LocalDate): Int =
        BoardOps.projectTask(board, name)?.steps.orEmpty().count { s ->
            !s.closed && s.waitDays == 0 && !s.pinned && s.notBefore != null && LocalDate.parse(s.notBefore).isAfter(today)
        }

    /** Bring forward: steps that wait for no one lose their paced date and take the first free cells. */
    fun bringForward(board: Board, name: String, today: LocalDate): Board {
        val h = BoardOps.projectTask(board, name) ?: return board
        var b = board
        h.steps.filter { s -> !s.closed && s.waitDays == 0 && !s.pinned && s.notBefore != null && LocalDate.parse(s.notBefore).isAfter(today) }
            .forEach { s -> b = BoardOps.mapStep(b, s.id) { it.copy(notBefore = today.toString(), date = null, slot = null) } }
        return Planner.plan(b, today).board
    }

    /** Do it today: the cell moves to a free cell today. */
    fun doToday(board: Board, stepId: String, today: LocalDate): Board = BoardOps.moveStep(board, stepId, today) ?: board

    fun notNow(board: Board, id: String, title: String, why: String, today: LocalDate): Board = board.copy(
        notNow = board.notNow.filterValues { it == today.toString() } + (id to today.toString()),
        notNowWhy = (board.notNowWhy + LogEntry(today.toString(), what = why, title = title)).takeLast(30),
    )
}
