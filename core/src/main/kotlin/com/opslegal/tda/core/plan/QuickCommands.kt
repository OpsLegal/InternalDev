package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import java.time.LocalDate

/**
 * Quick commands understood on the phone, no AI: "move … to Friday", "… done", "buy …", "call … tomorrow". Only
 * when the whole sentence fits and exactly one open cell matches; the user still confirms. Anything else goes to the AI.
 */
object QuickCommands {
    data class Cmd(val summary: String, val apply: (Board) -> Board)

    private fun cellByWords(board: Board, words: String, today: LocalDate): Pair<String, String>? {
        val ws = Rules.fold(words).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in setOf("the", "les", "des", "une", "cell", "task", "tache") }
        if (ws.isEmpty()) return null
        val hits = board.tasks.flatMap { t -> t.steps.filter { !it.closed && (it.date ?: "9") >= today.toString() }.map { s -> Planner.cellTitle(t, s) to s.id } }
            .filter { (title, _) -> Rules.fold(title).let { ti -> ws.all { ti.contains(it) } } }
        return hits.singleOrNull()
    }

    fun parse(board: Board, text: String, today: LocalDate, dayName: (LocalDate) -> String = { it.toString() }): Cmd? {
        val t = text.trim().trimEnd('.', '!')
        Regex("^(?:move|put|deplace|déplace|reporte|mets|bouge)\\s+(.+?)\\s+(?:to|on|à|a|au|pour)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val (title, id) = cellByWords(board, m.groupValues[1], today) ?: return null
            val day = Updates.dayInNote(m.groupValues[2], today) ?: return null
            return Cmd("move “$title” to ${dayName(day)}") { b -> BoardOps.moveStep(b, id, day) ?: b }
        }
        (Regex("^(.+?)\\s+(?:is\\s+)?(?:done|finished|fait|terminé|termine|fini)$", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("^(?:done|fait|fini)\\s*:?\\s+(.+)$", RegexOption.IGNORE_CASE).find(t))?.let { m ->
            val (title, id) = cellByWords(board, m.groupValues[1], today) ?: return null
            return Cmd("mark “$title” done") { b -> BoardOps.setStepDone(b, id, true) }
        }
        Regex("^(?:buy|acheter|achète|achete)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val items = m.groupValues[1].split(Regex(",|\\bet\\b|\\band\\b")).map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) return null
            return Cmd("add to To buy: ${items.joinToString(", ")}") { b -> Shopping.add(b, items).first }
        }
        Regex("^(?:call|phone|appeler|appelle|téléphoner à|telephoner a)\\s+(.+?)\\s+(today|tomorrow|aujourd'hui|demain|next week|semaine prochaine|monday|tuesday|wednesday|thursday|friday|saturday|sunday|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            val day = Updates.dayInNote(m.groupValues[2], today) ?: return null
            val what = "Call ${m.groupValues[1].trim()}"
            return Cmd("⚡ quick thing “$what” on ${dayName(day)}") { b -> Quick.add(b, listOf(what), day, today).first }
        }
        return null
    }
}
