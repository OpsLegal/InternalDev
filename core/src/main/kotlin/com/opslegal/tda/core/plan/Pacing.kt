package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Projects follow their timeline instead of filling the next free cells: open steps spread over the work days up to
 * the deadline, minus a buffer, skipping holidays, and waiting for others when a step needs their answer.
 */
object Pacing {

    data class Paced(val board: Board, val buffer: Int, val lastDay: LocalDate?)

    /** Spreads the project's open steps that are not on the table yet. Tight deadlines stay "as soon as possible". */
    fun pace(board: Board, name: String, today: LocalDate): Paced {
        val project = BoardOps.findProject(board, name) ?: return Paced(board, 0, null)
        val deadline = project.deadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return Paced(board, 0, null)
        val holder = BoardOps.projectTask(board, name) ?: return Paced(board, 0, null)
        val open = holder.steps.filter { !it.closed && it.date == null && !it.pinned && it.fixedDate == null }
        if (open.isEmpty()) return Paced(board, 0, null)
        val all = generateSequence(today) { it.plusDays(1) }.takeWhile { it.isBefore(deadline) }
            .filter { Holidays.isWorkDay(it, board.settings) }.toList()
        val buffer = maxOf(2, (all.size * 0.1).roundToInt())
        val days = all.take(maxOf(open.size, all.size - buffer))
        if (days.size < open.size * 3) return Paced(board, 0, null)
        val gap = days.size / open.size
        var cursor = 0
        var b = board
        open.forEachIndexed { i, st ->
            val idx = minOf(days.size - 1, maxOf(if (i == 0) 0 else i * gap, cursor + st.waitDays))
            b = BoardOps.mapStep(b, st.id) { it.copy(notBefore = days[idx].toString()) }
            cursor = idx + 1
        }
        return Paced(b, buffer, days.last())
    }

    /** A step the assistant adds when the basics of project management are missing. */
    data class Filled(val title: String, val waitDays: Int = 0, val waitFor: String = "", val added: Boolean = false)

    /** Adds a feedback round (with its wait) when someone else must approve, and a final review before the end. */
    fun fill(steps: List<Filled>, explain: String): List<Filled> {
        val titles = steps.joinToString(" | ") { it.title.lowercase() }
        val out = steps.toMutableList()
        val last = if (out.isEmpty()) 0 else out.size - 1
        if (Regex("client|customer|mandate|board|partner|feedback|approv|\\bact\\b", RegexOption.IGNORE_CASE).containsMatchIn(explain) &&
            !Regex("feedback|comment|approv|review by").containsMatchIn(titles)
        ) out.addAll(last, listOf(Filled("Send the draft for feedback", added = true), Filled("Integrate the feedback", 5, "their feedback", true)))
        if (out.size > 2 && !Regex("final review|proofread|relecture|review").containsMatchIn(out.joinToString(" | ") { it.title.lowercase() }))
            out.add(out.size - 1, Filled("Final review", added = true))
        return out
    }
}
