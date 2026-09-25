package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.RescheduleOption
import com.opslegal.tda.core.plan.Rescheduler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/** Where the board lives. The app implements it with a JSON file. */
interface BoardStore {
    suspend fun read(): Board
    suspend fun update(change: (Board) -> Board): Board
}

/**
 * The tools the assistant uses to read and change the 5-column table. Every change
 * goes through the deterministic [Planner], so the model decides *what* to do and
 * the planner guarantees the 5-per-day and one-step-per-day constraints.
 */
class AgentTools(
    private val store: BoardStore,
    private val today: () -> LocalDate,
    private val state: AgentState = AgentState(),
    private val calendar: CalendarSource? = null,
    private val messages: MessageSource? = null,
) {
    private var pendingOptions: List<RescheduleOption>
        get() = state.options
        set(value) { state.options = value }

    val specs: List<ToolSpec> = listOf(
        spec("get_table", "Show the 5-column table: one line per day, five cells per day, [x] = done. Also lists open tasks with their ids.") {
            prop("from", "string", "First day, ISO date. Defaults to today.")
            prop("days", "integer", "Number of days to show. Defaults to 14.")
        },
        spec(
            "add_task",
            "Add a task. Split work that needs more than one focused block of a few hours into steps; each step takes one cell on a different day. " +
                "The planner places the steps automatically. If the task does not fit before its deadline, nothing is moved and ranked options are returned instead.",
        ) {
            prop("title", "string", "Short title that fits in a table cell.")
            prop("description", "string", "The explanation: what it is, why it matters, useful context. Always fill it in.")
            prop("project", "string", "Project or life area, e.g. Personal, Refinancing.")
            enumProp("priority", Priority.entries.map { it.name }, "Own priority. Blockers inherit the priority of what they block automatically.")
            prop("deadline", "string", "Hard deadline, ISO date.")
            prop("fixed_date", "string", "For meetings/appointments: the only day it can happen, ISO date.")
            prop("on_day", "string", "Put the first step on this day (ISO date), e.g. the day the user tapped. Unlike fixed_date it can be pushed later.")
            arrayProp("steps", "Step titles in order. Omit for a single-cell task.")
            arrayProp("blocks", "Ids of existing tasks that cannot be completed until this one is done.")
            prop("impact_note", "string", "Why delaying this matters (money, other projects).")
            prop("min_days_between_steps", "integer", "Waiting time between steps, e.g. 3 when an answer is needed. Default 1.")
            required("title", "description")
        },
        spec(
            "update_task",
            "Change a task's details. Existing cells stay where they are. The user may have edited titles and explanations " +
                "themselves: keep their wording unless they ask you to change it.",
        ) {
            prop("task_id", "string", "Task id.")
            prop("title", "string", "")
            prop("description", "string", "The explanation.")
            prop("project", "string", "")
            enumProp("priority", Priority.entries.map { it.name }, "")
            prop("deadline", "string", "ISO date, or empty string to remove.")
            arrayProp("blocks", "Replaces the list of task ids this task blocks.")
            prop("impact_note", "string", "")
            required("task_id")
        },
        spec("add_steps", "Append steps to an existing task and plan them.") {
            prop("task_id", "string", "Task id.")
            arrayProp("steps", "Step titles.")
            required("task_id", "steps")
        },
        spec(
            "set_step_status",
            "Change a cell: done (yellow), todo, pushed (grey here, the work moves to a later day) or cancelled (grey, will not be done).",
        ) {
            prop("step_id", "string", "Step id.")
            enumProp("status", listOf("done", "todo", "pushed", "cancelled"), "")
            required("step_id", "status")
        },
        spec("rename_step", "Change the text of one cell.") {
            prop("step_id", "string", "Step id.")
            prop("title", "string", "New text.")
            required("step_id", "title")
        },
        spec("move_step", "Move one cell to a specific day and pin it there. Only do this when the user asked for that day.") {
            prop("step_id", "string", "Step id.")
            prop("date", "string", "ISO date.")
            required("step_id", "date")
        },
        spec("delete_task", "Delete a task and all its cells. Ask the user first.") {
            prop("task_id", "string", "Task id.")
            required("task_id")
        },
        spec(
            "propose_options",
            "Compute ranked ways to fit an existing task before its deadline (e.g. it just became urgent). Returns options; nothing changes until apply_option.",
        ) {
            prop("task_id", "string", "Task id.")
            required("task_id")
        },
        spec("apply_option", "Apply one of the options returned by the last add_task or propose_options call. Only after the user chose it.") {
            prop("option_id", "string", "Option id.")
            required("option_id")
        },
        spec("remember", "Save a durable fact about the user's habits or preferences to improve future planning.") {
            prop("note", "string", "One short sentence.")
            required("note")
        },
        spec("add_rule", "Add an assistant rule the user asked for. It goes to the bottom of the priority list.") {
            prop("text", "string", "The rule.")
            required("text")
        },
    ) + listOfNotNull(
        calendar?.let {
            spec(
                "get_calendar",
                "Read the user's phone calendar (Outlook, Google, Samsung... accounts synced on the phone): meetings and " +
                    "appointments. Check it before planning a day so tasks don't clash with meetings or overload busy days.",
            ) {
                prop("from", "string", "First day, ISO date. Defaults to today.")
                prop("days", "integer", "Number of days. Defaults to 7, at most 31.")
            }
        },
        messages?.let {
            spec(
                "get_recent_chats",
                "List the user's recent conversations across WhatsApp, SMS, Messenger, Instagram, Signal... (via Beeper), " +
                    "with the last message and unread count. Read only.",
            ) {
                prop("limit", "integer", "How many chats. Default 20, at most 50.")
                prop("unread_only", "boolean", "Only chats with unread messages.")
            }
        },
        messages?.let {
            spec(
                "read_messages",
                "Read messages: search all conversations for words (e.g. a name, 'invoice', 'Tuesday'), or read the latest " +
                    "messages of one chat by its id from get_recent_chats. Read only; you can never send messages. " +
                    "Only read what the current request needs.",
            ) {
                prop("query", "string", "Words to search for.")
                prop("chat_id", "string", "A chat id from get_recent_chats.")
                prop("limit", "integer", "How many messages. Default 20, at most 50.")
            }
        },
    )

    suspend fun execute(call: ToolCall): ToolResult = try {
        val board = store.read()
        if (needsConfirmation(call.name, call.input, board.conversation.confirmation)) {
            val action = PendingAction(call.name, call.input, describeAction(board, call.name, call.input))
            state.stage(action)
            ToolResult(
                call.id,
                "STAGED, not applied yet: ${action.summary}. Stage anything else this request needs, then stop calling tools, " +
                    "tell the user in one or two short sentences what you understood and what will change, and ask them to confirm.",
            )
        } else {
            ToolResult(call.id, run(call.name, call.input))
        }
    } catch (e: Exception) {
        ToolResult(call.id, e.message ?: e.toString(), isError = true)
    }

    /**
     * Runs the staged changes after the user said yes. Returns one line per change,
     * worded for the user.
     */
    suspend fun applyPending(): List<String> {
        val actions = state.pending.value
        state.clear()
        return actions.map { action ->
            try {
                val result = run(action.tool, action.input)
                if (result.length < 40) "${action.summary}: done." else result
            } catch (e: Exception) {
                "${action.summary}: failed (${e.message})."
            }
        }
    }

    private fun needsConfirmation(tool: String, input: JsonObject, policy: ConfirmationPolicy): Boolean = when (policy) {
        ConfirmationPolicy.NEVER -> false
        ConfirmationPolicy.ALWAYS -> tool in WRITE_TOOLS
        // Pushing or cancelling moves work around; marking done or to do is visible at once.
        ConfirmationPolicy.IMPORTANT -> tool in IMPORTANT_TOOLS ||
            (tool == "set_step_status" && input.str("status") in setOf("pushed", "cancelled"))
    }

    private fun describeAction(board: Board, tool: String, input: JsonObject): String {
        // Unknown ids fail now, so the assistant can correct itself before asking for a yes.
        fun taskTitle(id: String?) = board.tasks.firstOrNull { it.id == id }?.title ?: error("Unknown task $id")
        fun stepTitle(id: String?) = id?.let { BoardOps.findStep(board, it) }?.let { (t, s) ->
            if (t.steps.size > 1) "${t.title} · ${s.title}" else t.title
        } ?: error("Unknown step $id")
        return when (tool) {
            "add_task" -> buildString {
                append("add \"${input.str("title")}\"")
                val steps = input.list("steps").size
                if (steps > 1) append(" in $steps steps")
                (input.str("fixed_date") ?: input.str("on_day"))?.let { append(" on $it") }
                input.str("deadline")?.let { append(", due $it") }
                input.str("priority")?.let { append(", ${it.lowercase()} priority") }
            }
            "update_task" -> "change \"${taskTitle(input.str("task_id"))}\" (" +
                input.keys.filter { it != "task_id" }.joinToString { k -> "$k: ${input[k]}" } + ")"
            "add_steps" -> "add ${input.list("steps").size} step(s) to \"${taskTitle(input.str("task_id"))}\""
            "set_step_status" -> {
                val cell = stepTitle(input.str("step_id"))
                when (input.str("status")) {
                    "done" -> "mark \"$cell\" done"
                    "pushed" -> "push \"$cell\" to a later day"
                    "cancelled" -> "cancel \"$cell\""
                    else -> "mark \"$cell\" as to do"
                }
            }
            "rename_step" -> "rename \"${stepTitle(input.str("step_id"))}\" to \"${input.str("title")}\""
            "move_step" -> "move \"${stepTitle(input.str("step_id"))}\" to ${input.str("date")}"
            "delete_task" -> "delete \"${taskTitle(input.str("task_id"))}\" and all its cells"
            "apply_option" -> "apply: " + (pendingOptions.firstOrNull { it.id == input.str("option_id") }?.title
                ?: error("Unknown option. Call propose_options again."))
            "add_rule" -> "add the rule \"${input.str("text")}\""
            else -> tool
        }
    }

    private suspend fun run(name: String, input: JsonObject): String {
        val day = today()
        return when (name) {
            "get_table" -> {
                val board = store.read()
                val from = input.str("from")?.let(LocalDate::parse) ?: day
                describe(board, from, input.int("days") ?: 14)
            }
            "add_task" -> {
                var added: Task? = null
                val spec = BoardOps.NewTask(
                    title = input.str("title") ?: error("title is required"),
                    description = input.str("description").orEmpty(),
                    project = input.str("project").orEmpty(),
                    priority = input.str("priority")?.let { Priority.valueOf(it) } ?: Priority.NORMAL,
                    deadline = input.str("deadline")?.ifBlank { null },
                    fixedDate = input.str("fixed_date")?.ifBlank { null },
                    stepTitles = input.list("steps"),
                    blocks = input.list("blocks"),
                    impactNote = input.str("impact_note").orEmpty(),
                    minDaysBetweenSteps = input.int("min_days_between_steps") ?: 1,
                )
                val onDay = input.str("on_day")?.ifBlank { null }?.let(LocalDate::parse)
                if (onDay != null) {
                    var placed = false
                    val board = store.update { b ->
                        val (next, task, onThatDay) = BoardOps.addTaskOn(b, spec, onDay, day)
                        added = task
                        placed = onThatDay
                        Planner.plan(next, day).board
                    }
                    val task = board.tasks.first { it.id == added!!.id }
                    val cells = task.steps.joinToString("; ") { "${it.title} on ${it.date ?: "not placed"} (step ${it.id})" }
                    return if (placed) "Added \"${task.title}\" (id ${task.id}). Cells: $cells"
                    else "$onDay already has 5 open tasks, so \"${task.title}\" (id ${task.id}) went to the next free day. Cells: $cells. " +
                        "Tell the user, and offer to push or cancel something on $onDay if it must happen that day."
                }
                val board = store.update { b -> BoardOps.addTask(b, spec, day).also { added = it.second }.first }
                placeOrPropose(board, added!!.id, day)
            }
            "update_task" -> {
                val id = input.str("task_id")!!
                store.update { b ->
                    require(b.tasks.any { it.id == id }) { "Unknown task $id" }
                    BoardOps.updateTask(b, id) { t ->
                        t.copy(
                            title = input.str("title") ?: t.title,
                            description = input.str("description") ?: t.description,
                            project = input.str("project") ?: t.project,
                            priority = input.str("priority")?.let { Priority.valueOf(it) } ?: t.priority,
                            deadline = if ("deadline" in input) input.str("deadline")?.ifBlank { null }?.also { LocalDate.parse(it) } else t.deadline,
                            blocks = if ("blocks" in input) input.list("blocks") else t.blocks,
                            impactNote = input.str("impact_note") ?: t.impactNote,
                        )
                    }
                }
                "Updated."
            }
            "add_steps" -> {
                val id = input.str("task_id")!!
                val board = store.update { b ->
                    require(b.tasks.any { it.id == id }) { "Unknown task $id" }
                    BoardOps.updateTask(b, id) { t ->
                        t.copy(steps = t.steps + input.list("steps").map { Step(BoardOps.newId(), it) })
                    }
                }
                placeOrPropose(board, id, day)
            }
            "set_step_status" -> {
                val stepId = input.str("step_id")!!
                val status = input.str("status")
                val board = store.update { b ->
                    requireNotNull(BoardOps.findStep(b, stepId)) { "Unknown step $stepId" }
                    when (status) {
                        "done" -> BoardOps.setStepDone(b, stepId, true)
                        "todo" -> BoardOps.reopenStep(b, stepId)
                        "pushed" -> Planner.plan(BoardOps.pushStep(b, stepId, day), day).board
                        "cancelled" -> BoardOps.cancelStep(b, stepId, day)
                        else -> error("status must be done, todo, pushed or cancelled")
                    }
                }
                if (status == "pushed") {
                    val (task, _) = BoardOps.findStep(board, stepId)!!
                    val next = task.steps.filter { !it.closed && it.date != null }.minByOrNull { it.date!! }
                    "Pushed." + (next?.let { " The work is now on ${it.date}." } ?: "")
                } else {
                    "Done."
                }
            }
            "rename_step" -> {
                val stepId = input.str("step_id")!!
                store.update { b ->
                    requireNotNull(BoardOps.findStep(b, stepId)) { "Unknown step $stepId" }
                    BoardOps.renameStep(b, stepId, input.str("title")!!)
                }
                "Renamed."
            }
            "get_recent_chats" -> {
                val source = messages ?: error("Messages are not connected. Ask the user to allow it in Settings.")
                val chats = source.recentChats((input.int("limit") ?: 20).coerceIn(1, 50), input.bool("unread_only") == true)
                if (chats.isEmpty()) "No chats found."
                else chats.joinToString("\n") { c ->
                    "- ${c.title} [${c.network}] ${c.lastActivity}" + (if (c.unread > 0) " (${c.unread} unread)" else "") +
                        ": ${c.lastMessage.take(160)} (chat_id ${c.id})"
                }
            }
            "read_messages" -> {
                val source = messages ?: error("Messages are not connected. Ask the user to allow it in Settings.")
                val query = input.str("query")?.ifBlank { null }
                val chatId = input.str("chat_id")?.ifBlank { null }
                require(query != null || chatId != null) { "Give a query or a chat_id." }
                val found = source.messages(query, chatId, (input.int("limit") ?: 20).coerceIn(1, 50))
                if (found.isEmpty()) "No messages found."
                else found.joinToString("\n") { m ->
                    (if (m.isMatch) "» " else "  ") + "${m.time} ${m.chat} · ${if (m.fromMe) "me" else m.sender}: ${m.text.take(500)}"
                }
            }
            "get_calendar" -> {
                val source = calendar ?: error("The calendar is not connected. Ask the user to allow it in Settings.")
                val from = input.str("from")?.let(LocalDate::parse) ?: day
                val days = (input.int("days") ?: 7).coerceIn(1, 31)
                val events = source.events(from, from.plusDays(days.toLong() - 1))
                if (events.isEmpty()) "No calendar events from $from for $days days."
                else events.joinToString("\n") { e -> e.describe() }
            }
            "move_step" -> {
                val stepId = input.str("step_id")!!
                val date = LocalDate.parse(input.str("date")!!)
                store.update { b ->
                    requireNotNull(BoardOps.findStep(b, stepId)) { "Unknown step $stepId" }
                    BoardOps.moveStep(b, stepId, date) ?: error("$date already has 5 tasks. Propose options instead.")
                }
                "Moved to $date."
            }
            "delete_task" -> {
                val id = input.str("task_id")!!
                store.update { b -> BoardOps.deleteTask(b, id) }
                "Deleted."
            }
            "propose_options" -> {
                val id = input.str("task_id")!!
                val board = store.read()
                val task = board.tasks.firstOrNull { it.id == id } ?: error("Unknown task $id")
                // Consider the task's open cells as movable so they can be brought forward.
                val loose = BoardOps.updateTask(board, id) { t ->
                    t.copy(steps = t.steps.map { if (it.closed || it.pinned) it else it.copy(date = null, slot = null) })
                }
                pendingOptions = Rescheduler.options(loose, task.id, day)
                describeOptions(pendingOptions)
            }
            "apply_option" -> {
                val option = pendingOptions.firstOrNull { it.id == input.str("option_id") }
                    ?: error("Unknown option. Call propose_options again.")
                store.update { current ->
                    // Keep changes made since the proposal (e.g. cells ticked) that the option did not touch.
                    val proposed = option.board.tasks.associateBy { it.id }
                    current.copy(tasks = current.tasks.map { t -> proposed[t.id]?.let { p -> merge(t, p) } ?: t })
                }
                pendingOptions = emptyList()
                "Applied \"${option.title}\"."
            }
            "remember" -> {
                val note = input.str("note")!!.trim()
                store.update { it.copy(memory = (it.memory + note).distinct().takeLast(50)) }
                "Saved."
            }
            "add_rule" -> {
                store.update { BoardOps.addRule(it, input.str("text")!!) }
                "Rule added."
            }
            else -> error("Unknown tool $name")
        }
    }

    /** Places the task if it fits; otherwise leaves the board untouched and returns options. */
    private suspend fun placeOrPropose(board: Board, taskId: String, day: LocalDate): String {
        val options = Rescheduler.options(board, taskId, day)
        val fits = options.singleOrNull()?.id == "fits"
        if (fits) {
            val placed = store.update { current ->
                Planner.plan(current, day, listOf(taskId)).board
            }
            val task = placed.tasks.first { it.id == taskId }
            return "Added \"${task.title}\" (id ${task.id}). Cells: " +
                task.steps.joinToString("; ") { "${it.title} on ${it.date ?: "not placed"} (step ${it.id})" }
        }
        pendingOptions = options
        return "The task was saved (id $taskId) but does NOT fit before its deadline. Nothing was moved. " +
            "Present these options to the user, best first, and apply the one they choose:\n" + describeOptions(options)
    }

    private fun merge(current: Task, proposed: Task) =
        current.copy(steps = current.steps.map { s ->
            val p = proposed.steps.firstOrNull { it.id == s.id } ?: return@map s
            if (s.closed) s else s.copy(date = p.date, slot = p.slot)
        })

    companion object {
        private val WRITE_TOOLS = setOf(
            "add_task", "update_task", "add_steps", "set_step_status", "rename_step", "move_step", "delete_task", "apply_option", "add_rule",
        )

        /** Changes that are easy to miss or hard to undo. Marking a cell done or adding a task is visible at once. */
        private val IMPORTANT_TOOLS = setOf("update_task", "rename_step", "move_step", "delete_task", "apply_option", "add_rule")

        fun describe(board: Board, from: LocalDate, days: Int): String = buildString {
            appendLine("TABLE (day | 5 cells, [x]=done, [ ]=to do, [>]=pushed, [-]=cancelled, · = free)")
            Planner.rows(board, from, days).forEach { row ->
                append(row.label.padEnd(5)).append(" ").append(row.date).append(" | ")
                appendLine(row.cells.joinToString(" | ") { c ->
                    if (c == null) "·" else "[${cellMark(c)}] ${c.title} (step ${c.stepId})"
                })
            }
            val weights = Planner.effectiveWeights(board)
            val open = board.tasks.filter { !it.isDone }
            if (open.isNotEmpty()) {
                appendLine().appendLine("OPEN TASKS")
                open.forEach { t ->
                    append("- ${t.id}: ${t.title}")
                    if (t.project.isNotBlank()) append(" [${t.project}]")
                    append(" priority=${t.priority}")
                    val inherited = weights[t.id] ?: 0
                    if (inherited > t.priority.weight) append(" (inherits ${Priority.entries.first { it.weight == inherited }})")
                    t.deadline?.let { append(" deadline=$it") }
                    t.fixedDate?.let { append(" on=$it") }
                    if (t.blocks.isNotEmpty()) append(" blocks=${t.blocks}")
                    val unplaced = t.steps.count { !it.closed && it.date == null }
                    append(" steps=${t.steps.count { it.done }}/${t.steps.size} done")
                    if (unplaced > 0) append(", $unplaced not placed")
                    if (t.impactNote.isNotBlank()) append(" impact: ${t.impactNote}")
                    appendLine()
                    if (t.description.isNotBlank()) appendLine("    explanation: ${t.description.take(400)}")
                }
            }
        }

        private fun cellMark(c: com.opslegal.tda.core.model.Cell) = when {
            c.done -> "x"
            c.outcome == com.opslegal.tda.core.model.Outcome.PUSHED -> ">"
            c.outcome == com.opslegal.tda.core.model.Outcome.CANCELLED -> "-"
            else -> " "
        }

        fun describeOptions(options: List<RescheduleOption>): String = buildString {
            options.forEachIndexed { i, o ->
                appendLine("${i + 1}. option_id=${o.id}: ${o.title}. ${o.explanation}")
                if (o.moves.isEmpty()) appendLine("   Moves: none")
                o.moves.forEach { m -> appendLine("   Moves \"${m.title}\" ${m.from} -> ${m.to ?: "unplaced"}") }
                if (o.lateTasks.isNotEmpty()) appendLine("   Late: ${o.lateTasks.joinToString()}")
            }
        }
    }
}

// --- tiny JSON schema DSL -------------------------------------------------------

private class SchemaBuilder {
    val props = LinkedHashMap<String, JsonObject>()
    val required = mutableListOf<String>()

    fun prop(name: String, type: String, description: String) {
        props[name] = buildJsonObject { put("type", type); if (description.isNotBlank()) put("description", description) }
    }

    fun enumProp(name: String, values: List<String>, description: String) {
        props[name] = buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(it) } }
            if (description.isNotBlank()) put("description", description)
        }
    }

    fun arrayProp(name: String, description: String) {
        props[name] = buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", description)
        }
    }

    fun required(vararg names: String) { required += names }
}

private fun spec(name: String, description: String, block: SchemaBuilder.() -> Unit): ToolSpec {
    val b = SchemaBuilder().apply(block)
    val schema = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(b.props))
        if (b.required.isNotEmpty()) putJsonArray("required") { b.required.forEach { add(it) } }
    }
    return ToolSpec(name, description, schema)
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
private fun JsonObject.list(key: String): List<String> = (this[key] as? JsonArray)
    ?.map { it.jsonPrimitive.content }.orEmpty()
