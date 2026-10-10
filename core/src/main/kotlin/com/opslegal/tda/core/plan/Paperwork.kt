package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Doc
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.Task
import java.time.LocalDate
import java.util.Locale

/**
 * Documents and expenses, kept with a task or its project (two levels: no new screen, no new level). Expenses of a day
 * that belong to nothing sit on the board. The report is for the user to send; nothing leaves on its own.
 */
object Paperwork {
    val CATEGORIES = listOf("Meal", "Travel", "Mileage", "Supplies", "Fees", "Other")

    /** Where a task's documents and expenses live: its project, or the task itself. */
    fun projectOf(board: Board, task: Task) = if (task.isProject) BoardOps.findProject(board, task.project) else null

    fun docs(board: Board, task: Task): List<Doc> = projectOf(board, task)?.docs ?: task.docs
    fun expenses(board: Board, task: Task): List<Expense> = projectOf(board, task)?.expenses ?: task.expenses

    fun addDoc(board: Board, taskId: String, doc: Doc): Board {
        val task = board.tasks.firstOrNull { it.id == taskId } ?: return board
        val p = projectOf(board, task)
        return if (p != null) board.copy(projects = board.projects.map { if (it.name == p.name) it.copy(docs = it.docs.filter { d -> d.name != doc.name || d.uri != doc.uri } + doc) else it })
        else BoardOps.updateTask(board, taskId) { it.copy(docs = it.docs.filter { d -> d.name != doc.name || d.uri != doc.uri } + doc) }
    }

    fun changeDoc(board: Board, docId: String, change: (Doc) -> Doc?): Board = board.copy(
        projects = board.projects.map { p -> p.copy(docs = p.docs.mapNotNull { if (it.id == docId) change(it) else it }) },
        tasks = board.tasks.map { t -> t.copy(docs = t.docs.mapNotNull { if (it.id == docId) change(it) else it }) },
    )

    /** Adds [e] to the task's project (or the task), to [project] by name, or to the board when neither is given. */
    fun addExpense(board: Board, e: Expense, taskId: String? = null, project: String? = null): Board {
        val task = taskId?.let { id -> board.tasks.firstOrNull { it.id == id } }
        val p = task?.let { projectOf(board, it) } ?: project?.let { BoardOps.findProject(board, it) }
        return when {
            p != null -> board.copy(projects = board.projects.map { if (it.name == p.name) it.copy(expenses = it.expenses + e) else it })
            task != null -> BoardOps.updateTask(board, task.id) { it.copy(expenses = it.expenses + e) }
            else -> board.copy(expenses = board.expenses + e)
        }
    }

    /** Replaces (or, with null, deletes) the expense [id], wherever it is. */
    fun changeExpense(board: Board, id: String, e: Expense?): Board = board.copy(
        expenses = board.expenses.mapNotNull { if (it.id == id) e else it },
        projects = board.projects.map { p -> p.copy(expenses = p.expenses.mapNotNull { if (it.id == id) e else it }) },
        tasks = board.tasks.map { t -> t.copy(expenses = t.expenses.mapNotNull { if (it.id == id) e else it }) },
    )

    data class Row(val e: Expense, val where: String)

    /** Every expense with where it belongs, newest first. */
    fun all(board: Board): List<Row> = (board.projects.flatMap { p -> p.expenses.map { Row(it, p.name) } } +
        board.tasks.filter { !it.isProject }.flatMap { t -> t.expenses.map { Row(it, t.title) } } +
        board.expenses.map { Row(it, "No project") }).sortedByDescending { it.e.date }

    /** [period]: "month", "last" or "all"; [where]: a project or task name, or "" for all. */
    fun filter(board: Board, today: LocalDate, period: String, where: String): List<Row> {
        val month = today.toString().take(7)
        val last = today.withDayOfMonth(1).minusDays(1).toString().take(7)
        return all(board).filter { r ->
            (period == "all" || r.e.date.take(7) == (if (period == "month") month else last)) && (where.isBlank() || r.where == where)
        }
    }

    data class Totals(val total: Double, val taxes: Double, val tips: Double, val billable: Double)

    fun totals(rows: List<Row>) = Totals(rows.sumOf { it.e.amount }, rows.sumOf { it.e.tax }, rows.sumOf { it.e.tip }, rows.filter { it.e.billable }.sumOf { it.e.amount })

    fun money(n: Double): String = String.format(Locale.CANADA, "%.2f $", n)

    private fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""

    fun csv(rows: List<Row>): String = (listOf(listOf("Date", "Where", "Category", "Project or task", "Cell", "Total", "Taxes", "Tip", "Billable", "Note")) +
        rows.map { (e, w) -> listOf(e.date, e.vendor, e.category, w, e.task, "%.2f".format(Locale.ROOT, e.amount), "%.2f".format(Locale.ROOT, e.tax),
            "%.2f".format(Locale.ROOT, e.tip), if (e.billable) "yes" else "no", e.note) })
        .joinToString("\n") { r -> r.joinToString(",") { cell(it) } }

    /** "Fill: …" for a document: a task (or a project step) with the file attached; on [day] if given, else the next free cell. */
    fun documentTask(board: Board, title: String, what: String, intention: String, project: String, doc: Doc?, day: LocalDate?, today: LocalDate): Board {
        val spec = BoardOps.NewTask(title.trim(), description = if (what.isNotBlank()) "Fill in and send: ${what.trim()}." else title.trim(),
            project = project.trim(), intention = intention.trim())
        var (b, task, _) = if (day != null) BoardOps.addTaskOn(board, spec, day, today) else BoardOps.add(board, spec, today).let { Triple(it.board, it.task, true) }
        if (doc != null) b = addDoc(b, task.id, doc)
        return Planner.plan(b, today).board
    }
}
