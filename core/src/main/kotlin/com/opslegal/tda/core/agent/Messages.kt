package com.opslegal.tda.core.agent

/** A conversation in the user's messaging apps (WhatsApp, SMS, Instagram...), read only. */
data class ChatSummary(
    val id: String,
    val title: String,
    val network: String,
    val lastMessage: String,
    val unread: Int,
    /** ISO local date-time of the last activity. */
    val lastActivity: String,
    /** A conversation with one person (not a group). */
    val oneToOne: Boolean = true,
)

/** Reading a conversation the way the user would before answering. */
object Threads {
    /**
     * What the other person wrote since the user's last reply, with [before] messages ahead of it for
     * context (a closing "ok merci" doesn't hide the request). Null when the user wrote last. Oldest first.
     */
    fun sinceMyLastReply(messages: List<MessageItem>, before: Int = 2, max: Int = 12): List<MessageItem>? {
        val chrono = messages.sortedBy { it.time }
        if (chrono.isEmpty() || chrono.last().fromMe) return null
        val lastMine = chrono.indexOfLast { it.fromMe }
        val start = if (lastMine < 0) 0 else (lastMine - before).coerceAtLeast(0)
        return chrono.subList(start, chrono.size).takeLast(max)
    }

    /** When the person started waiting: their first message after the user's last reply. */
    fun waitingSince(thread: List<MessageItem>): String? {
        val lastMine = thread.indexOfLast { it.fromMe }
        return thread.drop(lastMine + 1).firstOrNull()?.time
    }
}

data class MessageItem(
    val chat: String,
    val sender: String,
    val text: String,
    /** ISO local date-time. */
    val time: String,
    val fromMe: Boolean,
    /** For searches: true for the matching message, false for surrounding context. */
    val isMatch: Boolean = false,
)

/**
 * Where messages come from. On Android: Beeper, which gathers WhatsApp, SMS, Messenger,
 * Instagram, Signal, Telegram... Read only: the assistant never sends messages.
 */
interface MessageSource {
    suspend fun recentChats(limit: Int, unreadOnly: Boolean): List<ChatSummary>

    /** Messages matching [query] (with a little context), or the latest ones of [chatId]. */
    suspend fun messages(query: String?, chatId: String?, limit: Int): List<MessageItem>
}
