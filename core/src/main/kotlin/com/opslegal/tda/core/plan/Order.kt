package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate

/**
 * What comes first: one order for planning, 📥 Fill and ↻ Replan, by rules on the phone (no AI). Seven groups, in the
 * user's order (Settings › My day) or the usual one; inside a group, the nearest date first. Something new that
 * outranks what is planned takes its place from tomorrow on; today, pinned and dated cells never move.
 */
object Order {
    data class Group(val key: String, val icon: String, val name: String, val about: String)

    val GROUPS = listOf(
        Group("emergency", "🚨", "Emergencies", "late, due today or tomorrow, or marked critical"),
        Group("promised", "🤝", "Promised deliveries", "a due date, and someone outside expects it (🤝 tag)"),
        Group("due", "📦", "Deliveries by due date", "nearest due date first"),
        Group("dated", "📅", "Dated operations, soon", "a set day within 2 weeks, nothing to deliver"),
        Group("unlocks", "🔗", "Unlocks the next steps", "most steps waiting on it first, bigger effort first"),
        Group("regular", "🔁", "Regular operations", "upkeep, bills, errands"),
        Group("normal", "·", "Normal tasks", "the rest"),
    )

    /** The user's order, completed with the groups they did not move. */
    fun order(board: Board): List<String> {
        val mine = board.settings.order.filter { k -> GROUPS.any { it.key == k } }.distinct()
        return mine + GROUPS.map { it.key }.filter { it !in mine }
    }

    /** One group moved up (-1) or down (+1). */
    fun move(board: Board, key: String, dir: Int): Board {
        val o = order(board).toMutableList()
        val i = o.indexOf(key); val j = i + dir
        if (i < 0 || j !in o.indices) return board
        o[i] = o[j].also { o[j] = o[i] }
        return board.copy(settings = board.settings.copy(order = o))
    }

    private val EXPECTED = Regex("(?i)\\b(client|customer|bank|banque|lawyer|avocat|notary|notaire|court|tribunal|broker|courtier|accountant|comptable|insurer|assureur|landlord|tenant|government|quote|devis|offer|offre|proposal|soumission|submit|soumettre|deliver|livrer)\\b")

    /** 🤝 Expected outside: the user's choice, else its words (pre-selected, ✨). */
    fun expected(task: Task): Boolean = task.expected ?: EXPECTED.containsMatchIn(listOf(task.title, task.description).plus(task.steps.map { it.title }).joinToString(" "))
    fun expectedGuessed(task: Task): Boolean = task.expected == null && expected(task)

    fun due(board: Board, task: Task): String? = task.deadline ?: task.project.takeIf { it.isNotBlank() }?.let { BoardOps.findProject(board, it)?.deadline }

    private fun openAfter(task: Task, step: Step): Int =
        if (!task.isProject) 0 else task.steps.dropWhile { it.id != step.id }.drop(1).count { !it.closed }

    private fun stepOf(task: Task, step: Step?): Step? = step ?: task.steps.firstOrNull { !it.closed && it.date == null } ?: task.steps.firstOrNull { !it.closed } ?: task.steps.firstOrNull()

    /** Which group a cell belongs to. */
    fun group(board: Board, task: Task, step: Step?, today: LocalDate): String {
        val st = stepOf(task, step)
        val due = due(board, task)
        val fixed = st?.let { task.fixedDateOf(it) }
        return when {
            task.priority == Priority.CRITICAL || (due != null && due <= today.plusDays(1).toString()) -> "emergency"
            due != null && expected(task) -> "promised"
            due != null -> "due"
            (fixed != null && fixed <= today.plusDays(14).toString()) || (st != null && task.kindOf(st) == TaskKind.MEETING) -> "dated"
            (st != null && openAfter(task, st) >= 2) || task.blocks.isNotEmpty() -> "unlocks"
            task.asset.isNotBlank() || task.billsCell || task.errands -> "regular"
            else -> "normal"
        }
    }

