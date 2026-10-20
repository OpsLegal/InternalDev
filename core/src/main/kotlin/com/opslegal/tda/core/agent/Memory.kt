package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Incoming
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The assistant's memory, kept on the phone only: every email, message and notification already read, once. Filling it
 * costs no AI: it is the text the phone already received. Checks send the AI only what is new and worth reading;
 * ✨ Prepare and the assistant's questions search here first (instant, free) before reading the mailbox again.
 */
@Serializable
data class MemItem(
    /** One per email, per chat message, per notification: what makes "already read". */
    val key: String,
    val source: String,
    val from: String,
    val text: String,
    val at: String,
    val mailId: String = "",
    val chatId: String = "",
    /** What already analysed it ("check", "look", "money"): each analysis sends the AI only what it hasn't seen. */
    val seenBy: Set<String> = emptySet(),
)

@Serializable
data class MemoryFile(val items: List<MemItem> = emptyList())

object Memory {
    const val KEEP_DAYS = 400L
    const val MAX = 5000

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun keyOf(i: Incoming): String = when {
        i.mailId.isNotBlank() -> "mail:${i.mailId}"
        i.chatId.isNotBlank() -> "chat:${i.chatId}@${i.at}"
        else -> "note:${i.source}:${(fold(i.from) + "|" + fold(i.text).take(200)).hashCode()}"
    }

    private fun textOf(i: Incoming) = (listOf(i.text) + i.thread.map { (if (it.fromMe) "me: " else "${it.sender}: ") + it.text }).distinct().joinToString(" / ").take(2000)

    /** Keeps what was not known yet, and returns those new items only (the rest was already read). */
    fun remember(file: MemoryFile, items: List<Incoming>, today: LocalDate): Pair<MemoryFile, List<Incoming>> {
        val known = file.items.map { it.key }.toHashSet()
        val fresh = items.filter { known.add(keyOf(it)) }
        if (fresh.isEmpty()) return file to emptyList()
        val added = fresh.map { MemItem(keyOf(it), it.source, it.from, textOf(it), it.at, it.mailId, it.chatId) }
        return prune(file.copy(items = file.items + added), today) to fresh
    }

    /** Only the items [by] has not analysed yet; [mark] records them as analysed once the analysis worked. */
    fun unseenBy(file: MemoryFile, items: List<Incoming>, by: String): List<Incoming> {
        val done = file.items.filter { by in it.seenBy }.map { it.key }.toHashSet()
        return items.filter { keyOf(it) !in done }
    }

    fun mark(file: MemoryFile, items: List<Incoming>, by: String): MemoryFile {
        val keys = items.map(::keyOf).toHashSet()
        return file.copy(items = file.items.map { if (it.key in keys && by !in it.seenBy) it.copy(seenBy = it.seenBy + by) else it })
    }

    private val NOISE_FROM = Regex("""(?i)(no-?reply|newsletter|news@|marketing|promo|mailer-daemon|notifications?@|digest|updates@|hello@|info@mailchimp|bounce)""")
    private val NOISE_TEXT = Regex("""(?i)(unsubscribe|se d[ée]sabonner|d[ée]sinscri|view (this email )?in (your )?browser|voir (ce courriel )?dans (votre|le) navigateur|verification code|code de v[ée]rification|one-time (pass)?code|% off|rabais exclusif|webinar|webinaire)""")

    /**
     * Not worth an AI reading: newsletters, promotions, automatic codes. Read on the phone, no AI. Invoices, receipts and
     * payments always stay (they are worth it, even from an automatic sender).
     */
    fun noise(i: Incoming): Boolean {
        if (MoneyCheck.relevant(listOf(i)).isNotEmpty()) return false
        return NOISE_FROM.containsMatchIn(i.from) || NOISE_TEXT.containsMatchIn(i.text)
    }

    /** What was read about these words (a name, a file, a project), most relevant and recent first. Local and free. */
    fun search(file: MemoryFile, query: String, limit: Int = 8): List<MemItem> {
        val words = fold(query).split(Regex("[^a-z0-9@.]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return emptyList()
        return file.items.map { m -> val hay = fold(m.from + " " + m.text); m to words.count { hay.contains(it) } }
            .filter { it.second > 0 && (words.size == 1 || it.second >= 2 || it.second * 2 >= words.size) }
            .sortedWith(compareByDescending<Pair<MemItem, Int>> { it.second }.thenByDescending { it.first.at })
            .take(limit).map { it.first }
    }

    /** The last [KEEP_DAYS] days, [MAX] items at most (oldest out first). */
    fun prune(file: MemoryFile, today: LocalDate): MemoryFile {
        val since = today.minusDays(KEEP_DAYS).toString()
        return file.copy(items = file.items.filter { it.at.take(10) >= since }.sortedBy { it.at }.takeLast(MAX))
    }

    fun label(m: MemItem): String = (if (m.mailId.isNotBlank() || m.source in setOf("outlook", "gmail", "email")) "Email" else if (m.chatId.isNotBlank()) "Messages" else m.source.replaceFirstChar { it.uppercase() }) +
        " from ${m.from.take(60)}, ${m.at.take(10)}"
}

/** The memory, as the assistant's tools see it (the app keeps it in a file on the phone). */
fun interface MemorySearch {
    suspend fun search(query: String, limit: Int): List<MemItem>
}
