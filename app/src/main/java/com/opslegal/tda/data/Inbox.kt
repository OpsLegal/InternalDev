package com.opslegal.tda.data

import android.content.Context
import com.opslegal.tda.core.model.Incoming
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What arrived on the phone since the last check (sender, subject, first lines), kept on this phone only
 * and emptied by each check. Written by the notification listener, read by the updates check.
 */
class Inbox(context: Context) {
    private val file = File(context.filesDir, "inbox.json")
    private val serializer = ListSerializer(Incoming.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun add(item: Incoming) {
        val items = read()
        // The same notification is often posted again (updated, re-grouped): keep one.
        if (items.any { it.source == item.source && it.from == item.from && it.text == item.text }) return
        write((items + item).takeLast(MAX))
    }

    /** Everything waiting, removed from the inbox. */
    @Synchronized
    fun drain(): List<Incoming> = read().also { if (it.isNotEmpty()) write(emptyList()) }

    /** Puts back items a failed check could not handle. */
    @Synchronized
    fun restore(items: List<Incoming>) {
        if (items.isNotEmpty()) write((items + read()).takeLast(MAX))
    }

    @Synchronized
    fun count(): Int = read().size

    private fun read(): List<Incoming> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun write(items: List<Incoming>) {
        val tmp = File(file.parentFile, "inbox.json.tmp")
        tmp.writeText(json.encodeToString(serializer, items))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        private const val MAX = 200
    }
}