    /** The sort key: lower comes first. */
    data class Key(val group: Int, val date: String, val after: Int, val heavy: Int, val urgency: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, { it.group }, { it.date }, { it.after }, { it.heavy }, { it.urgency })
    }

    fun key(board: Board, task: Task, step: Step?, today: LocalDate, weights: Map<String, Int> = Planner.effectiveWeights(board)): Key {
        val st = stepOf(task, step)
        val g = group(board, task, st, today)
        val unlocks = g == "unlocks" && st != null
        return Key(
            order(board).indexOf(g),
            due(board, task) ?: st?.let { task.fixedDateOf(it) } ?: "9999",
            if (unlocks) -openAfter(task, st!!) else 0,
            if (unlocks && task.effortOf(st!!) == Effort.HEAVY) 0 else 1,
            -Planner.urgency(task, weights, today, Values.score(board, task)),
        )
    }

    /** "📦 Deliveries by due date · due Nov 20": what decided the place, shown at the moment of the choice. */
    fun label(board: Board, task: Task, step: Step?, today: LocalDate): String {
        val st = stepOf(task, step)
        val g = group(board, task, st, today)
        val gr = GROUPS.first { it.key == g }
        val extra = when (g) {
            "promised", "due" -> due(board, task)?.let { " · due ${short(LocalDate.parse(it))}" }
            "dated" -> st?.let { task.fixedDateOf(it) }?.let { " · ${short(LocalDate.parse(it))}" }
            "unlocks" -> st?.let { " · ${openAfter(task, it)} steps after" }
            else -> null
        }.orEmpty()
        return "${gr.icon} ${gr.name}$extra"
    }

    fun short(d: LocalDate): String =
        "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.dayOfMonth}"

    /**
     * When something waiting outranks what is planned, the cells of lower groups from tomorrow on step aside (back to
     * the planner, which places everything in order). Today, pinned, dated, done and closed cells never move; a
     * project's later steps step aside with it so the order holds. Returns the board and the step ids that stepped aside.
     */
    fun resort(board: Board, today: LocalDate): Pair<Board, Map<String, String>> {
        val weights = Planner.effectiveWeights(board)
        val waiting = board.tasks.filter { t -> t.steps.any { it.date == null && !it.closed } }
        if (waiting.isEmpty()) return board to emptyMap()
        val best = waiting.minOf { key(board, it, null, today, weights).group }
        val moved = HashMap<String, String>()
        val t0 = today.toString()
        fun movable(t: Task, s: Step) = s.date != null && s.date > t0 && !s.closed && !s.pinned && t.fixedDateOf(s) == null
        val tasks = board.tasks.map { t ->
            val from = t.steps.indexOfFirst { s -> movable(t, s) && key(board, t, s, today, weights).group > best }
            if (from < 0) t else t.copy(steps = t.steps.mapIndexed { i, s -> if (i >= from && movable(t, s)) { moved[s.id] = s.date!!; s.copy(date = null, slot = null) } else s })
        }
        return board.copy(tasks = tasks) to moved
    }

    /** What taking today would do for an emergency: a free big cell, today's lowest cell moving to [to], or (small) today's ⚡ line. */
    data class Today(val free: Boolean = false, val small: Boolean = false, val outStep: String? = null, val outQuick: String? = null, val to: LocalDate? = null, val outTitle: String = "")

    fun isEmergencyLater(board: Board, task: Task, step: Step, today: LocalDate): Boolean =
        !step.closed && !step.emAsked && (step.date == null || step.date > today.toString()) && !BoardOps.isMissed(step, today) &&
            group(board, task, step, today) == "emergency"

    /** The first emergency that could not get today and was not asked about yet. */
    fun toAsk(board: Board, today: LocalDate): Pair<Task, Step>? =
        board.tasks.firstNotNullOfOrNull { t -> t.steps.firstOrNull { !it.closed }?.takeIf { isEmergencyLater(board, t, it, today) }?.let { t to it } }

    fun today(board: Board, stepId: String, today: LocalDate): Today? {
        val (task, step) = BoardOps.findStep(board, stepId) ?: return null
        if (Labels.sizeOf(step.title.ifBlank { task.title }) == "quick" && !task.isProject) {
            val line = Quick.onDay(board, today, today).filter { !it.done }
            val out = if (line.size >= board.settings.quickPerDay.coerceAtLeast(1)) line.minByOrNull { Quick.score(it, today) } else null
            return Today(small = true, outQuick = out?.id, to = out?.let { today.plusDays(1) }, outTitle = out?.title.orEmpty())
        }
        val n = board.cellsPerDay
        val cells = board.tasks.flatMap { t -> t.steps.filter { it.date == today.toString() && it.slot != null && it.slot < n && it.id != stepId }.map { t to it } }
        if (cells.size < n) return Today(free = true)
        val weights = Planner.effectiveWeights(board)
        val (ot, os) = cells.filter { (t, s) -> !s.closed && t.fixedDateOf(s) == null && group(board, t, s, today) != "emergency" }
            .maxByOrNull { (t, s) -> key(board, t, s, today, weights) } ?: return null
        var d = today.plusDays(1)
        repeat(90) {
            val used = board.tasks.flatMap { it.steps }.count { it.date == d.toString() && it.slot != null && it.slot < n }
            if (Areas.canPlan(board, ot, d) && used < n) return Today(outStep = os.id, to = d, outTitle = Planner.cellTitle(ot, os))
            d = d.plusDays(1)
        }
        return null
    }

    /** 🚨 Today: the emergency takes today (pinned); what it replaces moves as shown before the tap. */
    fun takeToday(board0: Board, stepId: String, today: LocalDate): Board {
        // A red one from Replan keeps its red record on its day; its work comes today.
        val board = BoardOps.findStep(board0, stepId)?.second?.takeIf { BoardOps.isMissed(it, today) }?.let { BoardOps.leaveMissed(board0, stepId, today) } ?: board0
        val plan = today(board, stepId, today) ?: return board0
        val (task, step) = BoardOps.findStep(board, stepId) ?: return board0
        var b = BoardOps.mapStep(board, stepId) { it.copy(emAsked = true) }
        if (plan.small) {
            plan.outQuick?.let { id -> b = Quick.edit(Quick.keep(b, today), id) { it.copy(date = plan.to.toString()) }; b = b.copy(quickPick = b.quickPick?.copy(ids = b.quickPick!!.ids - id)) }
            b = BoardOps.cancelStep(b, stepId, today)
            return Quick.add(b, listOf(Planner.cellTitle(task, step)), today, today, due = due(board, task) ?: today.toString(), impact = 3, area = Areas.of(board, task).id).first
        }
        val n = b.cellsPerDay
        val used = b.tasks.flatMap { it.steps }.filter { it.date == today.toString() && it.id != stepId }.mapNotNull { it.slot }.toSet()
        val slot = if (plan.free) (0 until n).first { it !in used } else b.tasks.flatMap { it.steps }.first { it.id == plan.outStep }.slot!!
        plan.outStep?.let { out ->
            val day = plan.to!!.toString()
            val usedThere = b.tasks.flatMap { it.steps }.filter { it.date == day }.mapNotNull { it.slot }.toSet()
            val s2 = (0 until n).firstOrNull { it !in usedThere } ?: (0 until com.opslegal.tda.core.model.SLOTS_PER_DAY).first { it !in usedThere }
            b = BoardOps.mapStep(b, out) { it.copy(date = day, slot = s2) }
        }
        b = BoardOps.mapStep(b, stepId) { it.copy(date = today.toString(), slot = slot, pinned = true, notBefore = null, emAsked = true) }
        return Planner.plan(b, today, resort = false).board
    }

    /** Keep its day: asked, not asked again. */
    fun keep(board: Board, stepId: String): Board {
        val task = BoardOps.findStep(board, stepId)?.first ?: return board
        return BoardOps.updateTask(board, task.id) { t -> t.copy(steps = t.steps.map { if (it.closed) it else it.copy(emAsked = true) }) }
    }
}
