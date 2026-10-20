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
 * The assistant's memory on the phone: what was already read, so it is never sent to the AI twice, and found back for
 * free. Locked with a key held in the phone's secure chip (Android Keystore, never exportable), in the app's private
 * storage, never backed up: a copy of the file is unreadable anywhere else, even on this phone without the app.
 */
class MemoryRepository(context: Context) : MemorySearch {
    private val file = File(context.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val state = MutableStateFlow(load())

    private fun load(): MemoryFile = runCatching {
        val stored = file.readText()
        if (!stored.startsWith("{")) return json.decodeFromString(MemoryFile.serializer(), KeystoreCipher.decrypt(stored, KeystoreCipher.MEMORY) ?: return MemoryFile())
        // A test build left it unlocked: cleaned by today's rules and locked right away.
        val m = Memory.clean(json.decodeFromString(MemoryFile.serializer(), stored))
        File(file.parentFile, "memory.json.tmp").apply { writeText(KeystoreCipher.encrypt(json.encodeToString(MemoryFile.serializer(), m), KeystoreCipher.MEMORY)); renameTo(file) }
        m
    }.getOrElse { file.delete(); MemoryFile() }
    val memory: StateFlow<MemoryFile> = state.asStateFlow()

    private suspend fun write(m: MemoryFile) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "memory.json.tmp")
        tmp.writeText(KeystoreCipher.encrypt(json.encodeToString(MemoryFile.serializer(), m), KeystoreCipher.MEMORY)); tmp.renameTo(file)
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

    /** Erases the memory and its key (Settings › Connections › Forget): even a copied file stays locked forever. */
    suspend fun forget() = lock.withLock {
        state.value = MemoryFile()
        withContext(Dispatchers.IO) { file.delete(); KeystoreCipher.erase(KeystoreCipher.MEMORY) }
    }

    /** Forgets what one connection brought, when the user turns it off. */
    suspend fun forget(which: (MemItem) -> Boolean) = lock.withLock {
        val next = Memory.forget(state.value, which)
        if (next.items.size != state.value.items.size) { state.value = next; write(next) }
    }
}
