package com.opslegal.tda.core

import com.opslegal.tda.core.agent.AnthropicProvider
import com.opslegal.tda.core.agent.BoardStore
import com.opslegal.tda.core.agent.ChatItem
import com.opslegal.tda.core.agent.HttpResponse
import com.opslegal.tda.core.agent.LlmProvider
import com.opslegal.tda.core.agent.OpenAiProvider
import com.opslegal.tda.core.agent.TdaAgent
import com.opslegal.tda.core.agent.ToolCall
import com.opslegal.tda.core.agent.ToolSpec
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.model.DefaultRules
import com.opslegal.tda.core.agent.AgentState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTest {
    private val today = LocalDate.parse("2026-09-21")

    private class MemoryStore(var board: Board) : BoardStore {
        override suspend fun read() = board
        override suspend fun update(change: (Board) -> Board) = change(board).also { board = it }
    }

    /** Replays scripted turns and records the system prompts it received. */
    private class ScriptedProvider(private val turns: MutableList<ChatItem.Assistant>) : LlmProvider {
        override val id = "fake"
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, history: List<ChatItem>, tools: List<ToolSpec>): ChatItem.Assistant {
            systems += system
            return turns.removeAt(0)
        }
    }

    @Test
    fun agentAddsTaskThroughTools() = runTest {
        val store = MemoryStore(Board(rules = DefaultRules.all, conversation = ConversationSettings(confirmation = ConfirmationPolicy.NEVER)))
        val input = buildJsonObject {
            put("title", "Meeting with ACME")
            put("fixed_date", "2026-09-23")
            put("project", "OpsLegal")
        }
        val provider = ScriptedProvider(mutableListOf(
            ChatItem.Assistant("", listOf(ToolCall("c1", "add_task", input))),
            ChatItem.Assistant("Added the meeting on Wednesday."),
        ))
        val items = TdaAgent(provider, store, today = { today }).send(emptyList(), "Add a meeting with ACME on Wednesday")

        assertEquals(4, items.size)
        val result = (items[2] as ChatItem.ToolResults).results.single()
        assertTrue(!result.isError, result.content)
        assertEquals("2026-09-23", store.board.tasks.single().steps.single().date)
        assertTrue(provider.systems.first().contains("1. Never put more than 5 tasks"))
    }

    @Test
    fun toolErrorsAreReportedNotThrown() = runTest {
        val store = MemoryStore(Board())
        val provider = ScriptedProvider(mutableListOf(
            ChatItem.Assistant("", listOf(ToolCall("c1", "set_step_done", buildJsonObject { put("step_id", "nope"); put("done", true) }))),
            ChatItem.Assistant("Sorry."),
        ))
        val items = TdaAgent(provider, store, today = { today }).send(emptyList(), "done")
        assertTrue((items[2] as ChatItem.ToolResults).results.single().isError)
    }

    @Test
    fun anthropicRequestShape() = runTest {
        var sentBody = ""
        var sentHeaders = emptyMap<String, String>()
        val reply = """{"content":[{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":"Hi"},
            {"type":"tool_use","id":"tu1","name":"get_table","input":{"days":7}}],"stop_reason":"tool_use"}"""
        val provider = AnthropicProvider("key", http = { _, h, body -> sentHeaders = h; sentBody = body; HttpResponse(200, reply) })
        val turn = provider.complete("sys", listOf(ChatItem.User("hello")), emptyList())
        assertEquals("Hi", turn.text)
        assertEquals("get_table", turn.toolCalls.single().name)
        assertEquals("key", sentHeaders["x-api-key"])
        assertEquals("claude-opus-5", Json.parseToJsonElement(sentBody).jsonObject["model"]!!.jsonPrimitive.content)

        // The next request replays the assistant blocks unchanged, thinking block included.
        provider.complete("sys", listOf(ChatItem.User("hello"), turn), emptyList())
        val messages = Json.parseToJsonElement(sentBody).jsonObject["messages"]!!.jsonArray
        val replayed = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", replayed[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiToolCallsParsed() = runTest {
        val reply = """{"choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"remember","arguments":"{\"note\":\"Prefers mornings\"}"}}]}}]}"""
        var url = ""
        val provider = OpenAiProvider("key", http = { u, _, _ -> url = u; HttpResponse(200, reply) })
        val turn = provider.complete("sys", listOf(ChatItem.User("hi")), emptyList())
        assertEquals("https://api.openai.com/v1/chat/completions", url)
        assertEquals("Prefers mornings", turn.toolCalls.single().input["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatHistorySerializes() {
        val items: List<ChatItem> = listOf(ChatItem.User("a"), ChatItem.Assistant("b", raw = buildJsonObject { putJsonArray("x") { add(1) } }))
        val json = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatItem.serializer()), items)
        assertEquals(items, Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChatItem.serializer()), json))
    }

    @Test
    fun changesWaitForConfirmation() = runTest {
        val store = MemoryStore(Board(rules = DefaultRules.all))
        val state = AgentState()
        val provider = ScriptedProvider(mutableListOf(
            ChatItem.Assistant("", listOf(ToolCall("c1", "add_task", buildJsonObject { put("title", "Call notary"); put("fixed_date", "2026-09-22") }))),
            ChatItem.Assistant("You want to call the notary on Tuesday. Shall I add it?"),
        ))
        val agent = TdaAgent(provider, store, state, today = { today })
        val items = agent.send(emptyList(), "Remind me to call the notary on Tuesday", spoken = true)

        assertTrue((items[2] as ChatItem.ToolResults).results.single().content.startsWith("STAGED"))
        assertTrue(store.board.tasks.isEmpty(), "nothing changes before the yes")
        assertEquals("add \"Call notary\" on 2026-09-22", state.pending.value.single().summary)
        assertTrue(provider.systems.first().contains("VOICE:"))

        val confirmed = agent.confirm("Yes.")
        assertEquals("2026-09-22", store.board.tasks.single().steps.single().date)
        assertTrue(state.pending.value.isEmpty())
        assertTrue((confirmed.last() as ChatItem.Assistant).text.contains("Call notary"))
    }

    @Test
    fun importantPolicyLetsSmallChangesThrough() = runTest {
        var board = Board(conversation = ConversationSettings(confirmation = ConfirmationPolicy.IMPORTANT))
        board = com.opslegal.tda.core.plan.BoardOps.addTask(board, com.opslegal.tda.core.plan.BoardOps.NewTask("Garage"), today).first
        val store = MemoryStore(board)
        val stepId = board.tasks.single().steps.single().id
        val state = AgentState()
        val provider = ScriptedProvider(mutableListOf(
            ChatItem.Assistant("", listOf(
                ToolCall("c1", "set_step_done", buildJsonObject { put("step_id", stepId); put("done", true) }),
                ToolCall("c2", "delete_task", buildJsonObject { put("task_id", board.tasks.single().id) }),
            )),
            ChatItem.Assistant("Marked done. Delete the garage task too?"),
        ))
        TdaAgent(provider, store, state, today = { today }).send(emptyList(), "garage is done, remove it")
        assertTrue(store.board.tasks.single().steps.single().done)
        assertEquals(listOf("delete_task"), state.pending.value.map { it.tool })
    }

    @Test
    fun newMessageDropsUnconfirmedChanges() = runTest {
        val store = MemoryStore(Board())
        val state = AgentState()
        state.stage(com.opslegal.tda.core.agent.PendingAction("add_rule", buildJsonObject { put("text", "x") }, "add the rule x"))
        val provider = ScriptedProvider(mutableListOf(ChatItem.Assistant("OK.")))
        TdaAgent(provider, store, state, today = { today }).send(emptyList(), "No, forget it")
        assertTrue(state.pending.value.isEmpty())
    }
}
