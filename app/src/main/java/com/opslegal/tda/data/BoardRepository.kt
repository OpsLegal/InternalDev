package com.opslegal.tda.data

import android.content.Context
import com.opslegal.tda.core.agent.BoardStore
import com.opslegal.tda.core.agent.ChatItem
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.DefaultRules
import com.opslegal.tda.core.plan.BoardOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Writes through a temp file so a crash mid-write never corrupts the data. */
private fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        writeText(text)
        tmp.delete()
    }
}

/** The whole table, rules and assistant memory, stored as one JSON file. */
class BoardRepository(context: Context) : BoardStore {
    private val file = File(context.filesDir, "board.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    val board: StateFlow<Board> = state.asStateFlow()

    private fun load(): Board {
        val loaded = runCatching { json.decodeFromString(Board.serializer(), file.readText()) }.getOrNull()
            ?: return Board(rules = DefaultRules.all, version = 2)
        // Boards saved before the two levels (task / project) are converted once, then written back.
        val migrated = BoardOps.migrateToTwoLevels(loaded, LocalDate.now())
        if (migrated != loaded) runCatching { file.writeAtomically(json.encodeToString(Board.serializer(), migrated)) }
        return migrated
    }

    override suspend fun read(): Board = state.value

    override suspend fun update(change: (Board) -> Board): Board = mutex.withLock {
        val next = change(state.value)
        if (next != state.value) {
            withContext(Dispatchers.IO) { file.writeAtomically(json.encodeToString(Board.serializer(), next)) }
            state.value = next
        }
        next
    }
}

/** The conversation with the assistant. */
class ChatRepository(context: Context) {
    private val file = File(context.filesDir, "chat.json")
    private val serializer = ListSerializer(ChatItem.serializer())
    private val mutex = Mutex()
    private val state = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()),
    )

    val items: StateFlow<List<ChatItem>> = state.asStateFlow()

    // Past conversations, kept when the user clears the chat, so the search can find them again.
    private val pastFile = File(context.filesDir, "past-chats.json")
    private val pastSerializer = ListSerializer(com.opslegal.tda.core.plan.PastChat.serializer())
    private val pastState = MutableStateFlow(runCatching { json.decodeFromString(pastSerializer, pastFile.readText()) }.getOrDefault(emptyList()))
    val past: StateFlow<List<com.opslegal.tda.core.plan.PastChat>> = pastState.asStateFlow()

    suspend fun append(item: ChatItem) = mutex.withLock {
        // Keep the history bounded; the board itself is the long-term memory.
        val next = trim(state.value + item)
        state.value = next
        withContext(Dispatchers.IO) { file.writeAtomically(json.encodeToString(serializer, next)) }
    }

    suspend fun clear() = mutex.withLock {
        val lines = state.value.mapNotNull { when (it) { is ChatItem.User -> "You: ${it.text}"; is ChatItem.Assistant -> it.text.takeIf { t -> t.isNotBlank() }?.let { t -> "Assistant: $t" }; else -> null } }
        if (lines.isNotEmpty()) {
            val title = (state.value.firstOrNull { it is ChatItem.User } as? ChatItem.User)?.text?.take(70) ?: lines.first().take(70)
            val next = (listOf(com.opslegal.tda.core.plan.PastChat(java.util.UUID.randomUUID().toString().take(8), java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ').take(16), title, lines.takeLast(60))) + pastState.value).take(60)
            pastState.value = next
            withContext(Dispatchers.IO) { pastFile.writeAtomically(json.encodeToString(pastSerializer, next)) }
        }
        state.value = emptyList()
        withContext(Dispatchers.IO) { file.delete() }
    }

    /** Drops the oldest exchanges, always cutting at a user message so tool calls stay paired. */
    private fun trim(items: List<ChatItem>, max: Int = 80): List<ChatItem> {
        if (items.size <= max) return items
        val start = items.indices.firstOrNull { it >= items.size - max && items[it] is ChatItem.User } ?: return items
        return items.drop(start)
    }
}
