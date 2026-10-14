package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Value
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * The week's barometer.
 * Capacity C = your done cells, average of your last 4 weeks + 10% (the first weeks: 70% of the cells your week has).
 * Aim of a value = C × its weight ÷ the sum of weights. Only done cells count: one cell, split ⅔ / ⅓ when it serves two.
 * A ticked routine adds ¼ cell (at most ¼ of the aim). What is beyond the aim is shown, never counted, and never hides a cost.
 * Open loops cost Organized: ½ per red cell, ¼ per push after the first; done gives it all back.
 * Delays cost Reputation, Money and Invest from the first push: ¼ per push, ½ when red; done gives half back.
 * Methodic = ½ the cells built for it + ½ Flow (cells done on their day, routines kept, steady days).
 * Section % = counted ÷ aims. Week % = counted ÷ C.
 */
object Balance {
    val DELAY_COSTS = setOf("Reputation", "Money", "Invest")
    const val ORGANIZED = "Organized"
    const val METHODIC = "Methodic"

    /** One value this week, in cells. [pct] 0..1 of its aim. */
    data class Line(
        val value: Value, val aim: Double, val done: Double, val routines: Double, val cost: Double,
        val counted: Double, val pct: Double, val over: Double, val from: List<String>, val why: List<String>, val built: Double? = null,
    )

    data class Week(
        val capacity: Int, val how: String, val lines: Map<String, Line>,
        /** Per category, 0..100. */
        val pillar: Map<String, Int>,
        /** 0..100. */
        val week: Int,
        /** Share of the week's plannable days gone by, 0..1. */
        val pace: Double,
        /** 0..1, null when there is nothing to judge yet. */
        val flow: Double?, val flowParts: List<Pair<String, Double>>,
        /** The value that matters most and is furthest behind (under 80%). */
        val opportunity: String?,
        val doneCount: Int,
    )

