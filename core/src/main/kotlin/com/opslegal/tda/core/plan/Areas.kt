package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Area
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Task
import java.time.LocalDate

/**
 * My week: each area of life is planned on its own days. A freelancer never gets company A's work on a company B day;
 * an office in Cairo works Sunday to Thursday; repairs may fit the weekend. Each project or task belongs to one area.
 */
object Areas {
    const val WORK = "work"
    const val HOME = "home"
    private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** The user's areas; before they set any: Work on the work days, Personal every day (as before areas existed). */
    fun all(settings: PlannerSettings): List<Area> = settings.areas.ifEmpty {
        listOf(Area(WORK, "Work", settings.workDays.sorted(), work = true), Area(HOME, "Personal", (1..7).toList(), work = false))
    }

    /** The area a task is planned in: its own, its project's, else Personal for personal life and Work for the rest. */
    fun of(board: Board, task: Task): Area {
        val list = all(board.settings)
        val id = task.area.ifBlank { BoardOps.findProject(board, task.project)?.area.orEmpty() }
        list.firstOrNull { it.id == id }?.let { return it }
        val work = list.firstOrNull { it.work } ?: list.first()
        val home = list.firstOrNull { !it.work } ?: list.first()
        return if (task.personal && task.project.isBlank()) home else work
    }

    /** The area of a project (by name). */
    fun ofProject(board: Board, name: String): Area =
        all(board.settings).let { list -> list.firstOrNull { it.id == BoardOps.findProject(board, name)?.area } ?: list.firstOrNull { it.work } ?: list.first() }

    /** Its day, and for work, not a public holiday or a day off. */
    fun canPlan(area: Area, date: LocalDate, settings: PlannerSettings): Boolean =
        date.dayOfWeek.value in area.days && !(area.work && Holidays.isOff(date, settings))

    fun canPlan(board: Board, task: Task, date: LocalDate): Boolean = canPlan(of(board, task), date, board.settings)

    /** A day the table shows: some area is planned on it. */
    fun shown(date: LocalDate, settings: PlannerSettings): Boolean = all(settings).any { date.dayOfWeek.value in it.days }

    /** "Mon–Fri", "Sun–Thu", "Sat, Sun", "every day". */
    fun daysText(days: List<Int>): String {
        val set = days.toSet()
        if (set.size == 7) return "every day"
        if (set.isEmpty()) return "no day"
        fun prev(d: Int) = if (d == 1) 7 else d - 1
        fun next(d: Int) = if (d == 7) 1 else d + 1
        val starts = (1..7).filter { it in set && prev(it) !in set }
        if (starts.size == 1 && set.size > 2) {
            var e = starts[0]
            while (next(e) in set) e = next(e)
            return "${DAY[starts[0] - 1]}–${DAY[e - 1]}"
        }
        return set.sorted().joinToString(", ") { DAY[it - 1] }
    }

    /** For the assistant: each area and its days, and where each project goes. */
    fun describe(board: Board): String =
        all(board.settings).joinToString("; ") { "${it.name}${if (it.work) " (work)" else ""} ${daysText(it.days)}" } +
            board.projects.takeIf { it.isNotEmpty() }?.let { ps -> ". Projects: " + ps.joinToString(", ") { "${it.name} → ${ofProject(board, it.name).name}" } }.orEmpty()

    /**
     * New areas or days: cells on a day their area no longer uses go back to the planner. Cells the user placed and fixed
     * meetings stay, and are returned so the user hears about them.
     */
    fun replan(board: Board, today: LocalDate): Triple<Board, Int, List<String>> {
        var moved = 0
        val kept = mutableListOf<String>()
        val tasks = board.tasks.map { t ->
            t.copy(steps = t.steps.map { st ->
                val d = st.date?.let(LocalDate::parse)
                if (d == null || d.isBefore(today) || st.closed || canPlan(board, t, d)) st
                else if (st.pinned || t.fixedDateOf(st) != null) { kept += "${Planner.cellTitle(t, st)} (${DAY[d.dayOfWeek.value - 1]} ${d.dayOfMonth})"; st }
                else { moved++; st.copy(date = null, slot = null) }
            })
        }
        return Triple(Planner.plan(board.copy(tasks = tasks), today).board, moved, kept)
    }
}
