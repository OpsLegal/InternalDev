package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update

/**
 * The assistant as a person: the name the user gave it ("Jimmy") and its face. People close to the user write to it
 * directly ("Jimmy, remind him…"): those messages are for the assistant and always come to the bell.
 */
object Me {
    fun name(board: Board): String = board.persona.name.trim().ifBlank { "Assistant" }
    fun named(board: Board): Boolean = board.persona.name.isNotBlank()

    /** The message names the assistant, as a word ("Jimmy," "Hey Jimmy", "… merci Jimmy"). */
    fun forMe(board: Board, text: String): Boolean = named(board) &&
        Regex("(^|[^\\p{L}])${Regex.escape(name(board))}([^\\p{L}]|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun forMe(board: Board, u: Update): Boolean = forMe(board, u.text) || u.thread.any { !it.fromMe && forMe(board, it.text) }

    /** "Jimmy, please remind…" reads "Please remind…" under Jimmy's name on a notification. */
    fun said(board: Board, text: String): String =
        text.replace(Regex("^\\s*(hey|hi|hello|bonjour|salut|allo)?\\s*${Regex.escape(name(board))}\\s*[,!:.]?\\s*", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { it.uppercase() }

    /** The person's first name: "Sarah (wife)" → "Sarah". */
    fun firstName(from: String): String = from.substringBefore(" (").substringBefore(" <").trim().split(" ").first().ifBlank { from }
}
