package com.opslegal.tda.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions with function calling. Also works with any service that
 * exposes an OpenAI-compatible endpoint (Mistral, OpenRouter, a local server...)
 * by changing [baseUrl].
 */
class OpenAiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: HttpTransport,
    private val baseUrl: String = "https://api.openai.com/v1",
    override val id: String = "openai",
) : LlmProvider {

    override suspend fun complete(system: String, history: List<ChatItem>, tools: List<ToolSpec>): ChatItem.Assistant {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                for (item in history) when (item) {
                    is ChatItem.User -> addJsonObject { put("role", "user"); put("content", item.text) }
                    is ChatItem.Assistant -> addJsonObject {
                        put("role", "assistant")
                        if (item.text.isBlank()) put("content", JsonNull) else put("content", item.text)
                        if (item.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                            item.toolCalls.forEach { c ->
                                addJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", c.name)
                                        put("arguments", c.input.toString())
                                    }
                                }
                            }
                        }
                    }
                    is ChatItem.ToolResults -> item.results.forEach { r ->
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.callId)
                            put("content", if (r.isError) "ERROR: ${r.content}" else r.content)
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.inputSchema)
                        }
                    }
                }
            }
        }
        val response = http.postJson(
            "${baseUrl.trimEnd('/')}/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body.toString(),
        )
        if (response.code !in 200..299) throw LlmException(errorMessage(response), response.code)

        val message = Json.parseToJsonElement(response.body).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject
        val text = message["content"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content.orEmpty()
        val calls = message["tool_calls"]?.takeIf { it != JsonNull }?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            val fn = o["function"]!!.jsonObject
            val args = fn["arguments"]?.jsonPrimitive?.content.orEmpty()
            val input = runCatching { Json.parseToJsonElement(args).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            ToolCall(o["id"]!!.jsonPrimitive.content, fn["name"]!!.jsonPrimitive.content, input)
        }
        return ChatItem.Assistant(text = text, toolCalls = calls, provider = id)
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-5"
    }
}
