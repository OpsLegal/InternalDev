package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConfirmationPolicy
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The TDA Assistant: sends the conversation to the user's AI provider, runs the tools
 * it asks for against the board, and loops until the model answers in plain text.
 */
class TdaAgent(
    private val provider: LlmProvider,
    private val store: BoardStore,
    private val state: AgentState = AgentState(),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val maxRounds: Int = 12,
) {
    private val tools = AgentTools(store, today, state)

    /**
     * Sends [userText] after [history] and returns the new items to append
     * (assistant turns and tool results, in order). [onItem] fires as each item arrives.
     * [spoken] tells the assistant the message was dictated and the answer will be read aloud.
     * Changes staged earlier and not confirmed are dropped: a new message replaces them.
     */
    suspend fun send(
        history: List<ChatItem>,
        userText: String,
        spoken: Boolean = false,
        onItem: suspend (ChatItem) -> Unit = {},
    ): List<ChatItem> {
        state.clear()
        val added = mutableListOf<ChatItem>(ChatItem.User(userText))
        onItem(added.first())
        repeat(maxRounds) {
            val system = systemPrompt(store.read(), today(), spoken)
            val reply = provider.complete(system, history + added, tools.specs)
            added += reply
            onItem(reply)
            if (reply.toolCalls.isEmpty()) return added
            val results = ChatItem.ToolResults(reply.toolCalls.map { tools.execute(it) })
            added += results
            onItem(results)
        }
        val stop = ChatItem.Assistant("I stopped after $maxRounds steps. Tell me how you want to continue.")
        added += stop
        onItem(stop)
        return added
    }

    /**
     * The user said yes: applies the staged changes without another AI call and returns
     * the items to append (the user's yes and a summary of what changed).
     */
    suspend fun confirm(userText: String = "Yes."): List<ChatItem> {
        val lines = tools.applyPending()
        val summary = if (lines.isEmpty()) "Nothing was waiting for confirmation." else lines.joinToString("\n")
        return listOf(ChatItem.User(userText), ChatItem.Assistant(summary, provider = "local"))
    }

    companion object {
        fun systemPrompt(board: Board, today: LocalDate, spoken: Boolean = false): String = buildString {
            appendLine(
                """
                You are the TDA Assistant, a planning assistant for a person with ADD (attention deficit disorder).
                Their whole method is a table: one line per day, five cells per day, one task per cell.
                A cell turns yellow when the task is done; a fully yellow line is a good day. Five tasks is the limit because
                more leads to unfinished days and a feeling of failure, and fewer helps them switch before boredom sets in.
                The table mixes personal and professional projects.

                You manage that table with your tools. The planner places cells for you and enforces the 5-per-day limit;
                you decide what the tasks are, how to split them into steps, their priority, deadlines and what they block.
                Always look at the table (get_table) before changing it. Never invent task or step ids.
                When the user reports an event (a new meeting, an email that needs an answer, a new project), turn it into tasks.
                When you learn a durable habit or preference, save it with remember.
                """.trimIndent(),
            )
            appendLine()
            val dow = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            appendLine("Today is $dow $today.")
            val workDays = board.settings.workDays.joinToString { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            appendLine("Days the planner fills: $workDays.")
            appendLine()
            appendLine("RULES, in priority order. A higher rule wins when two rules conflict:")
            board.rules.filter { it.enabled }.sortedBy { it.order }.forEachIndexed { i, rule ->
                appendLine("${i + 1}. ${rule.text}")
            }
            appendLine()
            val talk = board.conversation
            appendLine("UNDERSTANDING BEFORE ACTING:")
            if (talk.askWhenUnsure) {
                appendLine(
                    "- Make sure you understood the intention before changing anything. If something that matters is unclear or missing " +
                        "(which task, which day, a deadline, how many blocks it needs, what it blocks), ask ONE short question and wait. " +
                        "Do not guess. Do not ask about things that don't change the plan.",
                )
            }
            when (talk.confirmation) {
                ConfirmationPolicy.NEVER -> appendLine("- Changes you make with tools are applied immediately.")
                else -> appendLine(
                    "- Some changes are STAGED instead of applied (the tool result says so). After staging, repeat back in one or two " +
                        "short sentences what you understood and exactly what will change, then ask for confirmation. The app applies " +
                        "the staged changes when the user says yes. If the user answers anything else, the staged changes are dropped: " +
                        "take their correction into account and stage again.",
                )
            }
            if (spoken) {
                appendLine()
                appendLine(
                    "VOICE: the user is speaking and your answer will be read aloud. Answer in plain spoken sentences, at most three, " +
                        "no lists, no markdown, no ids. The transcript can contain recognition mistakes: when a name, date or number " +
                        "matters and sounds odd, check it with the user.",
                )
            }
            if (board.memory.isNotEmpty()) {
                appendLine()
                appendLine("WHAT YOU KNOW ABOUT THE USER:")
                board.memory.forEach { appendLine("- $it") }
            }
        }
    }
}
