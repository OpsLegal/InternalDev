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
}