    fun monday(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun inWeek(d: String?, mon: LocalDate) = d != null && d >= mon.toString() && d <= mon.plusDays(6).toString()

    /** What a cell serves, as shares of one cell: 1 value gets it all, 2 share it ⅔ / ⅓. Unrated: Reputation in a project, else Organized. */
    fun sharesOf(board: Board, task: Task): Map<String, Double> {
        val levels = Gbn.levelsOf(board, task).ifEmpty { mapOf((if (task.project.isNotBlank()) "Reputation" else ORGANIZED) to 1) }
        val ent = levels.entries.filter { (k, l) -> l > 0 && board.values.any { it.name == k && it.weight > 0 } }.sortedByDescending { it.value }.take(2)
        return when (ent.size) {
            1 -> mapOf(ent[0].key to 1.0)
            2 -> mapOf(ent[0].key to 2.0 / 3, ent[1].key to 1.0 / 3)
            else -> emptyMap()
        }
    }

    fun plannableDays(board: Board, mon: LocalDate): List<LocalDate> =
        (0L..6L).map { mon.plusDays(it) }.filter { d -> Areas.all(board.settings).any { Areas.canPlan(it, d, board.settings) } }

    fun capacity(board: Board, today: LocalDate): Pair<Int, String> {
        val mon = monday(today)
        val hist = (1..4).map { k ->
            val a = mon.minusWeeks(k.toLong())
            board.tasks.sumOf { t -> t.steps.count { it.done && it.outcome == null && inWeek(it.date, a) } }
        }.filter { it > 0 }
        if (hist.size >= 2) return (hist.average() * 1.1).roundToInt().coerceIn(10, 35) to "your last ${hist.size} weeks + 10%"
        val n = plannableDays(board, mon).size * 5
        return (n * 0.7).roundToInt().coerceAtLeast(10) to "70% of the $n cells your week has"
    }

    private class Acc(val value: Value, val aim: Double) {
        var done = 0.0; var routines = 0.0; var cost = 0.0
        val from = mutableListOf<String>(); val why = mutableListOf<String>()
    }

    fun r1(x: Double): String = ((x * 10).roundToInt() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }

    fun of(board: Board, today: LocalDate): Week {
        val mon = monday(today)
        val vals = board.values.filter { it.weight > 0 }
        val sumW = vals.sumOf { it.weight }.coerceAtLeast(1)
        val (c, how) = capacity(board, today)
        val acc = vals.associate { it.name to Acc(it, c.toDouble() * it.weight / sumW) }
        val all = board.tasks.flatMap { t -> t.steps.map { t to it } }
        val byId = all.associateBy { it.second.id }
        val todayS = today.toString()
        val done = all.filter { (_, s) -> s.done && s.outcome == null && inWeek(s.date, mon) && s.date!! <= todayS }
        done.forEach { (t, s) -> sharesOf(board, t).forEach { (k, sh) -> acc[k]?.let { it.done += sh; it.from += "${Planner.cellTitle(t, s)} +${r1(sh)}" } } }
        // Open loops and delays.
        var orgCost = 0.0
        val loops = mutableListOf<String>()
        all.forEach { (t, s) ->
            val pushes = if (s.outcome != null) 0 else s.pushLog.count { inWeek(it, mon) }
            val redOpen = !s.done && s.outcome == null && s.date != null && s.date < todayS
            val record = s.outcome == Outcome.MISSED && inWeek(s.date, mon)
            val back = if (record) s.origin?.let { byId[it]?.second } else null
            val recordOpen = record && back?.done != true
            var org = 0.0
            if (redOpen || recordOpen) org += 0.5
            if (!s.done && s.outcome == null && pushes > 1) org += 0.25 * (pushes - 1)
            if (org > 0) { orgCost += org; loops += "${Planner.cellTitle(t, s)} −${r1(org)}" }
            val delay = 0.25 * pushes + if (redOpen || record) 0.5 else 0.0
            if (delay == 0.0) return@forEach
            val gaveBack = s.done || (record && back?.done == true)
            sharesOf(board, t).forEach { (k, sh) ->
                val a = acc[k] ?: return@forEach
                if (k !in DELAY_COSTS) return@forEach
                val cost = delay * sh * if (gaveBack) 0.5 else 1.0
                a.cost += cost; a.why += "${Planner.cellTitle(t, s)} −${r1(cost)}"
            }
        }
        acc[ORGANIZED]?.let { it.cost += orgCost; it.why += loops }
        // Routines ticked in the daily review: ¼ cell each, at most ¼ of the aim.
        var ticked = 0; var expected = 0
        (0L..6L).map { mon.plusDays(it) }.filter { !it.isAfter(today) }.forEach { d ->
            val ticks = board.routineDone[d.toString()].orEmpty()
            board.routines.filter { d.dayOfWeek.value in it.days }.forEach { r ->
                val yes = r.id in ticks
                if (d.isBefore(today) || yes) expected++
                if (!yes) return@forEach
                ticked++
                val ent = r.serve.entries.filter { acc.containsKey(it.key) }.sortedByDescending { it.value }.take(2)
                val parts = if (ent.size == 2) listOf(2.0 / 3, 1.0 / 3) else listOf(1.0)
                ent.forEachIndexed { i, e -> acc.getValue(e.key).routines += 0.25 * parts[i] }
            }
        }
        acc.values.forEach { it.routines = minOf(it.routines, it.aim / 4) }
        // Flow, for Methodic: cells done on their day, routines kept, steady days (2 to 5 cells, at most 2 heavy).
        val past = plannableDays(board, mon).filter { it.isBefore(today) }
        val steady = past.count { d ->
            val cells = all.filter { (_, s) -> s.done && s.outcome == null && s.date == d.toString() }
            cells.size in 2..5 && cells.count { (t, s) -> t.effortOf(s) == Effort.HEAVY } <= 2
        }
        val parts = listOfNotNull(
            done.takeIf { it.isNotEmpty() }?.let { l -> "on their day" to l.count { (_, s) -> s.pushLog.none { inWeek(it, mon) } }.toDouble() / l.size },
            expected.takeIf { it > 0 }?.let { "routines kept" to ticked.toDouble() / it },
            past.takeIf { it.isNotEmpty() }?.let { "steady days" to steady.toDouble() / it.size },
        )
        val flow = if (parts.isEmpty()) null else parts.sumOf { it.second } / parts.size
        // Costs come after the cap: doing more elsewhere never hides a push, so each one is felt from the first.
        val lines = acc.mapValues { (k, a) ->
            val earned = a.done + a.routines
            val capped = (minOf(earned, a.aim) - a.cost).coerceAtLeast(0.0)
            val over = if (earned - a.cost > a.aim * 1.2) earned - a.cost - a.aim else 0.0
            if (k == METHODIC) {
                val built = minOf(1.0, capped / a.aim)
                val pct = if (flow == null) built else (built + flow) / 2
                Line(a.value, a.aim, a.done, a.routines, a.cost, pct * a.aim, pct, over, a.from, a.why, built)
            } else Line(a.value, a.aim, a.done, a.routines, a.cost, capped, capped / a.aim, over, a.from, a.why)
        }
        val pillar = Gbn.buckets.associateWith { b ->
            val l = lines.values.filter { it.value.bucket.ifBlank { Gbn.GROUND } == b }
            val aim = l.sumOf { it.aim }
            if (aim > 0) (l.sumOf { it.counted } / aim * 100).roundToInt() else 0
        }
        val week = (lines.values.sumOf { it.counted } / c * 100).roundToInt()
        val planDays = plannableDays(board, mon)
        val pace = if (planDays.isEmpty()) 0.0 else minOf(1.0, planDays.count { it.isBefore(today) }.toDouble() / planDays.size)
        val opp = lines.entries.filter { it.value.pct < 0.8 }.maxByOrNull { it.value.value.weight * (1 - it.value.pct) }?.key
        return Week(c, how, lines, pillar, week, pace, flow, parts, opp, done.size)
    }

    /** Choosing: what one more done cell of this task would add (bigger when it fills what matters and is missing). */
    fun gainOf(board: Board, task: Task, week: Week): Double =
        sharesOf(board, task).entries.sumOf { (k, sh) -> week.lines[k]?.let { sh * it.value.weight * maxOf(0.0, 1 - it.pct) } ?: 0.0 }

    /** "Serves: Reputation ⅔ · Money ⅓". */
    fun servesText(board: Board, task: Task): String = sharesOf(board, task).entries.joinToString(" · ") { (k, sh) ->
        k + when { sh > 0.9 -> ""; sh > 0.5 -> " ⅔"; else -> " ⅓" }
    }

    /** The cells still open this week (red, or left behind as a record): how many, and what finishing them today gives back. */
    data class CatchUp(val count: Int, val gain: Int)

    fun catchUp(board: Board, today: LocalDate, week: Week = of(board, today)): CatchUp? {
        val mon = monday(today)
        val todayS = today.toString()
        val ids = LinkedHashSet<String>()
        val steps = board.tasks.flatMap { it.steps }
        val byId = steps.associateBy { it.id }
        steps.forEach { s ->
            if (!s.done && s.outcome == null && s.date != null && s.date >= mon.toString() && s.date < todayS) ids += s.id
            if (s.outcome == Outcome.MISSED && inWeek(s.date, mon)) s.origin?.let { o -> byId[o]?.takeIf { !it.done && it.outcome == null }?.let { ids += o } }
        }
        if (ids.isEmpty()) return null
        val after = of(board.copy(tasks = board.tasks.map { t -> t.copy(steps = t.steps.map { s: Step -> if (s.id in ids) s.copy(done = true, date = todayS) else s }) }), today)
        return CatchUp(ids.size, after.week - week.week)
    }
}
