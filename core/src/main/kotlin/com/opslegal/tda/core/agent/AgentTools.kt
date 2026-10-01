package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.RescheduleOption
import com.opslegal.tda.core.plan.Rescheduler
import com.opslegal.tda.core.plan.Slots
import com.opslegal.tda.core.plan.Values
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
import java.time.LocalDateTime
import java.time.LocalTime

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
    private val mail: MailSource? = null,
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
            "Add a one-cell task. With project (and optionally steps), append steps to that project in order instead: two levels only, " +
                "anything needing several cells is a project. The planner places the cells. If it does not fit before its deadline, nothing is " +
                "moved and ranked options are returned instead.",
        ) {
            prop("title", "string", "Short title that fits in a table cell.")
            prop("description", "string", "The explanation: what it is, why it matters, useful context. Always fill it in.")
            prop("project", "string", "Project, matter or life area, e.g. Personal, Smith v. Jones. Tasks of a saved project take its priority and deadline.")
            enumProp("kind", KINDS, "task (default, blue text), meeting (black text) or deadline for a delivery or filing due that day (red text).")
            enumProp("effort", EFFORTS, "How heavy it will feel to THIS user, given what is easy or hard for them. Default normal.")
            arrayProp("values", "Names of the user's values this task serves (from VALUES), besides its project's.")
            enumProp("priority", Priority.entries.map { it.name }, "Only for a task without a project. Blockers inherit the priority of what they block automatically.")
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
            enumProp("kind", KINDS, "")
            enumProp("effort", EFFORTS, "")
            arrayProp("values", "Replaces the values this task serves.")
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
        spec(
            "move_step",
            "Move one cell to a specific day and pin it there. Only do this when the user asked for that day. If the day is full, " +
                "the result lists its cells and the least important one that can move: propose it to the user (or the one they name), " +
                "then call again with make_room=true (moves that cell later) or make_room_with=<its step id>.",
        ) {
            prop("step_id", "string", "Step id.")
            prop("date", "string", "ISO date.")
            prop("make_room", "boolean", "If the day is full, move its least important movable cell to its next free day.")
            prop("make_room_with", "string", "If the day is full, the step id of the cell to move later (the one the user chose).")
            required("step_id", "date")
        },
        spec(
            "swap_cells",
            "Swap two cells: each takes the other's day and place, in one move. Use it whenever the user says swap / exchange / " +
                "trade places, or 'put A where B is and B where A is'. Both cells always move; never do half a swap.",
        ) {
            prop("step_a", "string", "Step id of one cell.")
            prop("step_b", "string", "Step id of the other cell.")
            required("step_a", "step_b")
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
        spec(
            "save_project",
            "Create or update a project (a matter or file): an ordered list of steps, one cell each. Priority and deadline belong to projects, not to single tasks: " +
                "every task on the table is important. The project's tasks take its priority and are brought back before its deadline. " +
                "When planning a project, save it first, then add its tasks with project set to its name.",
        ) {
            prop("name", "string", "Project name. Use the existing name to update it.")
            enumProp("priority", Priority.entries.map { it.name }, "")
            prop("deadline", "string", "ISO date, or empty string to remove.")
            prop("notes", "string", "What the project is about, key people, context.")
            arrayProp("values", "Names of the user's values this project serves (from VALUES).")
            arrayProp("blocks", "Names of projects that can't finish until this one is done (it unlocks them and takes their importance).")
            prop("previous_name", "string", "Only to rename: the current name.")
            required("name")
        },
        spec(
            "set_value",
            "Add, change or remove one of the user's values (what matters to them: Brand, Money, Family...). Weight 1 to 3 is how " +
                "much it counts when choosing between tasks. min_per_week is for what they tend to neglect.",
        ) {
            prop("name", "string", "Value name, short.")
            prop("weight", "integer", "1 = counts a little, 3 = counts a lot.")
            prop("meaning", "string", "In the user's words: why it matters, what hurts it.")
            prop("min_per_week", "integer", "Cells per week they want for it; 0 = no minimum.")
            prop("remove", "boolean", "true to delete this value.")
            required("name")
        },
        spec(
            "set_about",
            "Save what comes easily to the user (lighter for them, good rewards and warm-ups) and what they tend to put off " +
                "(heavier for them). Lists replace the current ones: include what is already there that should stay.",
        ) {
            arrayProp("easy", "Kinds of work that are easy or enjoyable for them, e.g. repairs, cars, calls.")
            arrayProp("hard", "Kinds of work they tend to put off, e.g. long reading, paperwork.")
        },
        spec("set_meeting_hours", "Change when people can book meetings with the user.") {
            arrayProp("days", "Days, e.g. mon, tue, wed, thu, fri.")
            arrayProp("windows", "Time windows, e.g. 09:00-12:00, 14:00-17:00.")
            prop("duration_minutes", "integer", "Usual meeting length.")
            prop("max_per_day", "integer", "Most meetings in one day.")
            prop("buffer_minutes", "integer", "Break kept before and after each meeting.")
        },
        spec(
            "find_slots",
            "Find meeting times to offer someone: they fit the user's meeting hours and calendar, and a day that still has room " +
                "in the table, isn't next to a deadline and isn't already heavy. Read only.",
        ) {
            prop("from", "string", "First day, ISO date. Default today.")
            prop("days", "integer", "How many days to look at. Default 10, at most 30.")
            prop("duration_minutes", "integer", "Default: the user's usual meeting length.")
            prop("count", "integer", "How many options. Default 3.")
        },
        spec(
            "draft_message",
            "Prepare a message for the user to send themselves (e.g. offering the slots from find_slots). The app shows it with a " +
                "Share button; you never send anything. Write it in the recipient's language, short and friendly, in the user's name.",
        ) {
            prop("to", "string", "Who it is for.")
            prop("text", "string", "The message.")
            required("text")
        },
        spec(
            "book_meeting",
            "Book a meeting that the other person accepted: adds a black meeting cell on that day and, when possible, an event " +
                "in the user's calendar.",
        ) {
            prop("title", "string", "Short, e.g. \"Intro call Jean Martin\".")
            prop("with", "string", "Who the meeting is with.")
            prop("date", "string", "ISO date.")
            prop("start", "string", "Start time, HH:MM.")
            prop("duration_minutes", "integer", "Default: the user's usual meeting length.")
            prop("notes", "string", "What it is about, contact details, where.")
            required("title", "date", "start")
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
        mail?.let {
            spec(
                "read_email",
                "Read the user's work email (Microsoft 365), read only: search the whole mailbox for words (a name, a file " +
                    "number, 'invoice'), or without a query list the latest emails in the inbox. Shows sender, subject and " +
                    "first lines. You can never send, move or delete email. Only read what the current request needs.",
            ) {
                prop("query", "string", "Words to search for. Empty = latest inbox emails.")
                prop("limit", "integer", "How many emails. Default 10, at most 25.")
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
        fun stepTitle(id: String?) = id?.let { BoardOps.findStep(board, it) }?.let { (t, s) -> Planner.cellTitle(t, s) } ?: error("Unknown step $id")
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
            "move_step" -> "move \"${stepTitle(input.str("step_id"))}\" to ${input.str("date")}" +
                (input.str("make_room_with")?.ifBlank { null }?.let { " and move \"${stepTitle(it)}\" later" }
                    ?: if (input.bool("make_room") == true) " (a less important cell moves later if the day is full)" else "")
            "swap_cells" -> "swap \"${stepTitle(input.str("step_a"))}\" and \"${stepTitle(input.str("step_b"))}\""
            "delete_task" -> "delete \"${taskTitle(input.str("task_id"))}\" and all its cells"
            "apply_option" -> "apply: " + (pendingOptions.firstOrNull { it.id == input.str("option_id") }?.title
                ?: error("Unknown option. Call propose_options again."))
            "add_rule" -> "add the rule \"${input.str("text")}\""
            "set_value" -> if (input.bool("remove") == true) "remove the value \"${input.str("name")}\"" else buildString {
                append("set the value \"${input.str("name")}\"")
                input.int("weight")?.let { append(", weight $it") }
                input.int("min_per_week")?.takeIf { it > 0 }?.let { append(", at least $it a week") }
            }
            "set_about" -> buildString {
                append("remember")
                if ("easy" in input) append(" easy for you: ${input.list("easy").joinToString()}")
                if ("hard" in input) append(if ("easy" in input) "; hard: " else " hard for you: ").append(input.list("hard").joinToString())
            }
            "set_meeting_hours" -> "change your meeting hours (" + input.keys.joinToString { k -> "$k: ${input[k]}" } + ")"
            "book_meeting" -> "book \"${input.str("title")}\" on ${input.str("date")} at ${input.str("start")}" +
                (input.str("with")?.let { " with $it" } ?: "")
            "save_project" -> buildString {
                append("save the project \"${input.str("name")}\"")
                input.str("priority")?.let { append(", ${it.lowercase()} priority") }
                input.str("deadline")?.ifBlank { null }?.let { append(", due $it") }
            }
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
                    kind = kind(input) ?: TaskKind.TASK,
                    effort = effort(input) ?: Effort.NORMAL,
                    values = input.list("values"),
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
                var newSteps = emptyList<String>()
                val board = store.update { b -> BoardOps.add(b, spec, day).also { added = it.task; newSteps = it.steps.map { s -> s.id } }.board }
                val result = placeOrPropose(board, added!!.id, day)
                if (!added!!.isProject) result
                else {
                    // A close deadline: less urgent cells move later so the project's steps fit before it.
                    var moved = emptyList<String>()
                    store.update { b -> Projects.makeRoomForDeadline(b, added!!.project, day).also { moved = it.moved }.board }
                    val t = store.read().tasks.first { it.id == added!!.id }
                    "Added to the project \"${t.project}\": " + t.steps.filter { it.id in newSteps }
                        .joinToString("; ") { "${it.title} on ${it.date ?: "not placed"} (step ${it.id})" } +
                        (if (moved.isNotEmpty()) "\nTo meet the deadline, these moved later (tell the user): ${moved.joinToString()}" else "") +
                        if (moved.isEmpty() && result.contains("does NOT fit")) "\n" + result else ""
                }
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
                            kind = kind(input) ?: t.kind,
                            effort = effort(input) ?: t.effort,
                            values = if ("values" in input) input.list("values") else t.values,
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
            "read_email" -> {
                val source = mail ?: error("Work email is not connected. Ask the user to sign in to Microsoft in Settings.")
                val limit = (input.int("limit") ?: 10).coerceIn(1, 25)
                val query = input.str("query")?.ifBlank { null }
                val found = if (query != null) source.search(query, limit) else source.recent(null, limit)
                if (found.isEmpty()) "No emails found."
                else found.joinToString("\n") { m -> "${m.received} · ${m.from} · ${m.subject}: ${m.preview.replace('\n', ' ').take(300)}" }
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
                val with = input.str("make_room_with")?.ifBlank { null }
                val makeRoom = input.bool("make_room") == true || with != null
                var full = false
                var moved: String? = null
                var movedId: String? = null
                store.update { b ->
                    requireNotNull(BoardOps.findStep(b, stepId)) { "Unknown step $stepId" }
                    BoardOps.moveStep(b, stepId, date)?.let { return@update it }
                    if (!makeRoom) { full = true; return@update b }
                    val owner = BoardOps.findStep(b, stepId)!!.first.id
                    val victim = with?.let { id ->
                        val (t, st) = BoardOps.findStep(b, id) ?: error("Unknown step $id")
                        require(st.date == date.toString()) { "That cell is not on $date." }
                        require(!st.closed) { "That cell is done or grey; it already leaves room. Pick another." }
                        t to st
                    } ?: Projects.movableOn(b, date, owner, day) ?: error("Every cell on $date is a meeting, a deadline, done or placed by hand. Ask the user which one may move.")
                    moved = Planner.cellTitle(victim.first, victim.second)
                    movedId = victim.second.id
                    // The moved cell goes to its next free day; the requested one takes its place.
                    val freed = Projects.makeRoom(b, victim.second.id, stepId, date)
                    Planner.plan(freed, day).board
                }
                if (full) {
                    val b = store.read()
                    val owner = BoardOps.findStep(b, stepId)?.first?.id
                    val cells = b.tasks.flatMap { t -> t.steps.filter { it.date == date.toString() && it.slot != null }.map { t to it } }
                    val best = Projects.movableOn(b, date, owner, day)
                    return "$date is full. Its cells: " + cells.joinToString("; ") { (t, st) ->
                        "${Planner.cellTitle(t, st)} (step ${st.id}, ${t.kindOf(st).name.lowercase()}, priority ${t.priority}" +
                            (if (st.pinned) ", placed by hand" else "") + (if (st.closed) ", done/grey" else "") + ")"
                    } + ". " + (best?.let { "The least important one that can move: \"${Planner.cellTitle(it.first, it.second)}\" (step ${it.second.id}). " } ?: "") +
                        "Nothing changed. Tell the user the day is full and propose which cell to move later (the least important by priority, " +
                        "values and deadlines, never a meeting or deadline unless they say so), in one short question. When they agree or name " +
                        "another, call move_step again with make_room_with=<that step id>."
                }
                val newDay = movedId?.let { BoardOps.findStep(store.read(), it)?.second?.date }
                if (moved != null) "Moved to $date. To make room, \"$moved\" moved to ${newDay ?: "a later day"}." else "Moved to $date."
            }
            "swap_cells" -> {
                val a = input.str("step_a")!!
                val bId = input.str("step_b")!!
                var result = ""
                store.update { b ->
                    val (ta, sa) = BoardOps.findStep(b, a) ?: error("Unknown step $a")
                    val (tb, sb) = BoardOps.findStep(b, bId) ?: error("Unknown step $bId")
                    require(sa.date != null && sb.date != null) { "Both cells must be on the table to swap." }
                    var next = BoardOps.mapStep(b, a) { it.copy(date = sb.date, slot = sb.slot, pinned = true, notBefore = null) }
                    next = BoardOps.mapStep(next, bId) { it.copy(date = sa.date, slot = sa.slot, pinned = true, notBefore = null) }
                    result = "Swapped: \"${Planner.cellTitle(ta, sa)}\" is now on ${sb.date}, \"${Planner.cellTitle(tb, sb)}\" is now on ${sa.date}."
                    next
                }
                result
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
            "set_value" -> {
                val name = input.str("name")?.trim().orEmpty()
                require(name.isNotEmpty()) { "name is required" }
                store.update { b ->
                    val current = b.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    if (input.bool("remove") == true) return@update b.copy(values = b.values.filterNot { it === current })
                    val value = Value(
                        name = current?.name ?: name,
                        weight = (input.int("weight") ?: current?.weight ?: 2).coerceIn(1, 3),
                        meaning = input.str("meaning") ?: current?.meaning.orEmpty(),
                        minPerWeek = if ("min_per_week" in input) input.int("min_per_week")?.takeIf { it > 0 } else current?.minPerWeek,
                    )
                    b.copy(values = if (current == null) b.values + value else b.values.map { if (it === current) value else it })
                }
                "Saved."
            }
            "set_about" -> {
                store.update { b ->
                    b.copy(
                        about = b.about.copy(
                            profile = b.about.profile ?: "none",
                            easy = if ("easy" in input) input.list("easy").map { it.trim() }.filter { it.isNotEmpty() }.distinct() else b.about.easy,
                            hard = if ("hard" in input) input.list("hard").map { it.trim() }.filter { it.isNotEmpty() }.distinct() else b.about.hard,
                        ),
                    )
                }
                "Saved."
            }
            "set_meeting_hours" -> {
                store.update { b ->
                    val m = b.meetings
                    val days = input.list("days").mapNotNull(::dayNumber).distinct().sorted()
                    val windows = input.list("windows").filter { Slots.parseWindow(it) != null }
                    require("windows" !in input || windows.isNotEmpty()) { "Windows must look like 09:00-12:00." }
                    b.copy(
                        meetings = m.copy(
                            days = days.ifEmpty { m.days },
                            windows = windows.ifEmpty { m.windows },
                            durationMinutes = input.int("duration_minutes")?.coerceIn(10, 480) ?: m.durationMinutes,
                            maxPerDay = input.int("max_per_day")?.coerceIn(1, 5) ?: m.maxPerDay,
                            bufferMinutes = input.int("buffer_minutes")?.coerceIn(0, 120) ?: m.bufferMinutes,
                        ),
                    )
                }
                "Saved."
            }
            "find_slots" -> {
                val board = store.read()
                val from = input.str("from")?.ifBlank { null }?.let(LocalDate::parse) ?: day
                val days = (input.int("days") ?: 10).coerceIn(1, 30)
                val events = calendar?.events(from, from.plusDays(days.toLong())).orEmpty()
                val now = if (day == LocalDate.now()) LocalDateTime.now() else day.atStartOfDay()
                val slots = Slots.find(
                    board, events, from, days, (input.int("count") ?: 3).coerceIn(1, 6), now,
                    input.int("duration_minutes") ?: board.meetings.durationMinutes,
                )
                buildString {
                    if (slots.isEmpty()) append("No slot fits in these $days days. Offer to look further or relax a limit.")
                    slots.forEach { appendLine("- ${it.label()} (date ${it.date}, start ${it.start})") }
                    if (calendar == null) append("Note: the calendar is not connected, so only the table was checked.")
                }.trim()
            }
            "draft_message" -> {
                state.setDraft(Draft(input.str("to").orEmpty(), input.str("text") ?: error("text is required")))
                "The message is ready with a Share button. Tell the user in a few words; don't repeat it all."
            }
            "book_meeting" -> {
                val board = store.read()
                val date = LocalDate.parse(input.str("date") ?: error("date is required"))
                val start = LocalTime.parse(input.str("start")!!.trim().padStart(5, '0'))
                val minutes = (input.int("duration_minutes") ?: board.meetings.durationMinutes).coerceIn(10, 480)
                val end = start.plusMinutes(minutes.toLong())
                val with = input.str("with").orEmpty()
                val title = input.str("title") ?: error("title is required")
                val notes = listOfNotNull(
                    "$start–$end" + (if (with.isNotBlank()) " with $with" else ""),
                    input.str("notes")?.ifBlank { null },
                ).joinToString(". ")
                var added: Task? = null
                store.update { b ->
                    val spec = BoardOps.NewTask(
                        title = "$start $title", description = notes, kind = TaskKind.MEETING, fixedDate = date.toString(),
                    )
                    val (next, task) = BoardOps.addTask(b, spec, day)
                    added = task
                    Planner.plan(next, day).board
                }
                val cal = calendar?.addEvent(title, date.atTime(start), date.atTime(end), notes)
                val placed = store.read().tasks.firstOrNull { it.id == added?.id }?.steps?.firstOrNull()?.date != null
                buildString {
                    append(if (placed) "Meeting cell added on $date." else "$date is full: the meeting was saved but has no cell. Tell the user.")
                    append(if (cal != null) " Added to the calendar \"$cal\"." else " Not added to the phone calendar (not connected or not allowed).")
                }
            }
            "save_project" -> {
                val name = input.str("name")?.trim().orEmpty()
                val previous = input.str("previous_name")?.ifBlank { null }
                store.update { b ->
                    val current = BoardOps.findProject(b, previous ?: name)
                    val project = Project(
                        name = name,
                        priority = input.str("priority")?.let { Priority.valueOf(it) } ?: current?.priority ?: Priority.NORMAL,
                        deadline = if ("deadline" in input) input.str("deadline")?.ifBlank { null } else current?.deadline,
                        notes = input.str("notes") ?: current?.notes.orEmpty(),
                        values = if ("values" in input) input.list("values") else current?.values.orEmpty(),
                        blocks = if ("blocks" in input) input.list("blocks") else current?.blocks.orEmpty(),
                    )
                    Projects.makeRoomForDeadline(Planner.plan(BoardOps.saveProject(b, project, previous), day).board, name, day).board
                }
                "Project \"$name\" saved."
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
            "add_task", "update_task", "add_steps", "set_step_status", "rename_step", "move_step", "swap_cells", "delete_task", "apply_option", "add_rule", "save_project",
            "set_value", "set_about", "set_meeting_hours", "book_meeting",
        )

        /** Changes that are easy to miss or hard to undo. Marking a cell done or adding a task is visible at once. */
        private val IMPORTANT_TOOLS = setOf(
            "update_task", "rename_step", "move_step", "swap_cells", "delete_task", "apply_option", "add_rule", "save_project",
            "set_value", "set_about", "set_meeting_hours", "book_meeting",
        )

        private val KINDS = TaskKind.entries.map { it.name.lowercase() }
        private val EFFORTS = Effort.entries.map { it.name.lowercase() }

        private fun effort(input: JsonObject): Effort? =
            input.str("effort")?.let { e -> Effort.entries.firstOrNull { it.name.equals(e, ignoreCase = true) } }

        private fun dayNumber(text: String): Int? {
            val t = text.trim().lowercase()
            t.toIntOrNull()?.let { return it.takeIf { n -> n in 1..7 } }
            val names = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
            val french = listOf("lu", "ma", "me", "je", "ve", "sa", "di")
            return (names.indexOf(t.take(2)).takeIf { it >= 0 } ?: french.indexOf(t.take(2)).takeIf { it >= 0 })?.plus(1)
        }

        private fun kind(input: JsonObject): TaskKind? =
            input.str("kind")?.let { k -> TaskKind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }

        fun describe(board: Board, from: LocalDate, days: Int): String = buildString {
            appendLine("TABLE (day | 5 cells, [x]=done, [ ]=to do, [>]=pushed, [-]=cancelled, · = free)")
            Planner.rows(board, from, days).forEach { row ->
                append(row.label.padEnd(5)).append(" ").append(row.date).append(" | ")
                appendLine(row.cells.joinToString(" | ") { c ->
                    if (c == null) "·" else "[${cellMark(c)}] ${c.title} (step ${c.stepId})"
                })
            }
            if (board.values.isNotEmpty()) {
                appendLine().appendLine("VALUES (what matters to the user, weight 1-3)")
                board.values.forEach { v ->
                    append("- ${v.name} weight=${v.weight}")
                    v.minPerWeek?.let { append(" min_per_week=$it") }
                    if (v.meaning.isNotBlank()) append(": ${v.meaning}")
                    appendLine()
                }
                val gaps = Values.gaps(board, from)
                if (gaps.isNotEmpty()) {
                    appendLine("BALANCE this week, below minimum: " + gaps.joinToString { "${it.value.name} ${it.count}/${it.min}" })
                }
            }
            if (board.about.easy.isNotEmpty() || board.about.hard.isNotEmpty()) {
                appendLine().appendLine("ABOUT THE USER")
                if (board.about.easy.isNotEmpty()) appendLine("- easy or enjoyable for them: ${board.about.easy.joinToString()}")
                if (board.about.hard.isNotEmpty()) appendLine("- they tend to put off: ${board.about.hard.joinToString()}")
            }
            if (board.projects.isNotEmpty()) {
                appendLine().appendLine("PROJECTS (steps in order, [x] = done)")
                board.projects.forEach { p ->
                    if (Projects.isIdea(board, p)) {
                        appendLine("- ${p.name} IDEA (parked, not started, no cells)" + (if (p.notes.isNotBlank()) ": ${p.notes.take(300)}" else ""))
                        return@forEach
                    }
                    val stats = Projects.stats(board, p)
                    append("- ${p.name} ${stats.percent}% done (${stats.done}/${stats.total}) priority=${p.priority}")
                    p.deadline?.let { append(" deadline=$it") }
                    stats.end.end?.let { append(" ends=$it") }
                    if (stats.end.late) append(" AT RISK")
                    if (p.blocks.isNotEmpty()) append(" unlocks=${p.blocks}")
                    if (p.values.isNotEmpty()) append(" values=${p.values}")
                    if (p.notes.isNotBlank()) append(" notes: ${p.notes.take(300)}")
                    appendLine()
                    BoardOps.projectTask(board, p.name)?.let { t ->
                        t.steps.filter { it.outcome == null }.forEachIndexed { i, st ->
                            append("    ${i + 1}. [${if (st.done) "x" else " "}] ${st.title}")
                            st.date?.let { append(" on $it") }
                            if (t.kindOf(st) != TaskKind.TASK) append(" ${t.kindOf(st).name.lowercase()}")
                            if (t.effortOf(st) != Effort.NORMAL) append(" effort=${t.effortOf(st).name.lowercase()}")
                            appendLine(" (step ${st.id})")
                        }
                    }
                }
            }
            val weights = Planner.effectiveWeights(board)
            val open = board.tasks.filter { !it.isDone && !it.isProject }
            if (open.isNotEmpty()) {
                appendLine().appendLine("ONE-CELL TASKS")
                open.forEach { t ->
                    append("- ${t.id}: ${t.title}")
                    if (t.project.isNotBlank()) append(" [${t.project}]")
                    if (t.kind != TaskKind.TASK) append(" ${t.kind.name.lowercase()}")
                    if (t.effort != Effort.NORMAL) append(" effort=${t.effort.name.lowercase()}")
                    if (t.pushes > 0) append(" pushed ${t.pushes}x")
                    if (t.values.isNotEmpty()) append(" values=${t.values}")
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
