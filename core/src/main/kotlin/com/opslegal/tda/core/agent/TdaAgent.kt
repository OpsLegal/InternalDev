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
    private val calendar: CalendarSource? = null,
    private val messages: MessageSource? = null,
    private val mail: MailSource? = null,
) {
    private val tools = AgentTools(store, today, state, calendar, messages, mail)

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
        /** The page the user asked from; the assistant focuses on it (Playbook and Settings: that page only). */
        page: AssistantPage? = null,
        /** Extra facts about that page the app knows (e.g. its settings). */
        pageFacts: String = "",
    ): List<ChatItem> {
        state.clear()
        val added = mutableListOf<ChatItem>(ChatItem.User(userText))
        onItem(added.first())
        repeat(maxRounds) {
            var system = systemPrompt(store.read(), today(), spoken, calendar != null, messages != null, mail != null)
            if (page != null) system += "\n\nCURRENT PAGE: " + page.prompt + (if (pageFacts.isNotBlank()) "\n$pageFacts" else "")
            val specs = page?.allowedTools?.let { allowed -> tools.specs.filter { it.name in allowed } } ?: tools.specs
            val reply = provider.complete(system, history + added, specs)
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
        fun systemPrompt(
            board: Board,
            today: LocalDate,
            spoken: Boolean = false,
            hasCalendar: Boolean = false,
            hasMessages: Boolean = false,
            hasMail: Boolean = false,
        ): String = buildString {
            appendLine(
                """
                You are the Docket 5 assistant, a planning assistant for legal professionals (lawyers, in-house counsel,
                legal operations, paralegals) who juggle many matters and personal obligations. Many of them get scattered
                easily or have ADD, so keep things simple and concrete.
                Their whole method is a table: one line per day, five cells per day, one task per cell.
                A cell turns yellow when the task is done; a fully yellow line is a good day. Five tasks is the limit because
                more leads to unfinished days and a feeling of failure, and fewer helps them switch before boredom sets in.
                The table mixes matters (files, clients, filings, meetings) and personal obligations. Think in legal terms:
                court and filing deadlines are hard deadlines; a step that waits for a client, the other party or the court
                needs waiting time before the next step.

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
            appendLine(
                "Every task has an explanation (description). Read it: it says what the title really means. Titles can be " +
                    "deliberately discreet. When you create a task, always write a clear explanation.",
            )
            appendLine(
                "TWO LEVELS ONLY, to keep things simple: a TASK is one cell (blue). Anything that needs several cells in an order is a " +
                    "PROJECT of steps (green); never make a task with sub-steps. Priority and deadline belong to projects. When the user " +
                    "describes something with several steps, agree on priority, deadline and steps, save_project, then add_task with that " +
                    "project and its steps (appended in order). A project that must be done before another one unlocks it (save_project " +
                    "blocks) and takes its importance. Mark meetings with kind=meeting and deliveries, filings or deadlines due that day with kind=deadline.",
            )
            appendLine(
                "VALUES AND EFFORT: the user's values (in get_table) say what matters to them; weigh them in every choice and " +
                    "name the value at stake when you explain a trade-off. Tag tasks and projects with the values they serve. " +
                    "Set each task's effort as it will feel to THIS user: lighter when it matches what is easy for them, heavier " +
                    "when it matches what they put off. The planner allows at most 2 heavy cells a day. For a heavy task, make the " +
                    "first step tiny and easy to start (e.g. \"open the file and list what's missing, 20 min\"), and when you plan a " +
                    "day, follow heavy work with something easy or enjoyable as a reward. When a task was pushed twice or more, " +
                    "ask ONE short question: is it heavy for them, and should you split it, make a smaller first step or hand it off? " +
                    "When a value is below its weekly minimum (BALANCE), suggest one concrete thing for it.",
            )
            appendLine(
                "LEARNING ABOUT THE USER: never run a questionnaire. When a message starts with \"ABOUT ME:\", turn what they said " +
                    "into set_value and set_about calls. Otherwise, when you notice something durable (what they enjoy, what they " +
                    "avoid, what matters), save it with set_about or set_value in the moment.",
            )
            appendLine(
                "MEETINGS: to find a time for someone, call find_slots, then draft_message with 2 or 3 of the times in the " +
                    "recipient's language. When the person accepts a time, book_meeting. Never book a time find_slots did not offer " +
                    "unless the user asks for it.",
            )
            appendLine(
                "When a message starts with \"For <day> <date>:\", the user tapped that day in the table: put what they describe " +
                    "on that day with add_task on_day, unless they say otherwise.",
            )
            if (hasCalendar) {
                appendLine("You can read the user's calendar with get_calendar. Check it before placing work on a day or when a request involves a date.")
            }
            if (hasMessages) {
                appendLine(
                    "You can read (never send) the user's messages from WhatsApp, SMS, Instagram, Messenger... with get_recent_chats and " +
                        "read_messages. Use them when a request is about a person, an answer they are waiting for, or what needs doing. " +
                        "Read only what the request needs, and never repeat private content that isn't relevant.",
                )
            }
            if (hasMail) {
                appendLine(
                    "You can read (never send) the user's work email with read_email. Use it when a request is about a client, a file, " +
                        "a document, an answer they are waiting for, or what an email asked. Read only what the request needs.",
                )
            }
            appendLine()
            appendLine("RULES, in priority order. A higher rule wins when two rules conflict:")
            board.rules.filter { it.enabled }.sortedBy { it.order }.forEachIndexed { i, rule ->
                appendLine("${i + 1}. ${rule.text}")
            }
            appendLine()
            val talk = board.conversation
            if (talk.languages.size > 1) {
                val names = talk.languages.joinToString { com.opslegal.tda.core.voice.LanguageGuess.displayName(it) }
                appendLine(
                    "LANGUAGES: the user speaks $names and may start any conversation in any of them. Always answer in the " +
                        "language of their latest message. Keep task titles and explanations in the language they were written in, " +
                        "and write new ones in the language the user is using.",
                )
                appendLine()
            }
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

/** Pages the assistant can be asked about. Playbook and Settings: it works on that page only. */
enum class AssistantPage(val title: String, val prompt: String, val allowedTools: Set<String>?, val suggestions: List<String>) {
    PROGRESS(
        "Progress",
        "The user is on the PROGRESS page, looking at each project's % done, planned end and deadline (in get_table: % done, ends=, " +
            "AT RISK). Focus on the projects: say plainly where they stand, which are at risk and why, and propose concrete adjustments " +
            "(push or cancel steps, move a deadline, reorder by importance, pause a project). Stage changes as usual and give the new end dates.",
        null,
        listOf("How am I doing?", "Which projects are at risk, and what do you suggest?", "Rebalance my projects for next week"),
    ),
    PLAYBOOK(
        "Playbook",
        "The user is on the PLAYBOOK page: their values (pillars, weight 1-3, weekly minimum), what is easy or hard for them, and the " +
            "assistant rules. Work ONLY on this page: values (set_value), the easy/hard lists (set_about) and rules (add_rule). Do not " +
            "change the table or projects here. One question at a time.",
        setOf("get_table", "set_value", "set_about", "add_rule", "remember"),
        listOf("Help me set my values", "What belongs in “easy for me” and “I tend to put off”?", "Suggest a rule from how I work"),
    ),
    SETTINGS(
        "Settings",
        "The user is on the SETTINGS page. Work ONLY on settings: explain options in plain words and recommend values for their situation. " +
            "You cannot change settings yourself, except meeting hours (set_meeting_hours) when they ask: otherwise say exactly which option to tap.",
        setOf("get_table", "set_meeting_hours"),
        listOf("Which update check times fit my day?", "Set up my meeting hours", "Explain the confirmation setting"),
    ),
}

