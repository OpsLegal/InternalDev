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
)

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
