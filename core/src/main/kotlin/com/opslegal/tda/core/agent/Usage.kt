package com.opslegal.tda.core.agent

import kotlinx.serialization.Serializable

/** Tokens one answer used, as the AI provider reported them. */
@Serializable
data class Usage(val input: Long = 0, val output: Long = 0, val cacheWrite: Long = 0, val cacheRead: Long = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cacheWrite + o.cacheWrite, cacheRead + o.cacheRead)
    val total: Long get() = input + output + cacheWrite + cacheRead
}

/** One month of use: per feature ("chat", "prepare", "checks"…), the tokens and an estimated cost in US dollars. */
@Serializable
data class UsageLine(val tokens: Usage = Usage(), val usd: Double = 0.0, val calls: Int = 0)

@Serializable
data class UsageLedger(
    /** "2026-10" → feature → line. Only the last 6 months are kept. */
    val months: Map<String, Map<String, UsageLine>> = emptyMap(),
    /** The user's monthly budget (USD); null = none. At 100%, automatic checks pause; what the user asks still works. */
    val budget: Double? = null,
) {
    fun month(m: String): Map<String, UsageLine> = months[m].orEmpty()
    fun usd(m: String): Double = month(m).values.sumOf { it.usd }
    fun tokens(m: String): Long = month(m).values.sumOf { it.tokens.total }
    fun overBudget(m: String): Boolean = budget != null && usd(m) >= budget
    fun nearBudget(m: String): Boolean = budget != null && usd(m) >= budget * 0.8

    fun add(m: String, feature: String, u: Usage, usd: Double): UsageLedger {
        val cur = month(m)[feature] ?: UsageLine()
        val next = month(m) + (feature to UsageLine(cur.tokens + u, cur.usd + usd, cur.calls + 1))
        return copy(months = (months + (m to next)).toSortedMap().entries.toList().takeLast(6).associate { it.key to it.value })
    }
}

/**
 * Estimated prices (US dollars per million tokens) for the cost line. The provider's own bill is the reference: this
 * is labelled "about". Cache writes cost 1.25× the input price; reads use each model's cache-read price.
 */
object Pricing {
    private data class P(val input: Double, val output: Double, val cacheRead: Double)

    private val TABLE = listOf(
        "claude-fable-5" to P(10.0, 50.0, 0.25), "claude-mythos-5" to P(10.0, 50.0, 0.25),
        "claude-opus-5-5" to P(4.0, 20.0, 0.20), "claude-opus-5" to P(5.0, 25.0, 0.50), "claude-opus-4" to P(5.0, 25.0, 0.50),
        "claude-sonnet-5" to P(2.0, 10.0, 0.20), "claude-sonnet-4" to P(3.0, 15.0, 0.30),
        "claude-haiku-5-5" to P(0.10, 0.50, 0.01), "claude-haiku-4" to P(1.0, 5.0, 0.10),
    )

    /** Null when the model's price is unknown (other providers): then only tokens are shown. */
    fun usd(model: String, u: Usage): Double? {
        val p = TABLE.firstOrNull { model.startsWith(it.first) }?.second ?: return null
        return (u.input * p.input + u.output * p.output + u.cacheWrite * p.input * 1.25 + u.cacheRead * p.cacheRead) / 1_000_000.0
    }

    /** The lighter model used for automatic checks when the user keeps "lighter checks" on (Claude only). */
    const val LIGHT_MODEL = "claude-haiku-5-5"

    val FEATURES = linkedMapOf("chat" to "Chat", "prepare" to "✨ Prepare", "checks" to "Automatic checks", "looks" to "30 / 60 / 90-day looks", "read" to "Reading receipts and documents", "other" to "Other")
}

/** Wraps a provider: every answer's tokens go to [onUsage] with the feature that asked. */
class MeteredProvider(private val inner: LlmProvider, private val model: String, private val feature: String, private val onUsage: (String, String, Usage) -> Unit) : LlmProvider {
    override val id: String get() = inner.id
    override suspend fun complete(system: String, history: List<ChatItem>, tools: List<ToolSpec>): ChatItem.Assistant {
        val a = inner.complete(system, history, tools)
        a.usage?.let { runCatching { onUsage(feature, model, it) } }
        return a
    }
}
