package com.opslegal.tda.data

import android.content.Context
import com.opslegal.tda.core.agent.MemItem
import com.opslegal.tda.core.agent.Memory
import com.opslegal.tda.core.agent.MemoryFile
import com.opslegal.tda.core.agent.MemorySearch
import com.opslegal.tda.core.model.Incoming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * The assistant's memory on the phone (memory.json in the app's private storage, never backed up): what was already
 * read, so it is never sent to the AI twice, and found back for free.
 */
class MemoryRepository(context: Context) : MemorySearch {
    private val file = File(context.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val state = MutableStateFlow(runCatching { json.decodeFromString(MemoryFile.serializer(), file.readText()) }.getOrDefault(MemoryFile()))
    val memory: StateFlow<MemoryFile> = state.asStateFlow()

    private suspend fun write(m: MemoryFile) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "memory.json.tmp")
        tmp.writeText(json.encodeToString(MemoryFile.serializer(), m)); tmp.renameTo(file)
    }

    /** Keeps the new ones and returns them (the others were already read). */
    suspend fun remember(items: List<Incoming>): List<Incoming> = lock.withLock {
        val (next, fresh) = Memory.remember(state.value, items, LocalDate.now())
        if (fresh.isNotEmpty()) { state.value = next; write(next) }
        fresh
    }

    fun unseenBy(items: List<Incoming>, by: String): List<Incoming> = Memory.unseenBy(state.value, items, by)

    suspend fun mark(items: List<Incoming>, by: String) = lock.withLock {
        val next = Memory.mark(state.value, items, by); state.value = next; write(next)
    }

    override suspend fun search(query: String, limit: Int): List<MemItem> = Memory.search(state.value, query, limit)

    /** Erases the memory (Settings › Connections › Forget). Nothing else changes. */
    suspend fun forget() = lock.withLock { state.value = MemoryFile(); withContext(Dispatchers.IO) { file.delete() } }
}
