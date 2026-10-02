package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import java.time.LocalDate

/**
 * Writes the answer the USER will review and send. Rule 1 (built in, no setting changes it): the app
 * prepares replies, it never sends, accepts or declines anything; the user does the last tap in Outlook or Beeper.
 */
object ReplyWriter {

    const val RULE_1 = "I prepare replies. Nothing ever leaves on its own: emails wait in your Outlook Drafts, and a message goes only " +
        "when you tap Send after reading it. I never accept or decline anything for you."

    /** The user's answer to a meeting request, or, for a request that needs work, "I'll get back to you" ([LATER]). */
    enum class Choice { ACCEPT, DECLINE, OTHER_TIME, LATER }

    suspend fun write(
        provider: LlmProvider, board: Board, update: Update, choice: Choice?, slots: List<String>, current: String, today: LocalDate,
        promise: String? = null,
    ): String {
        val reply = provider.complete("Reply with the message only.", listOf(ChatItem.User(prompt(board, update, choice, slots, current, today, promise))), emptyList()).text
        return clean(reply).ifBlank { error("The reply could not be written. Try again.") }
    }

    fun prompt(board: Board, u: Update, choice: Choice?, slots: List<String>, current: String, today: LocalDate, promise: String? = null): String = buildString {
        appendLine("You are the user's assistant. Write the reply THE USER will read before sending it themselves. You never send anything.")
        appendLine("Today is $today.")
        if (u.thread.isNotEmpty()) {
            appendLine("The conversation with ${u.from} (via ${u.source}), oldest first. Answer everything after the user's last message;")
            appendLine("the last message may be a small word (\"ok\", \"merci\"): find the real request before it, and the person's intent.")
            u.thread.forEach { m -> appendLine("${if (m.fromMe) "USER" else m.sender.ifBlank { u.from }} (${m.time}): ${m.text}") }
        } else {
            appendLine("From: ${u.from} (via ${u.source}). Their message: \"\"\"${u.text}\"\"\"")
        }
        if (choice == Choice.LATER) {
            appendLine("The user needs time to do it properly: write a short acknowledgment saying they are looking at it and will come back " +
                (promise?.let { "by $it" } ?: "soon") + ". Do not answer the substance.")
        }
        if (u.meeting.isNotBlank()) {
            appendLine("It is a meeting request: ${u.meeting}.")
            when (choice) {
                Choice.ACCEPT -> appendLine("The user accepts.")
                Choice.DECLINE -> appendLine("The user declines, politely, without a long excuse.")
                Choice.OTHER_TIME -> appendLine("The user can't then and offers these times instead: ${slots.joinToString("; ").ifBlank { "(none free: ask them to suggest a time next week)" }}.")
                Choice.LATER, null -> Unit
            }
        }
        appendLine("Their plan this week, for context only (never list it): ${AgentTools.describe(board, today, 7).lines().take(12).joinToString(" / ")}")
        if (current.isNotBlank()) appendLine("Improve this draft as the user asked, keeping their changes: \"\"\"$current\"\"\"")
        appendLine("Write a short, warm, professional reply in the language of their messages, in the tone the conversation already has. Never promise what the plan can't hold.")
        appendLine("No subject line, no signature placeholder, no quotes around it: only the message text.")
    }

    /** Drops quotes or a "Subject:" line the AI may add around the message. */
    fun clean(text: String): String = text.trim()
        .lines().dropWhile { it.startsWith("Subject:", ignoreCase = true) || it.startsWith("Objet", ignoreCase = true) }
        .joinToString("\n").trim().removeSurrounding("\"").trim()
}
