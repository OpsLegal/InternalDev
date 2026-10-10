package com.opslegal.tda.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the assistant reads for the user, with their own AI: a receipt (to fill an expense), a form (what to fill), the
 * user's words about a document (a clean name and intention). It only fills fields: the user checks before saving.
 */
object Readers {
    data class Receipt(val total: Double?, val taxes: Double?, val tip: Double?, val where: String?, val date: String?, val category: String?)
    data class Fill(val items: List<String>, val known: List<String>)
    data class DocName(val what: String, val name: String, val intention: String)

    private suspend fun ask(provider: LlmProvider, prompt: String, files: List<ChatItem.Attachment>): JsonObject? {
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt, files)), emptyList()).text
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.list(k: String) = runCatching { this[k]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())

    suspend fun receipt(provider: LlmProvider, photo: ChatItem.Attachment, categories: List<String>): Receipt? = ask(provider,
        "This image is a receipt. Read it. Amounts as numbers in its currency, no symbols; date as YYYY-MM-DD; null when not shown. " +
            "\"total\" = the amount paid, tip included; \"taxes\" = all taxes together (e.g. GST + QST, TPS + TVQ); \"tip\" = the tip or gratuity; " +
            "\"where\" = the business name; \"category\" one of ${categories.joinToString()}.\n" +
            "Reply with only {\"total\": number|null, \"taxes\": number|null, \"tip\": number|null, \"where\": string|null, \"date\": string|null, \"category\": string|null}.",
        listOf(photo),
    )?.let { o -> Receipt(o.num("total"), o.num("taxes"), o.num("tip"), o.str("where"), o.str("date"), o.str("category")?.takeIf { it in categories }) }

    suspend fun whatToFill(provider: LlmProvider, docName: String, task: String, about: String, file: ChatItem.Attachment?): Fill? = ask(provider,
        "The user must complete a document for a task. Document: \"$docName\". Task: $task. ${if (about.isNotBlank()) "About the user: $about." else ""}\n" +
            (if (file != null) "The document is attached: read it. " else "You only have its name and the task. ") +
            "List what they have to fill in or gather (max 8 short items, their language), and what you already know from the task (max 3).\n" +
            "Reply with only {\"items\": [string], \"known\": [string]}.",
        listOfNotNull(file),
    )?.let { o -> Fill(o.list("items").take(8), o.list("known").take(3)).takeIf { it.items.isNotEmpty() } }

    suspend fun docName(provider: LlmProvider, words: String, fileName: String, file: ChatItem.Attachment?): DocName? = ask(provider,
        "A user must fill in a document. Their words: \"$words\". ${if (fileName.isNotBlank()) "The file: \"$fileName\"." else ""} ${if (file != null) "The document is attached." else ""}\n" +
            "Clean it up, in their language: \"what\" = a clear description (one line), \"name\" = a short cell title starting with a verb (max 40 characters, " +
            "e.g. \"Fill the SAAQ claim form\"), \"intention\" = why it matters, one sentence starting with \"To\".\n" +
            "Reply with only {\"what\": string, \"name\": string, \"intention\": string}.",
        listOfNotNull(file),
    )?.let { o -> o.str("name")?.let { DocName(o.str("what") ?: words, it.take(60), o.str("intention").orEmpty()) } }

    /** Without the assistant: a tidy version of the user's words (no file extension, no leading "the", whole words). */
    fun tidy(words: String, fileName: String): DocName {
        val w = words.ifBlank { fileName }.replace(Regex("\\.[A-Za-z0-9]+$"), "").replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(the|a|an|le|la|les|un|une|l')\\s*", RegexOption.IGNORE_CASE), "")
        val c = w.replaceFirstChar { it.uppercase() }
        var n = "Fill: $c"
        if (n.length > 40) n = n.take(40).replace(Regex("\\s+\\S*$"), "") + "…"
        return DocName(c, n, "To get it filled in and sent on time.")
    }
}
