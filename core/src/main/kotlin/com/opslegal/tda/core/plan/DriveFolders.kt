package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board

/**
 * Where a file is saved in a connected drive: Apps › Docket 5 › area › project (or "Tasks") › kind › "date what.ext".
 * Each area saves to the drive the user chose for it ([Board.driveOf]); with none chosen, the first connected one.
 */
object DriveFolders {
    /** Folder and file names a drive accepts: no / \ : * ? " < > |, no trailing dots or spaces, 80 characters at most. */
    fun clean(s: String): String = s.replace(Regex("""[\\/:*?"<>|#%]"""), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').take(80).ifBlank { "Untitled" }

    fun path(board: Board, area: String, project: String?, kind: String, date: String, what: String, ext: String): String {
        val a = Areas.all(board.settings).firstOrNull { it.id == area }?.name ?: "Not sorted"
        return listOf(clean(a), clean(project?.ifBlank { null } ?: "Tasks"), clean(kind), clean("$date $what") + "." + ext.trimStart('.')).joinToString("/")
    }

    /** The drive for an area: the user's choice, else the first connected one; null when none is connected. */
    fun driveFor(board: Board, area: String, connected: List<String>): String? =
        board.driveOf[area]?.takeIf { it in connected } ?: connected.firstOrNull()

    fun choose(board: Board, area: String, slot: String?): Board =
        board.copy(driveOf = if (slot == null) board.driveOf - area else board.driveOf + (area to slot))

    /** A file the app keeps on the phone (a receipt photo, a scan), to move to the area's drive. */
    data class LocalFile(val id: String, val uri: String, val area: String, val project: String?, val kind: String, val date: String, val what: String)

    /**
     * The receipts and scans still on the phone ([isLocal] says which links are the app's own files; a file the user
     * picked stays where it is). With "cloud" chosen, each goes to its area's drive, then leaves the phone.
     */
    fun localFiles(board: Board, isLocal: (String) -> Boolean): List<LocalFile> {
        val areas = Areas.all(board.settings)
        val work = (areas.firstOrNull { it.work } ?: areas.first()).id
        fun money(e: com.opslegal.tda.core.model.Expense) = "${e.vendor.ifBlank { e.category }} ${"%.2f".format(java.util.Locale.US, e.amount)}"
        val fromProjects = board.projects.flatMap { p ->
            val area = p.area.ifBlank { work }
            p.expenses.filter { isLocal(it.photo) }.map { LocalFile(it.id, it.photo, it.area.ifBlank { area }, p.name, "Receipts", it.date, money(it)) } +
                p.docs.filter { isLocal(it.uri) }.map { LocalFile(it.id, it.uri, area, p.name, "Documents", it.at, it.name.substringBeforeLast('.')) }
        }
        val fromTasks = board.tasks.filter { !it.isProject }.flatMap { t ->
            val area = Areas.of(board, t).id
            t.expenses.filter { isLocal(it.photo) }.map { LocalFile(it.id, it.photo, it.area.ifBlank { area }, null, "Receipts", it.date, money(it)) } +
                t.docs.filter { isLocal(it.uri) }.map { LocalFile(it.id, it.uri, area, null, "Documents", it.at, it.name.substringBeforeLast('.')) }
        }
        val loose = board.expenses.filter { isLocal(it.photo) }.map { LocalFile(it.id, it.photo, it.area.ifBlank { work }, null, "Receipts", it.date, money(it)) }
        return fromProjects + fromTasks + loose
    }

    /** The file [id] now lives at [link] (its drive): the expense or document points there. */
    fun moved(board: Board, id: String, link: String): Board {
        fun e(x: com.opslegal.tda.core.model.Expense) = if (x.id == id) x.copy(photo = link) else x
        fun d(x: com.opslegal.tda.core.model.Doc) = if (x.id == id) x.copy(uri = link) else x
        return board.copy(
            expenses = board.expenses.map(::e),
            projects = board.projects.map { p -> p.copy(expenses = p.expenses.map(::e), docs = p.docs.map(::d)) },
            tasks = board.tasks.map { t -> t.copy(expenses = t.expenses.map(::e), docs = t.docs.map(::d)) },
        )
    }
}
