package com.opslegal.tda.core.voice

/**
 * Guesses which of the user's languages a text is written in, from very common words.
 * Used to pick the voice that reads an answer aloud, so a French answer is read in French.
 */
object LanguageGuess {
    private val commonWords: Map<String, Set<String>> = mapOf(
        "en" to setOf(
            "the", "and", "is", "are", "you", "your", "to", "of", "it", "for", "on", "with", "i", "we", "this", "that",
            "have", "will", "shall", "do", "what", "tomorrow", "today", "yes", "no", "please", "can", "my",
        ),
        "fr" to setOf(
            "le", "la", "les", "et", "est", "sont", "vous", "tu", "de", "des", "du", "un", "une", "pour", "avec", "je",
            "nous", "ce", "cette", "que", "qui", "demain", "aujourd'hui", "oui", "non", "mon", "ma", "mes", "pas", "faire", "à",
        ),
        "es" to setOf(
            "el", "la", "los", "las", "y", "es", "son", "usted", "tú", "de", "un", "una", "para", "con", "yo", "que",
            "mañana", "hoy", "sí", "no", "mi", "hacer",
        ),
        "de" to setOf("der", "die", "das", "und", "ist", "sind", "sie", "du", "ich", "mit", "für", "morgen", "heute", "ja", "nein", "nicht"),
        "it" to setOf("il", "lo", "gli", "e", "è", "sono", "lei", "tu", "di", "un", "una", "per", "con", "io", "che", "domani", "oggi", "sì"),
        "pt" to setOf("o", "os", "as", "e", "é", "são", "você", "de", "um", "uma", "para", "com", "eu", "que", "amanhã", "hoje", "sim", "não"),
    )

    /**
     * Returns the tag from [candidates] (e.g. "fr-CA", "en-CA") that best matches [text].
     * Falls back to the first candidate when nothing stands out.
     */
    fun guess(text: String, candidates: List<String>): String {
        if (candidates.size <= 1) return candidates.firstOrNull() ?: "en-US"
        val words = text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val scores = candidates.associateWith { tag ->
            val known = commonWords[tag.substringBefore('-').lowercase()] ?: emptySet()
            words.count { it in known || (it.contains('\'') && it.substringBefore('\'') + "'" in known) }
        }
        val best = scores.maxByOrNull { it.value } ?: return candidates.first()
        // A clear winner only; otherwise keep the main language.
        return if (best.value > 0 && scores.values.count { it == best.value } == 1) best.key else candidates.first()
    }

    /** Human name of a language tag, in that language. */
    fun displayName(tag: String): String = when (tag) {
        "en-CA" -> "English (Canada)"
        "en-US" -> "English (US)"
        "en-GB" -> "English (UK)"
        "fr-CA" -> "Français (Canada)"
        "fr-FR" -> "Français (France)"
        "es-ES" -> "Español (España)"
        "es-MX" -> "Español (México)"
        else -> tag
    }

    /** Languages offered in Settings. Adding one here is all it takes. */
    val offered = listOf("en-CA", "fr-CA", "en-US", "en-GB", "fr-FR", "es-ES", "es-MX")
}
