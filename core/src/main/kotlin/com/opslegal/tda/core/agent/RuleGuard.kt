package com.opslegal.tda.core.agent

/**
 * A new rule only shapes how the assistant plans and writes. It can never give it a power the app doesn't have
 * (sending, accepting, paying, deleting without the user's tap) nor remove a safety: those are refused, kindly.
 * The protections themselves live in code; this only keeps the rule list honest.
 */
object RuleGuard {
    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private val ACT = Regex("\\b(send|envo|repl|repon|answer|accept|decline|refus|pay|pai|delete|supprim|forward|transf)")
    private val ALONE = Regex("\\b(automatic|without|sans|by yourself|toi meme|for me|a ma place|always|toujours|on your own|tout seul)")
    private val OFF = Regex("\\b(ignore|forget|bypass|override|disable|desactiv|oublie)\\b.*\\b(rule|regle|confirm|limit|safety|securit)")
    private val CELLS = Regex("\\b(more than 5|plus de 5|6 cells|six cells|7 cells|6 cases|six cases)")

    /** Null when the rule can be added; otherwise why not, in the assistant's words. */
    fun refuse(text: String): String? {
        val t = fold(text.trim())
        return when {
            ACT.containsMatchIn(t) && ALONE.containsMatchIn(t) -> "That one I can't take: Rule 1 says I never send, accept, decline, pay or delete anything without your tap."
            OFF.containsMatchIn(t) -> "That one I can't take: it would remove a safety of the app."
            CELLS.containsMatchIn(t) -> "That one I can't take: the day has 5 cells, by design."
            t.length < 8 || t.length > 300 -> "A rule is one clear sentence about how I plan or write."
            else -> null
        }
    }
}
