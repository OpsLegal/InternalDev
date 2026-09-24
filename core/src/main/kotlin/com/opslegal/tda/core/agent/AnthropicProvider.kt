package com.opslegal.tda.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Claude via the Messages API (`POST /v1/messages`) with the user's own API key.
 * Raw HTTP is used on purpose: the same code path serves several providers and
 * keeps the core portable to iOS later.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: HttpTransport,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProvider {
    override val id = "anthropic"

    override suspend fun complete(system: String, history: List<ChatItem>, tools: List<ToolSpec>): ChatItem.Assistant {
        // Server-side fallback lets a refused request be retried on another model.
        val useFallbacks = model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16000)
            put("system", system)
            if (useFallbacks) put("fallbacks", "default")
            putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.inputSchema)
                    }
                }
            }
            put("messages", messages(history))
        }
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
            if (useFallbacks) put("anthropic-beta", "server-side-fallback-2026-07-01")
        }
        val response = http.postJson("$baseUrl/v1/messages", headers, body.toString())
        if (response.code !in 200..299) throw LlmException(errorMessage(response), response.code)

        val json = Json.parseToJsonElement(response.body).jsonObject
        val content = json["content"]?.jsonArray ?: JsonArray(emptyList())
        if (json["stop_reason"]?.jsonPrimitive?.content == "refusal") {
            throw LlmException("The model declined this request. Try rephrasing it.")
        }
        val text = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        val calls = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }.map {
            val o = it.jsonObject
            ToolCall(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["input"]?.jsonObject ?: JsonObject(emptyMap()))
        }
        return ChatItem.Assistant(text = text, toolCalls = calls, provider = id, raw = content)
    }

    private fun messages(history: List<ChatItem>) = buildJsonArray {
        for (item in history) when (item) {
            is ChatItem.User -> addJsonObject {
                put("role", "user")
                put("content", item.text)
            }
            is ChatItem.Assistant -> addJsonObject {
                put("role", "assistant")
                // Replay Claude's own blocks unchanged (thinking blocks must not be edited).
                if (item.provider == id && item.raw is JsonArray) {
                    put("content", item.raw)
                } else {
                    putJsonArray("content") {
                        if (item.text.isNotBlank()) addJsonObject { put("type", "text"); put("text", item.text) }
                        item.toolCalls.forEach { c ->
                            addJsonObject { put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", c.input) }
                        }
                    }
                }
            }
            is ChatItem.ToolResults -> addJsonObject {
                put("role", "user")
                // All results of one turn go back in a single message.
                putJsonArray("content") {
                    item.results.forEach { r ->
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", r.callId)
                            put("content", r.content)
                            if (r.isError) put("is_error", true)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        val SUGGESTED_MODELS = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5")
    }
}

internal fun errorMessage(response: HttpResponse): String {
    val detail = runCatching {
        Json.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
    return when (response.code) {
        401 -> "The API key was rejected. Check it in Settings."
        429 -> "Rate limit or quota reached on your AI account. Try again in a moment."
        else -> "AI request failed (${response.code})" + (detail?.let { ": $it" } ?: "")
    }
}
