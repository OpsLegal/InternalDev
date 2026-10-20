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
}
