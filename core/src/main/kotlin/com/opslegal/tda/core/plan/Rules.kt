package com.opslegal.tda.core.plan

import com.opslegal.tda.core.agent.Memory
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.FilteredItem
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.Learned
import java.time.LocalDate
import java.util.Locale

/**
 * Rules first, on the phone, free: what the user's taps teach ([learn]) and how messages are filtered ([filter]).
 * Nothing is dropped silently: what doesn't deserve an alert waits in Filtered for 30 days.
 */
object Rules {
    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    /** The sender's address in "Name <a@b.c>", or the whole text folded. */
    fun senderKey(from: String): String = (Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from).let(::fold)

    /** Records (or replaces) what was learned for [key] in [lib]. */
    fun learn(board: Board, lib: String, key: String, value: String, from: String, today: LocalDate): Board {
        val k = fold(key); if (k.isBlank()) return board
        val rest = board.ruleLearned.filterNot { it.lib == lib && it.key == k }
        return board.copy(ruleLearned = listOf(Learned(BoardOps.newId(), lib, k, value, from, today.toString())) + rest.take(499))
    }

    fun forget(board: Board, id: String): Board = board.copy(ruleLearned = board.ruleLearned.filterNot { it.id == id })

    fun valueOf(board: Board, lib: String, key: String): String? = fold(key).let { k -> board.ruleLearned.firstOrNull { it.lib == lib && it.key == k }?.value }

    /** The category and area learned for a store, when the user sorted it before. */
    fun store(board: Board, vendor: String): Pair<String, String>? {
        val v = fold(vendor); if (v.length < 3) return null
        val hit = board.ruleLearned.firstOrNull { it.lib == "stores" && (v == it.key || v.startsWith(it.key) || it.key.startsWith(v)) } ?: return null
        return hit.value.substringBefore('|') to hit.value.substringAfter('|', "")
    }

    private val BRANDS = mapOf(
        "desjardins" to "desjardins", "rbc" to "rbc", "td bank" to "td.com", "bmo" to "bmo", "scotia" to "scotiabank", "banque nationale" to "bnc",
        "national bank" to "bnc", "interac" to "interac", "paypal" to "paypal", "amazon" to "amazon", "apple" to "apple", "microsoft" to "microsoft",
        "netflix" to "netflix", "postes canada" to "canadapost", "canada post" to "canadapost", "revenu quebec" to "revenuquebec", "hydro-quebec" to "hydroquebec",
    )
    private val URGENT = Regex("(suspend|verify your|confirm your|locked|unusual|refund|remboursement|suspendu|verifi|confirmer|mettre a jour vos|update your payment|colis en attente|package on hold)")

    /** A sender that names a bank or a brand but writes from another address, with pressure words: likely a scam. */
    fun scam(i: Incoming): String? {
        val addr = Regex("<([^>]+)>").find(i.from)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        val domain = addr.substringAfter('@', "")
        val name = fold(i.from.substringBefore('<') + " " + i.text.take(200))
        val brand = BRANDS.entries.firstOrNull { (b, _) -> Regex("\\b${Regex.escape(b)}\\b").containsMatchIn(name) } ?: return null
        if (domain.contains(brand.value)) return null
        if (!URGENT.containsMatchIn(fold(i.text))) return null
        return "it names ${brand.key.replaceFirstChar { it.uppercase() }} but comes from $domain"
    }

    /** Why [i] goes to Filtered (kind and reason), or null when it deserves to be read. What the user said wins. */
    fun filter(board: Board, i: Incoming): Pair<String, String>? {
        val key = senderKey(i.from)
        when (valueOf(board, "filter", key)) {
            "show" -> return null
            "ignore" -> return "ignored" to "you said: always ignore this sender"
            "scam" -> return "scam" to "you marked this sender as a scam"
        }
        if (board.lookIgnore.any { fold(it).isNotBlank() && key.contains(fold(it)) }) return "ignored" to "you said: always ignore this sender"
        scam(i)?.let { return "scam" to it }
        if (Memory.noise(i)) return "ad" to "an unsubscribe link, sale words or a newsletter address"
        return null
    }

    /** Adds what was filtered (newest first), keeps 30 days and 200 at most. */
    fun keepFiltered(board: Board, items: List<Pair<Incoming, Pair<String, String>>>, today: LocalDate): Board {
        val since = today.minusDays(30).toString()
        val known = board.filtered.map { it.mailId.ifBlank { it.from + it.title } }.toHashSet()
        val added = items.filter { (i, _) -> known.add(i.mailId.ifBlank { i.from + i.text.take(120) }) }
            .map { (i, k) -> FilteredItem(BoardOps.newId(), k.first, i.from, i.text.take(160), k.second, i.at, i.mailId, i.source) }
        return board.copy(filtered = (added + board.filtered).filter { it.at.take(10) >= since }.take(200))
    }
}
