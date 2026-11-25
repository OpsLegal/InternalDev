package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import java.time.Duration
import java.time.LocalDateTime

/**
 * 💬 People first: answering fast is half of it. By rules, no AI.
 * - "Préviens-moi quand tu arrives" is a promise: a card with the line ready ("Bien arrivé ❤️"), one tap sends it.
 * - Each message from a person shows how long they have waited and 3 ready answers; one fills the line, the user
 *   reads it and taps Send. "Tonight" also puts "Answer … properly" on today's ⚡ line, so the promise is kept.
 */
object People {
    data class Promise(val event: String, val icon: String, val button: String, val line: String)
    data class Answer(val icon: String, val label: String, val text: String, val later: Boolean = false)

    private val CHATS = setOf("whatsapp", "sms", "signal", "messenger", "telegram", "instagram", "teams", "imessage", "beeper", "discord", "slack", "linkedin")
    private val ASK = Regex("""pr[ée]viens|dis[- ]moi|[ée]cris[- ]moi|tiens[- ]moi|appelle[- ]moi|let me know|tell me|text me|message me|call me|send me a (message|text)|keep me posted""", RegexOption.IGNORE_CASE)
    private val FRENCH = Regex("""[éèàçù]|\b(je|tu|vous|merci|bonjour|quand|est-ce|pour|avec)\b""", RegexOption.IGNORE_CASE)

    /** A message from a person, in a chat (answered through Beeper), not an email or a newsletter. */
    fun isPerson(u: Update): Boolean = u.source.lowercase() in CHATS || (u.chatId.isNotBlank() && u.source !in Updates.EMAIL)

    fun french(text: String) = FRENCH.containsMatchIn(text)
    fun firstName(u: Update) = u.from.substringBefore('(').substringBefore('<').trim().split(' ').firstOrNull().orEmpty()

    /** "Let me know when you arrive / get home / it's done": the line to send when it happens. */
    fun promise(u: Update): Promise? {
        if (!isPerson(u)) return null
        val t = u.text.ifBlank { u.thread.lastOrNull { !it.fromMe }?.text.orEmpty() }
        if (!ASK.containsMatchIn(t)) return null
        val fr = french(t)
        return when {
            Regex("arriv|atterr|land", RegexOption.IGNORE_CASE).containsMatchIn(t) -> Promise("arrive", "✈️", "Arrived", if (fr) "Bien arrivé ❤️" else "Arrived safely ❤️")
            Regex("rentr|home|maison", RegexOption.IGNORE_CASE).containsMatchIn(t) -> Promise("home", "🏠", "Home", if (fr) "Bien rentré ❤️" else "Home safe ❤️")
            Regex("fini|termin|done|finish|fait", RegexOption.IGNORE_CASE).containsMatchIn(t) -> Promise("done", "✅", "Done", if (fr) "C'est fait ✅" else "Done ✅")
            else -> null
        }
    }

    /** The promises still to keep (cards not handled yet), oldest first. */
    fun promises(updates: List<Update>): List<Pair<Update, Promise>> = updates.mapNotNull { u -> promise(u)?.let { u to it } }

    /** Three ready answers in the person's language: got it, yes, and "tonight" (which also plans the real answer). */
    fun answers(u: Update): List<Answer> {
        val fr = french(u.text + " " + u.thread.joinToString(" ") { it.text })
        val n = firstName(u)
        return if (fr) listOf(Answer("👍", "Bien reçu", "Bien reçu $n 👍"), Answer("✅", "Oui, ok", "Oui, c'est ok pour moi ✅"),
            Answer("🕗", "Ce soir", "Bien reçu $n, je te réponds ce soir 🙏", later = true))
        else listOf(Answer("👍", "Got it", "Got it $n 👍"), Answer("✅", "Yes, OK", "Yes, that works for me ✅"),
            Answer("🕗", "Tonight", "Got it $n, I'll get back to you tonight 🙏", later = true))
    }

    /** How long the person has waited, in hours (null when unknown). */
    fun waitedHours(u: Update, now: LocalDateTime): Long? =
        (u.at.ifBlank { u.createdAt }).takeIf { it.isNotBlank() }?.let { runCatching { Duration.between(LocalDateTime.parse(it.take(19)), now).toHours().coerceAtLeast(0) }.getOrNull() }

    fun waitLabel(h: Long?): String = when {
        h == null -> "new"
        h < 1 -> "just now"
        h < 24 -> "waiting $h h"
        else -> "waiting ${h / 24} day" + if (h >= 48) "s" else ""
    }

    /** "Answer … properly" on today's ⚡ line: what "Tonight" promised. */
    fun answerLater(board: Board, u: Update, today: java.time.LocalDate): Board =
        Quick.add(board, listOf("↩ Answer ${firstName(u)} properly"), today, today, impact = 3).first
}
