package com.opslegal.tda.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A tool the assistant can call, described with a JSON schema for its input. */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

@Serializable
data class ToolCall(val id: String, val name: String, val input: JsonObject)

@Serializable
data class ToolResult(val callId: String, val content: String, val isError: Boolean = false)

/** Provider-neutral conversation history. Persisted so a chat survives app restarts. */
@Serializable
sealed class ChatItem {
    @Serializable
    @SerialName("user")
    data class User(val text: String) : ChatItem()

    /**
     * A model turn. [raw] keeps the provider's own content blocks (including thinking
     * blocks) so they can be sent back unchanged on the next request.
     */
    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val provider: String = "",
        val raw: JsonElement? = null,
    ) : ChatItem()

    @Serializable
    @SerialName("tool_results")
    data class ToolResults(val results: List<ToolResult>) : ChatItem()
}

class LlmException(message: String, val statusCode: Int? = null) : Exception(message)

/** One request/response round with a model. Implementations must not loop. */
interface LlmProvider {
    val id: String

    suspend fun complete(system: String, history: List<ChatItem>, tools: List<ToolSpec>): ChatItem.Assistant
}

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP seam so the core stays free of platform HTTP clients (OkHttp on Android). */
fun interface HttpTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse
}
