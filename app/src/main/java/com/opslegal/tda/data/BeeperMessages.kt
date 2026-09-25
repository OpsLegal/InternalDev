package com.opslegal.tda.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.opslegal.tda.core.agent.ChatSummary
import com.opslegal.tda.core.agent.MessageItem
import com.opslegal.tda.core.agent.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Reads, never sends, the user's chats through Beeper's Android content provider
 * (https://developers.beeper.com/android/content-providers). One permission covers every
 * network Beeper connects: WhatsApp, SMS/Google Messages, Messenger, Instagram, Signal...
 */
class BeeperMessages(private val context: Context) : MessageSource {

    val installed: Boolean
        get() = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, READ_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun recentChats(limit: Int, unreadOnly: Boolean): List<ChatSummary> = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://$AUTHORITY/chats").buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .apply { if (unreadOnly) appendQueryParameter("isUnread", "1") }
            .build()
        query(uri) { c ->
            ChatSummary(
                id = c.string("roomId"),
                title = c.string("title"),
                network = c.string("protocol"),
                lastMessage = c.string("messagePreview"),
                unread = c.int("unreadCount"),
                lastActivity = c.time("timestamp"),
            )
        }
    }

    override suspend fun messages(query: String?, chatId: String?, limit: Int): List<MessageItem> = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://$AUTHORITY/messages").buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .apply {
                if (query != null) {
                    appendQueryParameter("query", query)
                    appendQueryParameter("contextBefore", "1")
                    appendQueryParameter("contextAfter", "1")
                }
                if (chatId != null) appendQueryParameter("roomIds", chatId)
            }
            .build()
        val rows = query(uri) { c ->
            MessageItem(
                chat = c.string("roomId"),
                sender = c.string("displayName"),
                text = c.string("text_content"),
                time = c.time("timestamp"),
                fromMe = c.int("isSentByMe") == 1,
                isMatch = c.int("is_search_match") == 1,
            )
        }
        // Show chat names instead of internal room ids.
        val names = chatTitles(rows.map { it.chat }.distinct())
        rows.map { it.copy(chat = names[it.chat] ?: "chat") }
    }

    private fun chatTitles(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val uri = Uri.parse("content://$AUTHORITY/chats").buildUpon()
            .appendQueryParameter("roomIds", ids.joinToString(","))
            .appendQueryParameter("limit", ids.size.toString())
            .build()
        return query(uri) { c -> c.string("roomId") to c.string("title") }.toMap()
    }

    private fun <T> query(uri: Uri, row: (Cursor) -> T): List<T> {
        if (!permitted) return emptyList()
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }.orEmpty()
    }

    // Columns can change: Beeper marks this API experimental. Missing columns read as empty.
    private fun Cursor.string(name: String): String = getColumnIndex(name).takeIf { it >= 0 }?.let { getString(it) }.orEmpty()
    private fun Cursor.int(name: String): Int = getColumnIndex(name).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
    private fun Cursor.long(name: String): Long = getColumnIndex(name).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

    private fun Cursor.time(name: String): String {
        val raw = long(name)
        if (raw <= 0) return ""
        // Milliseconds, or seconds on older versions.
        val millis = if (raw < 100_000_000_000L) raw * 1000 else raw
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString()
    }

    companion object {
        const val AUTHORITY = "com.beeper.api"
        const val READ_PERMISSION = "com.beeper.android.permission.READ_PERMISSION"
    }
}
