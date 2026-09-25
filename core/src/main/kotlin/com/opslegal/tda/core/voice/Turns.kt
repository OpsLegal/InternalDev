package com.opslegal.tda.core.voice

import com.opslegal.tda.core.model.ConversationSettings

/**
 * Decides when the person has finished speaking. People with ADD often pause mid-idea,
 * so the assistant waits for a real end of turn: a spoken "I'm done" phrase, or a pause
 * long enough (longer when the sentence is obviously unfinished).
 */
object TurnDetector {

    private val connectors = setOf(
        // English
        "and", "or", "but", "because", "so", "then", "to", "with", "for", "the", "a", "an", "of", "on", "at",
        "in", "like", "um", "uh", "also", "if", "before", "after", "when", "which", "my", "about", "until", "from",
        // French
        "et", "ou", "mais", "parce", "que", "qu'", "donc", "puis", "alors", "avec", "pour", "le", "la", "les", "un",
        "une", "de", "du", "des", "à", "au", "aux", "sur", "euh", "si", "avant", "après", "quand", "mon", "ma", "mes",
    )

    /** If [text] ends with one of [phrases], returns the text without it; otherwise null. */
    fun stripEndPhrase(text: String, phrases: List<String>): String? {
        val normalized = normalize(text)
        for (phrase in phrases.map(::normalize).filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            if (normalized == phrase || normalized.endsWith(" $phrase")) {
                val words = text.trim().split(Regex("\\s+"))
                val keep = (words.size - phrase.split(" ").size).coerceAtLeast(0)
                return words.take(keep).joinToString(" ").trimEnd(',', ';', ' ', '.')
            }
        }
        return null
    }

    /** True when the sentence clearly continues: ends with a connector, a comma or "...". */
    fun looksUnfinished(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith(",") || trimmed.endsWith("...") || trimmed.endsWith("…")) return true
        val last = normalize(trimmed).substringAfterLast(' ')
        return last in connectors
    }

    /** How long to wait in silence after the last recognized words before ending the turn. */
    fun pauseMillis(text: String, settings: ConversationSettings): Long {
        val base = (settings.pauseSeconds.coerceIn(0.5f, 20f) * 1000).toLong()
        return if (settings.waitWhenUnfinished && looksUnfinished(text)) base * 2 else base
    }

    internal fun normalize(text: String): String =
        text.lowercase().replace(Regex("[.,!?;:«»\"]"), " ").replace('’', '\'').trim().replace(Regex("\\s+"), " ")
}

enum class Reply { YES, NO, OTHER }

/** Classifies an answer to "Shall I do it?" without calling the AI. */
object ReplyClassifier {
    private val buts = setOf("but", "except", "mais", "sauf")

    fun classify(text: String, settings: ConversationSettings): Reply {
        var normalized = TurnDetector.normalize(text)
        if (normalized.isEmpty()) return Reply.OTHER
        fun has(phrase: String): Boolean {
            val p = TurnDetector.normalize(phrase)
            return p.isNotEmpty() && (" $normalized ").contains(" $p ")
        }
        // "No problem" / "pourquoi pas" mean yes although they contain a "no" word.
        val yesWithNo = settings.yesWords.map(TurnDetector::normalize)
            .filter { y -> settings.noWords.any { n -> (" $y ").contains(" ${TurnDetector.normalize(n)} ") } }
            .sortedByDescending { it.length }
        for (y in yesWithNo) normalized = (" $normalized ").replace(" $y ", " __yes__ ").trim()
        val words = normalized.split(" ")
        if (settings.noWords.any(::has)) return Reply.NO
        // "Yes but move it to Friday" is a correction, not a confirmation.
        if (words.any { it in buts }) return Reply.OTHER
        if (words.size <= 6 && ("__yes__" in words || settings.yesWords.any(::has))) return Reply.YES
        return Reply.OTHER
    }
}
