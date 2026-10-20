package com.opslegal.tda.core

import com.opslegal.tda.core.agent.AnthropicProvider
import com.opslegal.tda.core.agent.ChatItem
import com.opslegal.tda.core.agent.HttpResponse
import com.opslegal.tda.core.agent.MeteredProvider
import com.opslegal.tda.core.agent.Pricing
import com.opslegal.tda.core.agent.Usage
import com.opslegal.tda.core.agent.UsageLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the AI used is counted from the provider's own report, per feature and month; the budget pauses only automatic checks. */
class UsageTest {
    @Test
    fun theTokensComeFromTheAnswerAndTheFixedPartIsCached() = runBlocking {
        var sent = ""
        val reply = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":120,"output_tokens":40,"cache_creation_input_tokens":3000,"cache_read_input_tokens":0}}"""
        val p = AnthropicProvider("k", "claude-opus-5-5", { _, _, body -> sent = body; HttpResponse(200, reply) })
        var got: Triple<String, String, Usage>? = null
        MeteredProvider(p, "claude-opus-5-5", "prepare") { f, m, u -> got = Triple(f, m, u) }.complete("SYSTEM", listOf(ChatItem.User("hi")), emptyList())
        assertEquals(Triple("prepare", "claude-opus-5-5", Usage(120, 40, 3000, 0)), got)
        val body = Json.parseToJsonElement(sent).jsonObject
        assertTrue(body["system"].toString().contains("cache_control"), "the instructions are cached")
        assertNotNull(body["cache_control"])
    }

    @Test
    fun aMonthAddsUpAndTheBudgetIsWatched() {
        val usd = Pricing.usd("claude-opus-5-5", Usage(1_000_000, 100_000, 0, 0))!!
        assertEquals(6.0, usd, 0.001)
        assertNull(Pricing.usd("gpt-5", Usage(10, 10)), "unknown price: tokens only")
        var l = UsageLedger(budget = 10.0).add("2026-10", "chat", Usage(1000, 100), 8.5)
        assertTrue(l.nearBudget("2026-10")); assertTrue(!l.overBudget("2026-10"))
        l = l.add("2026-10", "checks", Usage(500, 50), 2.0)
        assertTrue(l.overBudget("2026-10")); assertEquals(2, l.month("2026-10").size); assertEquals(10.5, l.usd("2026-10"), 0.001)
    }

    @Test
    fun routineWorkThinksLessAndTheChatSendsOnlyItsRecentPart() = runBlocking {
        var sent = ""
        val ok = """{"content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        AnthropicProvider("k", "claude-haiku-5-5", { _, _, b -> sent = b; HttpResponse(200, ok) }, effort = "low").complete("S", listOf(ChatItem.User("x")), emptyList())
        assertTrue(sent.contains("\"effort\":\"low\""))
        AnthropicProvider("k", "claude-haiku-4-5", { _, _, b -> sent = b; HttpResponse(200, ok) }, effort = "low").complete("S", listOf(ChatItem.User("x")), emptyList())
        assertTrue(!sent.contains("effort"), "older models reject effort")
        val long = (1..40).flatMap { listOf(ChatItem.User("q$it"), ChatItem.Assistant("a$it")) }
        val recent = com.opslegal.tda.core.agent.TdaAgent.recent(long)
        assertTrue(recent.size <= com.opslegal.tda.core.agent.TdaAgent.HISTORY && recent.first() is ChatItem.User)
    }

    @Test
    fun theChecksFixedRulesAreTheCachedSystemPart() = runBlocking {
        var sent = ""
        val ok = """{"content":[{"type":"text","text":"{\"updates\":[]}"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
        val p = AnthropicProvider("k", "claude-haiku-5-5", { _, _, b -> sent = b; HttpResponse(200, ok) })
        com.opslegal.tda.core.agent.UpdateCheck.run(p, com.opslegal.tda.core.model.Board(), listOf(com.opslegal.tda.core.model.Incoming("i", "outlook", "A", "Hello", "2026-10-20T09:00")), java.time.LocalDate.parse("2026-10-20"), "2026-10-20T09:00")
        val body = Json.parseToJsonElement(sent).jsonObject
        assertTrue(body["system"].toString().contains("UPDATE, NEVER DUPLICATE"), "rules in the system part")
        assertTrue(!body["system"].toString().contains("2026-10-20"), "no date in the cached part")
    }
}
