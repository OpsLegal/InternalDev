package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.model.Routine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Routines: never checked, assumed done, counted in the week's balance. No clock times: the moment of the day only. */
object Routines {
    val days = listOf("M", "Tu", "W", "Th", "F", "Sa", "Su")
    val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun at(board: Board, day: Int, moment: Moment, skipId: String? = null): List<Routine> =
        board.routines.filter { it.id != skipId && it.moment == moment && day in it.days }

    /** A moment of one day holding 3 routines or more. */
    data class Crowded(val day: Int, val moment: Moment, val count: Int) {
        val key: String get() = "$day:${moment.name}"
        val words: String get() = "${dayNames[day - 1]} ${moment.label.lowercase()}"
    }

    /** Never blocked, never flagged on the routine page: the weekly review asks how they went. */
    fun crowded(board: Board): List<Crowded> = Moment.values().flatMap { m ->
        (1..7).mapNotNull { d -> at(board, d, m).size.takeIf { it >= 3 }?.let { Crowded(d, m, it) } }
    }.filter { it.key !in board.routineFine }

    /** A free moment to try a wish: weekends first, then weekday mornings and evenings. */
    fun freeSlot(board: Board): Pair<Int, Moment> {
        val order = listOf(Moment.MORNING, Moment.AFTERNOON, Moment.EVENING, Moment.EARLY, Moment.MIDDAY)
        for (d in listOf(6, 7, 1, 2, 3, 4, 5)) for (m in order) if (at(board, d, m).isEmpty()) return d to m
        return 6 to Moment.MORNING
    }

    /** A week's base from routines (level × days). Ground and Nourish attributes no routine covers count as average. */
    fun base(board: Board): Map<String, Int> {
        val out = HashMap<String, Int>()
        val covered = HashSet<String>()
        board.routines.forEach { r -> r.serve.forEach { (k, l) -> out[k] = (out[k] ?: 0) + l * r.days.size; covered += k } }
        board.values.filter { it.bucket != Gbn.BUILD && it.name !in covered }.forEach { out[it.name] = (out[it.name] ?: 0) + 4 }
        return out
    }

    /** This week's points per attribute: what the planned cells serve (level per cell), plus the routines. */
    fun points(board: Board, today: LocalDate): Map<String, Int> {
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val tot = base(board).toMutableMap()
        board.tasks.forEach { t ->
            val lv = Gbn.levelsOf(board, t)
            t.steps.filter { s -> s.outcome == null && s.date?.let { d -> LocalDate.parse(d).let { !it.isBefore(mon) && !it.isAfter(mon.plusDays(6)) } } == true }
                .forEach { _ -> lv.forEach { (k, l) -> tot[k] = (tot[k] ?: 0) + l } }
        }
        return tot
    }

    /**
     * Each category's score out of 100 for the week: how much of what it should get it gets. An attribute should get its
     * weight × 2 points (three cells at level 2 for a weight-3 attribute); more than that does not count twice. Under 60:
     * the category is underperforming.
     */
    fun scores(board: Board, today: LocalDate): Map<String, Int> {
        val tot = points(board, today)
        return Gbn.buckets.associateWith { b ->
            val list = board.values.filter { it.bucket == b && it.weight > 0 }
            val want = list.sumOf { it.weight * 2 }.coerceAtLeast(1)
            list.sumOf { minOf(tot[it.name] ?: 0, it.weight * 2) } * 100 / want
        }
    }

    const val LOW = 60

    /** One attribute's share of the week's score: it needs [need] points (weight × 2), it got [have], from [from]. */
    data class Line(val name: String, val bucket: String, val weight: Int, val need: Int, val have: Int, val from: List<String>)

    /** How each category's score is made, attribute by attribute, with the cells and routines that brought the points. */
    fun breakdown(board: Board, today: LocalDate): List<Line> {
        val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val tot = points(board, today)
        val covered = board.routines.flatMap { it.serve.keys }.toSet()
        return board.values.filter { it.weight > 0 }.map { v ->
            val from = mutableListOf<String>()
            board.tasks.forEach { t ->
                val l = Gbn.levelsOf(board, t)[v.name] ?: return@forEach
                t.steps.filter { s -> s.outcome == null && s.date?.let { d -> LocalDate.parse(d).let { !it.isBefore(mon) && !it.isAfter(mon.plusDays(6)) } } == true }
                    .forEach { s -> from += "${Planner.cellTitle(t, s)} +$l" }
            }
            board.routines.forEach { r -> r.serve[v.name]?.let { l -> from += "${r.title} (routine) +${l * r.days.size}" } }
            if (v.bucket != Gbn.BUILD && v.name !in covered) from += "average week +4"
            Line(v.name, v.bucket, v.weight, v.weight * 2, tot[v.name] ?: 0, from)
        }
    }

    /** This week as levels 1-3 per attribute: what the planned cells serve, plus the routines. */
    fun week(board: Board, today: LocalDate): Map<String, Int> {
        val tot = points(board, today)
        val max = (tot.values.maxOrNull() ?: 0).coerceAtLeast(1)
        return tot.mapValues { maxOf(1, Math.round(it.value * 3.0 / max).toInt()) }
    }

    /** A proposed routine, from the calendar or from a task the user keeps creating. */
    data class Hint(val title: String, val days: List<Int>, val moment: Moment, val from: String, val note: String)

    /** One-cell tasks created 3 times or more in the last 30 days, not already a routine or declined. */
    fun taskHints(board: Board, today: LocalDate): List<Hint> = board.tasks
        .filter { !it.isProject && it.createdAt.isNotBlank() && runCatching { LocalDate.parse(it.createdAt.take(10)) }.getOrNull()?.isAfter(today.minusDays(30)) == true }
        .groupBy { it.title.trim().lowercase() }
        .filter { (k, v) -> v.size >= 3 && board.routines.none { it.title.lowercase() == k } && k !in board.routineNo }
        .map { (_, v) ->
            val d = v.mapNotNull { t -> t.steps.firstOrNull()?.date?.let { LocalDate.parse(it).dayOfWeek.value } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 6
            Hint(v.first().title, listOf(d), Moment.EVENING, "tasks", "you created it ${v.size} times this month")
        }

    /** Events repeating on the same weekday in different weeks (title, day, moment of day), from the phone calendar. */
    fun calendarHints(board: Board, events: List<Triple<String, LocalDate, Moment>>): List<Hint> = events
        .filter { it.first.isNotBlank() }
        .groupBy { it.first.trim().lowercase() }
        .filter { (k, v) -> v.map { it.second.with(DayOfWeek.MONDAY) }.distinct().size >= 2 && board.routines.none { it.title.lowercase() == k } && k !in board.routineNo }
        .map { (_, v) ->
            val dd = v.map { it.second.dayOfWeek.value }.distinct().sorted()
            Hint(v.first().first, dd, v.first().third, "calendar", "repeats ${dd.joinToString(" and ") { dayNames[it - 1].take(3) }} in your calendar")
        }.take(4)
}
