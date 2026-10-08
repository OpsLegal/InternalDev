package com.opslegal.tda.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.agent.ChatItem
import com.opslegal.tda.core.voice.Reply
import com.opslegal.tda.core.voice.ReplyClassifier
import com.opslegal.tda.voice.VoiceController
import com.opslegal.tda.voice.VoiceState
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.model.DefaultRules
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Values
import com.opslegal.tda.core.agent.AssistantPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.opslegal.tda.core.plan.Updates
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TdaApp

    val board = app.boards.board
    val chat = app.chat.items
    val settings = app.settings.settings
    val premium = app.billing.premium
    val offers = app.billing.offers

    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()

    fun refresh() = viewModelScope.launch {
        app.refreshToday()
        app.billing.refresh()
        loadCalendar()
    }

    fun edit(change: (Board) -> Board) = viewModelScope.launch { app.boards.update(change) }

    /** Edits the board and then lets the planner place anything new. */
    fun editAndPlan(change: (Board) -> Board) = viewModelScope.launch {
        val today = LocalDate.now()
        app.boards.update { Planner.plan(change(it), today).board }
    }

    fun addTask(spec: BoardOps.NewTask) = editAndPlan { BoardOps.addTask(it, spec, LocalDate.now()).first }

    private val sharedState = MutableStateFlow<String?>(null)

    /** Text shared from another app, waiting to be placed in the assistant's message box. */
    val sharedText: StateFlow<String?> = sharedState.asStateFlow()

    fun receiveShared(text: String) {
        if (text.isNotBlank()) sharedState.value = text.take(8000)
    }

    fun consumeShared(): String? = sharedState.value.also { sharedState.value = null }

    /** A one-line message on top of the table; [warn] when a project would miss its deadline. */
    data class Notice(val text: String, val warn: Boolean = false)

    private val noticeState = MutableStateFlow<Notice?>(null)

    val notice: StateFlow<Notice?> = noticeState.asStateFlow()

    fun dismissNotice() {
        noticeState.value = null
    }

    /** Adds a task on a chosen day; if that day is full, the planner finds the next free cell. */
    fun addTaskOn(spec: BoardOps.NewTask, date: LocalDate) = viewModelScope.launch {
        val today = LocalDate.now()
        var placed = true
        app.boards.update { b ->
            val (next, _, onDay) = BoardOps.addTaskOn(b, spec, date, today)
            placed = onDay
            Planner.plan(next, today).board
        }
        if (!placed) {
            noticeState.value = Notice("That day already has 5 open tasks, so \"${spec.title}\" went to the next free day. " +
                "Push or cancel a cell to make room.")
        }
    }

    fun setDone(stepId: String, done: Boolean) = edit { BoardOps.setStepDone(it, stepId, done) }

    fun reopen(stepId: String) = edit { BoardOps.reopenStep(it, stepId) }

    /** What a change would do to a project, computed on a copy before anything is applied. */
    fun impactOf(projectName: String, change: (Board) -> Board): Projects.End {
        val today = LocalDate.now()
        return Projects.end(Planner.plan(change(board.value), today).board, projectName)
    }

    /**
     * Applies a change, places what needs a cell, and, for a project, says where it now ends
     * ("Tax report now ends Mon 5"). [quiet]: say nothing unless the project finished or is at risk.
     */
    fun apply(change: (Board) -> Board, projectName: String? = null, doneText: String? = null, quiet: Boolean = false) = viewModelScope.launch {
        val today = LocalDate.now()
        val next = app.boards.update { Planner.plan(change(it), today).board }
        if (projectName == null) { doneText?.let { noticeState.value = Notice(it) }; return@launch }
        val end = Projects.end(next, projectName)
        if (quiet && end.open > 0) return@launch
        val text = when {
            end.open == 0 -> "$projectName is finished."
            else -> "$projectName now ends ${end.end?.let(::dayName) ?: "later (no free cell yet)"}" +
                (end.deadline?.let { " (deadline ${dayName(it)})" } ?: "") + "."
        }
        noticeState.value = Notice(listOfNotNull(doneText, text).joinToString(" "), end.late)
    }

    fun dayName(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth}"

    /** Saves a one-cell task's corrections; with a project name, the task becomes that project's next step. */
    fun updateTask(taskId: String, spec: BoardOps.NewTask, day: LocalDate? = null) = edit { b ->
        val today = LocalDate.now()
        var next = BoardOps.updateTask(b, taskId) { t ->
            t.copy(
                title = spec.title.trim(),
                description = spec.description.trim(),
                kind = spec.kind,
                effort = spec.effort,
                effortByUser = spec.effortByUser,
                values = (spec.values + spec.serve.keys).distinct(),
                intention = spec.intention.trim(),
                serve = spec.serve.filterValues { it > 0 },
                where = spec.where.trim(),
                steps = t.steps.map { it.copy(title = spec.title.trim()) },
            )
        }
        if (spec.project.isNotBlank()) {
            next = BoardOps.moveIntoProject(next, taskId, spec.project.trim(), today)
            noticeState.value = Notice("\"${spec.title.trim()}\" is now a step of ${BoardOps.findProject(next, spec.project)?.name ?: spec.project}.")
        }
        // A new day chosen in the form: the cell moves there (a free cell, or the least important movable one).
        val step = next.tasks.firstOrNull { it.id == taskId }?.steps?.firstOrNull { !it.closed }
        if (day != null && step != null && step.date != day.toString()) {
            next = com.opslegal.tda.core.plan.Projects.pinStep(next, step.id, day, today)
            val placed = BoardOps.findStep(next, step.id)?.second?.date == day.toString()
            noticeState.value = Notice(if (placed) "\"${spec.title.trim()}\" moved to ${dayName(day)}." else "${dayName(day)} is full of fixed cells, so \"${spec.title.trim()}\" goes to the next free day.")
        }
        Planner.plan(next, today).board
    }

    /** Saves a project and its list of steps still to do (new, renamed, reordered or removed). */
    fun saveProject(project: Project, previousName: String?, steps: List<BoardOps.EditedStep>) = viewModelScope.launch {
        val today = LocalDate.now()
        var moved = emptyList<String>()
        val next = app.boards.update { b ->
            Projects.save(Planner.plan(b, today).board, project, previousName, steps, today).also { moved = it.moved }.board
        }
        val end = Projects.end(next, project.name.trim())
        val last = end.end
        if (last != null) {
            val room = if (moved.isEmpty()) "" else " To meet the deadline, moved later: ${moved.joinToString()}."
            noticeState.value = Notice("${project.name.trim()} ends ${dayName(last)}" + (end.deadline?.let { " (deadline ${dayName(it)})" } ?: "") + "." + room, end.late)
        }
    }

    /** One step as the project form will place it: its day, and whether that is after the deadline. */
    data class StepDay(val date: LocalDate?, val late: Boolean)

    /**
     * What saving would do, computed on a copy: each open step's day (in the form's order), and the cells that
     * would move later to make room. Shown under the steps before the user saves.
     */
    fun previewProject(project: Project, previousName: String?, steps: List<BoardOps.EditedStep>): Pair<List<StepDay>, List<String>> {
        val today = LocalDate.now()
        val name = project.name.trim().ifBlank { return emptyList<StepDay>() to emptyList() }
        val saved = runCatching { Projects.save(board.value, project.copy(name = name), previousName, steps, today) }.getOrNull()
            ?: return emptyList<StepDay>() to emptyList()
        val limit = project.deadline?.let { LocalDate.parse(it) }
        val open = BoardOps.projectTask(saved.board, name)?.steps.orEmpty().filter { !it.closed }
        return open.map { s ->
            val d = s.date?.let(LocalDate::parse)
            StepDay(d, d == null || (limit != null && d.isAfter(maxOf(limit, today))))
        } to saved.moved
    }

    fun deleteProject(name: String) = edit { BoardOps.deleteProject(it, name) }

    /** Saves an idea (a project with no steps). Returns why it can't be saved, or null. */
    fun saveIdea(previousName: String?, name: String, text: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Give the idea a name."
        val b = board.value
        val clash = BoardOps.findProject(b, n)
        if (clash != null && !clash.name.equals(previousName?.trim(), ignoreCase = true)) return "“$n” already exists."
        val old = previousName?.let { BoardOps.findProject(b, it) }
        edit { BoardOps.saveProject(it, (old ?: Project(n)).copy(name = n, notes = text.trim()), old?.name) }
        return null
    }

    /** Puts an idea in order: main objective, sub-objectives, key details, in the user's language. Never invents. */
    suspend fun cleanIdea(name: String, text: String): String {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first.")
        val prompt = buildString {
            appendLine("A Docket 5 user is parking an idea to start later. Put their text in order so it is clear and easy to act on later.")
            appendLine("Idea: \"${name.ifBlank { "(no name yet)" }}\"")
            appendLine("Their text:")
            appendLine("\"\"\"$text\"\"\"")
            appendLine("Reply with only the new text, nothing before or after. Its parts, each heading on its own line:")
            appendLine("Main objective: one sentence.")
            appendLine("Sub-objectives: (only if the text has some) one line each starting with \"- \".")
            appendLine("Key details: one line each starting with \"- \" (people, places, dates, amounts, constraints, first ideas of steps).")
            appendLine("Keep everything the user said; never invent. Short plain sentences. Write everything, headings included, in the language of their text.")
        }
        return provider.complete("Reply with the text only.", listOf(ChatItem.User(prompt)), emptyList()).text.trim()
            .ifBlank { error("It could not be cleaned up. Try again.") }
    }

    /** Remembers where the user dragged the round buttons. */
    fun saveButtonsPosition(xDp: Float, yDp: Float) = updateSettings { it.copy(fabX = xDp, fabY = yDp) }

    /** A task prepared by the AI for the task form, for the user to check before adding. */
    data class DraftTask(
        val title: String, val notes: String, val kind: com.opslegal.tda.core.model.TaskKind,
        val effort: com.opslegal.tda.core.model.Effort, val project: String, val day: LocalDate?,
        /** For a change: one short sentence saying what was changed and why. */
        val reason: String = "",
        /** Why it matters to the user, in one sentence. */
        val intention: String = "",
        /** What it serves (Ground · Build · Nourish on): a level 1-3 per attribute. */
        val serve: Map<String, Int> = emptyMap(),
        /** Where it happens, for a physical task. */
        val where: String = "",
        /** A planned cell it can be done during (no cell of its own), when it fits. */
        val ride: com.opslegal.tda.core.plan.Rides.Host? = null,
        /** Distance between this task's place and the host's, in km, when known. */
        val rideKm: Double? = null,
        /** The host fits by activity but is farther than the user's max detour. */
        val rideFar: Boolean = false,
        /** It needs preparation or thinking first: it gets its own cell. */
        val needsPrep: Boolean = false,
    )

    /** A place name to a point, with the phone's own map lookup (no key, nothing sent to us). Null when not found. */
    private suspend fun locate(place: String): Pair<Double, Double>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (place.isBlank() || !android.location.Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(getApplication(), java.util.Locale.getDefault()).getFromLocationName(place, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** Distance between two places: the map lookup when both are found, else the AI's estimate. */
    private suspend fun distance(a: String, b: String, estimate: Double?): Double? {
        if (a.equals(b, ignoreCase = true)) return 0.0
        val pa = locate(a) ?: return estimate
        val pb = locate(b) ?: return estimate
        return com.opslegal.tda.core.plan.Rides.km(pa, pb)
    }

    /** Levels and intention read from the AI's JSON. */
    private fun readServe(json: kotlinx.serialization.json.JsonObject): Map<String, Int> =
        if (!board.value.gbn) emptyMap()
        else com.opslegal.tda.core.plan.Gbn.parseLevels(board.value, (json["serve"] as? kotlinx.serialization.json.JsonObject).orEmpty()
            .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() })

    private fun StringBuilder.askIntentionAndServe() {
        appendLine("- intention: one sentence starting with \"To\", why it matters to the user (in the language of their words).")
        if (board.value.gbn) {
            append(com.opslegal.tda.core.plan.Gbn.prompt(board.value))
            appendLine("- serve: an object {attribute: level 1-3} for every attribute above that it serves, as many as apply.")
        }
    }

    /**
     * Asks the connected AI to write the task from its name and explanation: the explanation in clear words, the
     * intention, what it serves, and the rest of the form. For a task being changed, the edited explanation says what changes.
     */
    suspend fun draftTask(name: String, describe: String, current: com.opslegal.tda.core.model.Task? = null, currentDay: LocalDate? = null): DraftTask {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first, or use Manual mode.")
        val b = board.value
        val today = LocalDate.now()
        val prompt = buildString {
            appendLine("You fill a task form for a Docket 5 user. A task is one cell: one focused block of a few hours.")
            appendLine("Today is $today (${today.dayOfWeek.name.lowercase()}).")
            if (b.projects.isNotEmpty()) appendLine("Their projects: ${b.projects.joinToString { it.name }}.")
            if (current != null) {
                appendLine("CURRENT TASK: title \"${current.title}\"; notes \"${current.description}\"; type ${current.kind.name.lowercase()}; " +
                    "effort ${current.effort.name.lowercase()}; day ${currentDay ?: "not placed"}.")
                appendLine("Free cells per working day (5 a day): " + generateSequence(today) { it.plusDays(1) }
                    .filter { it.dayOfWeek.value in b.settings.workDays }.take(15)
                    .joinToString { d -> "$d ${d.dayOfWeek.name.take(3).lowercase()}: ${5 - b.tasks.flatMap { it.steps }.count { it.date == d.toString() && it.slot != null }}" })
                appendLine("The user edited the explanation below: apply what changed; keep everything else exactly as it is. Prefer a day with a free cell.")
                appendLine("Also give \"reason\": one short sentence saying what you changed and why.")
            }
            if (current == null) {
                val hosts = com.opslegal.tda.core.plan.Rides.hosts(b, today)
                if (hosts.isNotEmpty()) {
                    appendLine("CELLS ALREADY PLANNED (id | day | title | place):")
                    hosts.take(40).forEach { h -> appendLine("${h.stepId} | ${h.date} | ${h.title}${if (h.errands) " (errands trip)" else ""} | ${h.where.ifBlank { "-" }}") }
                    appendLine("RIDE ALONG: if the new task is quick (about 30 minutes or less), needs no preparation or thinking first, and can be done")
                    appendLine("during one of these cells (same place or same activity, e.g. asking the contractor something during his visit, or an errand")
                    appendLine("on an errands trip), give its id as \"ride_with\". Otherwise ride_with is empty. If it needs preparation, \"prep\": true.")
                    appendLine("When both are physical places, \"km\": your best estimate of the distance between them (a number), else null.")
                }
            }
            appendLine("Name the user gave: \"${name.trim()}\"")
            appendLine("The user's explanation:")
            appendLine("\"\"\"$describe\"\"\"")
            appendLine("Reply with only a JSON object: {\"title\": string, \"notes\": string, \"intention\": string, " + (if (b.gbn) "\"serve\": {}, " else "") +
                "\"type\": \"task\"|\"meeting\"|\"deadline\", \"effort\": \"light\"|\"normal\"|\"heavy\", \"project\": string, \"day\": string}.")
            appendLine("- title: the user's name if they gave one (fix only the spelling), else at most 6 words; what the cell shows.")
            appendLine("- where: for a physical task (going somewhere, or something at home), the place: a store, an address, an area, or \"Home\"; else empty. Also give \"ride_with\", \"prep\" and \"km\" as said above when cells are listed.")
            appendLine("- notes: the explanation rewritten in clear, correct words, 1 to 3 short sentences, keeping all of the user's facts and adding none.")
            askIntentionAndServe()
            appendLine("- type: meeting for a call or meeting, deadline for a filing or delivery due that day, else task.")
            appendLine("- project: one of their projects if it clearly belongs to it, else empty.")
            appendLine(if (current != null) "- day: YYYY-MM-DD, the task's day after the change (the current one if unchanged)." else "- day: YYYY-MM-DD only if the user named a day, else empty.")
            appendLine("Write in the language of the user's words.")
        }
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt)), emptyList()).text
        val body = reply.substring(reply.indexOf('{').coerceAtLeast(0), (reply.lastIndexOf('}') + 1).coerceAtLeast(0))
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("The task could not be prepared. Try again, or use Manual mode.")
        fun str(k: String) = json[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val d = DraftTask(
            title = str("title").ifBlank { name.trim().ifBlank { describe.trim().take(40) } },
            notes = str("notes").ifBlank { describe.trim() },
            intention = str("intention"),
            serve = readServe(json),
            where = str("where"),
            kind = when (str("type").lowercase()) { "meeting" -> com.opslegal.tda.core.model.TaskKind.MEETING; "deadline" -> com.opslegal.tda.core.model.TaskKind.DEADLINE; else -> com.opslegal.tda.core.model.TaskKind.TASK },
            effort = when (str("effort").lowercase()) { "light" -> com.opslegal.tda.core.model.Effort.LIGHT; "heavy" -> com.opslegal.tda.core.model.Effort.HEAVY; else -> com.opslegal.tda.core.model.Effort.NORMAL },
            project = str("project").let { p -> b.projects.firstOrNull { it.name.equals(p, ignoreCase = true) }?.name.orEmpty() },
            day = runCatching { LocalDate.parse(str("day")) }.getOrNull()?.takeIf { !it.isBefore(today) },
            reason = str("reason"),
        )
        if (current != null) return d
        val prep = json["prep"]?.jsonPrimitive?.booleanOrNull == true
        val host = str("ride_with").takeIf { it.isNotBlank() }?.let { id -> com.opslegal.tda.core.plan.Rides.hosts(b, today).firstOrNull { it.stepId == id } }
        if (prep || host == null) return d.copy(needsPrep = prep)
        // Physical on both sides: close enough, or not joined. Unknown places are never joined on a guess.
        val estimate = json["km"]?.jsonPrimitive?.doubleOrNull
        val km = when {
            d.where.isNotBlank() && host.where.isNotBlank() -> distance(d.where, host.where, estimate) ?: return d
            d.where.isNotBlank() -> estimate
            else -> null
        }
        return d.copy(ride = host, rideKm = km, rideFar = km != null && km > b.settings.maxDetourKm)
    }

    /** Adds a quick thing to a planned cell (or its errands trip) instead of giving it a cell. */
    fun addRide(hostStepId: String, title: String, description: String, where: String) {
        val (task, step) = BoardOps.findStep(board.value, hostStepId) ?: return
        edit { com.opslegal.tda.core.plan.Rides.add(it, hostStepId, title, description, where) }
        noticeState.value = Notice("Added to “${Planner.cellTitle(task, step)}” on ${step.date?.let { dayName(LocalDate.parse(it)) } ?: "its day"}: no extra cell.")
    }

    fun setRiderDone(stepId: String, riderId: String, done: Boolean) = edit { com.opslegal.tda.core.plan.Rides.setDone(it, stepId, riderId, done) }

    /** The cell is done; its extras too ([all]) or the open ones become small tasks of their own. */
    fun doneWithRiders(stepId: String, all: Boolean) {
        val today = LocalDate.now()
        edit { b ->
            val next = if (all) com.opslegal.tda.core.plan.Rides.allDone(b, stepId) else com.opslegal.tda.core.plan.Rides.release(b, stepId, today)
            Planner.plan(BoardOps.setStepDone(next, stepId, true), today).board
        }
        if (!all) noticeState.value = Notice("The extras not done are kept as small tasks: they get their own cell.")
    }

    /** A plan written by the AI for the project form: a short note and the steps. */
    data class DraftPlan(val note: String, val steps: List<com.opslegal.tda.core.plan.Pacing.Filled>, val intention: String = "", val serve: Map<String, Int> = emptyMap())

    /**
     * Asks the connected AI for a project's note (3 lines at most) and steps, from the user's explanation.
     * [current] is the plan being modified, if any (its steps still to do; done ones are never repeated).
     */
    suspend fun draftPlan(name: String, priority: String, deadline: String?, serves: List<String>, explain: String,
                          currentNote: String?, doneSteps: List<String>, openSteps: List<String>?): DraftPlan {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first, or use Manual.")
        val about = board.value.about
        val prompt = buildString {
            appendLine("You help a Docket 5 user plan a project. Their table has 5 cells a day; each cell is one focused block of a few hours.")
            appendLine("Today is ${LocalDate.now()}. Project: \"$name\", priority $priority${deadline?.let { ", deadline $it" } ?: ", no deadline"}.")
            deadline?.let { dl ->
                val today = LocalDate.now()
                val b = board.value
                val limit = maxOf(today, LocalDate.parse(dl).minusDays(b.settings.deadlineBufferDays.toLong()))
                val days = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(limit) }.filter { it.dayOfWeek.value in b.settings.workDays }.toList()
                val free = days.sumOf { d -> 5 - b.tasks.flatMap { it.steps }.count { it.date == d.toString() && it.slot != null } }
                appendLine("Time before the deadline: ${days.size} working day(s), $free free cell(s) now; less important cells can be moved later.")
                appendLine("The plan MUST fit before the deadline: when time is short, use fewer, bigger steps (combine small ones), at most ${maxOf(days.size * 5, 1)} steps.")
            }
            if (serves.isNotEmpty()) appendLine("It serves these values of the user: ${serves.joinToString()}.")
            if (about.easy.isNotEmpty()) appendLine("Easy or enjoyable for the user: ${about.easy.joinToString()}.")
            if (about.hard.isNotEmpty()) appendLine("They tend to put off: ${about.hard.joinToString()}.")
            if (openSteps != null) {
                appendLine("CURRENT PLAN. Note: ${currentNote.orEmpty().ifBlank { "(none)" }}")
                appendLine("Steps already done: ${doneSteps.ifEmpty { listOf("none") }.joinToString("; ")}")
                appendLine("Steps still to do, in order: ${openSteps.ifEmpty { listOf("none") }.joinToString("; ")}")
                appendLine("The user's modification:")
            } else appendLine("The user's explanation:")
            appendLine("\"\"\"$explain\"\"\"")
            appendLine("Reply with only a JSON object: {\"note\": string, \"intention\": string, " + (if (board.value.gbn) "\"serve\": {}, " else "") + "\"steps\": [{\"title\": string, \"wait_days\": number, \"why\": string, \"added\": boolean}, ...]}.")
            appendLine("- note: the explanation rewritten in clear, correct words (with the modification applied, if any), at most 4 short sentences, keeping all of the user's facts and adding none.")
            askIntentionAndServe()
            appendLine(if (openSteps != null) "- steps: the full list of steps still to do after the modification, in order; never repeat done steps; keep the exact wording of unchanged steps."
            else "- steps: 2 to 8 steps in order.")
            appendLine("Each step is one focused block of a few hours, a short title (at most 7 words); make the first one small and easy to start.")
            appendLine("Think like a project manager: when someone else must answer (a client's feedback, an approval, a signature), the next step waits:")
            appendLine("wait_days = the work days it usually takes, why = who or what it waits for (a few words), else 0 and empty.")
            appendLine("If basic steps are missing (sending a draft for feedback, integrating it, a final review), add them with added = true.")
            appendLine("Write in the language of the user's text.")
        }
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt)), emptyList()).text
        val body = reply.substring(reply.indexOf('{').coerceAtLeast(0), (reply.lastIndexOf('}') + 1).coerceAtLeast(0))
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("The plan could not be read. Try again, or use Manual.")
        val note = json["note"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val steps = (json["steps"] as? JsonArray)?.mapNotNull { e ->
            (e as? kotlinx.serialization.json.JsonObject)?.let { o ->
                val t = o["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { return@mapNotNull null }
                com.opslegal.tda.core.plan.Pacing.Filled(t, o["wait_days"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()?.coerceIn(0, 30) ?: 0,
                    o["why"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), o["added"]?.jsonPrimitive?.contentOrNull == "true")
            } ?: e.jsonPrimitive.contentOrNull?.trim()?.ifBlank { null }?.let { com.opslegal.tda.core.plan.Pacing.Filled(it) }
        }.orEmpty().take(12).let { if (openSteps == null) com.opslegal.tda.core.plan.Pacing.fill(it, explain) else it }
        return DraftPlan(note, steps, json["intention"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), readServe(json))
    }

    /** Proposals from the user's channels waiting for Apply, Discuss or Dismiss (urgent first). */
    val updates = board.map { Updates.waiting(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.waiting(board.value))

    /** The bell's one list: what arrived and waits for the user, each once. */
    val inbox = board.map { Updates.inbox(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.inbox(board.value))

    fun dismissItem(u: com.opslegal.tda.core.model.Update, how: String, comment: String, project: String?) =
        edit { Updates.dismiss(it, u.id, how, comment, project, LocalDate.now()) }

    /** "Add a task" from an item: the form is saved, so the item's change to the table is done (an answer may still wait). */
    fun itemTaskAdded(id: String) = edit { b -> b.copy(updates = b.updates.map { if (it.id == id && it.status == com.opslegal.tda.core.model.UpdateStatus.NEW) it.copy(status = com.opslegal.tda.core.model.UpdateStatus.APPLIED) else it }) }

    /** The two piles behind the bell: changes to the table, and answers to prepare. */
    val updateTasks = board.map { Updates.tasks(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.tasks(board.value))
    val updateReplies = board.map { Updates.replies(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.replies(board.value))

    /** The updates sheet is open (bell, or a tap on an updates notification). */
    val updatesOpen = MutableStateFlow(false)

    /** "Add to tasks & reply": once the task is saved, the bell opens on this item's reply. */
    val replyNext = MutableStateFlow<String?>(null)

    /** Opening the bell is the review: the bell goes back to black until the next review time. */
    fun markReviewed() = edit { it.copy(checks = it.checks.copy(lastReview = java.time.LocalDateTime.now().withNano(0).toString())) }

    private val checkingState = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = checkingState.asStateFlow()

    /** "Check now": runs a check here and says what it found. */
    fun checkUpdatesNow() = viewModelScope.launch {
        if (checkingState.value) return@launch
        checkingState.value = true
        try {
            val found = com.opslegal.tda.updates.UpdatesWorker.check(app)
            if (found == 0) noticeState.value = Notice("Nothing new that touches your table.")
        } catch (e: Exception) {
            noticeState.value = Notice("The check didn't work: ${e.message ?: "no connection"}. What arrived is kept for the next one.")
        } finally {
            checkingState.value = false
        }
    }

    /** Applies an update's changes, with the usual "project now ends..." line; or says it no longer applies. */
    fun applyUpdate(update: com.opslegal.tda.core.model.Update) {
        val today = LocalDate.now()
        val events = calendarEvents.value
        if (Updates.apply(board.value, update, today, events) == null) {
            edit { Updates.setStatus(it, update.id, com.opslegal.tda.core.model.UpdateStatus.DISMISSED) }
            noticeState.value = Notice("That update no longer applies (the item changed since).")
            return
        }
        val project = update.project.ifBlank { null }?.let { BoardOps.findProject(board.value, it)?.name }
        apply({ b -> Updates.apply(b, update, today, events) ?: b }, project, doneText = "Applied.")
    }

    /** What Apply will do, with the day each new cell lands on: shown on the card before the tap. */
    fun describeUpdate(update: com.opslegal.tda.core.model.Update): List<String> =
        Updates.describe(board.value, update, LocalDate.now(), calendarEvents.value)

    /** A meeting with no date (and not in the calendar) can't be applied: it would land on a wrong day. */
    fun canApply(update: com.opslegal.tda.core.model.Update): Boolean = update.actions.none { a ->
        a.type == "add" && a.kind == com.opslegal.tda.core.model.TaskKind.MEETING && a.date == null &&
            com.opslegal.tda.core.plan.CalendarCells.findEvent(a.title, null, calendarEvents.value) == null
    }

    fun dismissUpdate(id: String) = edit { Updates.setStatus(it, id, com.opslegal.tda.core.model.UpdateStatus.DISMISSED) }

    /** Discuss: the assistant gets the update and the proposal, and the user decides with it. */
    /** Ask AI from the weekly review: the assistant looks into it and proposes; the card stays until the user decides. */
    fun investigateUpdate(update: com.opslegal.tda.core.model.Update) =
        askAssistant("Look into this for me (${update.source}, ${update.from}${update.at.takeIf { it.isNotBlank() }?.let { ", since ${it.take(10)}" } ?: ""}): " +
            "\"${update.text.take(300)}\". Read the conversation or the email if you can. In 3 short lines: what is asked, what my table " +
            "already holds about it, and what you propose (an answer, a task, or nothing). Change nothing until I say.")

    /** Ask AI about a project from the weekly review: where it stands, what blocks it, the next step. */
    fun investigateProject(name: String, status: String, stalled: Boolean) =
        askAssistant("Look into my project $name ($status${if (stalled) ", no step done this week" else ""}). In 3 short lines: " +
            "where it stands, what blocks it, and the one next step you propose. Change nothing until I say.")

    fun discussUpdate(update: com.opslegal.tda.core.model.Update) {
        dismissUpdate(update.id)
        askAssistant("About this update (${update.source}, ${update.from}): \"${update.text.take(300)}\". " +
            "You suggested: ${update.summary} Is that the right move, and what are the alternatives?")
    }

    fun editChecks(change: (com.opslegal.tda.core.model.UpdateChecks) -> com.opslegal.tda.core.model.UpdateChecks) = viewModelScope.launch {
        val next = app.boards.update { it.copy(checks = change(it.checks)) }
        com.opslegal.tda.updates.UpdatesWorker.scheduleNext(getApplication(), next.checks)
    }

    /** The Microsoft address whose email the assistant can read, or null. */
    val microsoftAccount = app.microsoft.account

    /** Why the last Microsoft sign-in failed, shown under the button. */
    val microsoftError = MutableStateFlow<String?>(null)

    /** The browser came back and the sign-in is being finished. */
    val microsoftBusy = MutableStateFlow(false)

    fun signInMicrosoft() = runCatching { getApplication<Application>().startActivity(app.microsoft.signInIntent()) }
        .onFailure { noticeState.value = Notice("No browser found to sign in to Microsoft.") }

    /** The browser came back from the Microsoft sign-in. */
    fun finishMicrosoft(uri: android.net.Uri?) {
        if (!app.microsoft.isRedirect(uri)) return
        viewModelScope.launch {
            microsoftBusy.value = true
            microsoftError.value = null
            try {
                val address = app.microsoft.finishSignIn(uri!!)
                microsoftError.value = null
                if (repliesWizard.value == 3) repliesWizard.value = 4
                else noticeState.value = Notice("Work email connected: $address. The assistant can read it (never send), and Updates check it.")
            } catch (e: Exception) {
                microsoftError.value = e.message ?: "Microsoft sign-in failed. Try again."
            } finally {
                microsoftBusy.value = false
            }
        }
    }

    fun disconnectMicrosoft() = app.microsoft.disconnect()

    /* ---------- Replies: the assistant prepares, the user sends (Rule 1) ---------- */

    /** The reply setup's open step (1 to 4), or null. Kept here so it survives the trip to Microsoft's page. */
    val repliesWizard = MutableStateFlow<Int?>(null)

    /** Get started: the step shown (0-6, 7 = the final status), null when closed. */
    val getStarted = MutableStateFlow<Int?>(null)

    fun editReplies(change: (com.opslegal.tda.core.model.ReplySettings) -> com.opslegal.tda.core.model.ReplySettings) =
        edit { it.copy(replies = change(it.replies)) }

    /** True when Outlook drafts are allowed (the setup's Microsoft step is done). */
    fun canDraft() = app.microsoft.canDraft

    /** Opens Microsoft's page asking for "read and write mail" (drafts). Never "send". */
    fun signInForDrafts() {
        microsoftError.value = null
        runCatching { getApplication<Application>().startActivity(app.microsoft.signInIntent(drafts = true)) }
            .onFailure { microsoftError.value = "No browser found to open Microsoft's page." }
    }

    /** The setup's check: a draft the user can see in Outlook, then delete. Returns the problem, or null. */
    suspend fun testDraft(): String? = runCatching {
        app.microsoft.newDraft("Docket 5 test, you can delete me", "This draft shows Docket 5 can prepare replies in your Outlook. It never sends: you do.")
    }.exceptionOrNull()?.let { it.message ?: "The test draft didn't work. Try again." }

    /** Up to 3 times that fit the user's day for a meeting, in plain words. */
    suspend fun meetingSlots(): List<String> {
        val today = LocalDate.now()
        val calendar = com.opslegal.tda.data.PhoneCalendar(app).takeIf { settings.value.calendarAccess && it.permitted }
        val events = runCatching { calendar?.events(today, today.plusDays(10)) }.getOrNull().orEmpty()
        return com.opslegal.tda.core.plan.Slots.find(board.value, events, today, 10, 3, java.time.LocalDateTime.now()).map { it.label() }
    }

    suspend fun writeReply(
        update: com.opslegal.tda.core.model.Update, choice: com.opslegal.tda.core.agent.ReplyWriter.Choice?, slots: List<String>, current: String,
        promise: String? = null,
    ): String {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first.")
        return com.opslegal.tda.core.agent.ReplyWriter.write(provider, board.value, update, choice, slots, current, LocalDate.now(), promise)
    }

    /** The task an answer would add ("Review Sophie's lease"), when the request needs work. */
    fun workTitle(update: com.opslegal.tda.core.model.Update): String? =
        update.actions.firstOrNull { it.type == "add" }?.title?.ifBlank { null }

    /**
     * The day an "I'll get back to you" can promise: when the work lands in the table (already there, or where
     * the planner would put it). Null when there is no work or no free cell yet.
     */
    fun promiseDate(update: com.opslegal.tda.core.model.Update): LocalDate? {
        val title = workTitle(update) ?: return null
        val today = LocalDate.now()
        fun find(b: Board) = b.tasks.flatMap { it.steps }.firstOrNull { it.title.equals(title, true) && !it.done && it.date != null }
            ?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        find(board.value)?.let { return it }
        if (update.status != com.opslegal.tda.core.model.UpdateStatus.NEW) return null
        return Updates.apply(board.value, update, today)?.let { find(Planner.plan(it, today).board) }
    }

    /** Docket 5 may send the replies the user confirms in Beeper. */
    fun canSendMessages() = com.opslegal.tda.data.BeeperMessages(app).canSend

    /**
     * Sends a reply the user read and confirmed with their tap (Rule 1). With [addWork], the task behind an
     * "I'll get back to you" joins the table too. Returns the problem, or null when sent.
     */
    suspend fun sendMessage(update: com.opslegal.tda.core.model.Update, text: String, addWork: Boolean): String? {
        runCatching { com.opslegal.tda.data.BeeperMessages(app).send(update.chatId, text) }.exceptionOrNull()?.let { return it.message ?: "The message wasn't sent. Try again." }
        val today = LocalDate.now()
        val work = if (addWork && update.status == com.opslegal.tda.core.model.UpdateStatus.NEW) workTitle(update) else null
        app.boards.update { b ->
            val withWork = if (work != null) Updates.apply(b, update, today)?.let { Planner.plan(it, today).board } ?: b else b
            Updates.rememberReply(Updates.setReplied(withWork, update.id), text)
        }
        val day = work?.let { promiseDate(update) }
        noticeState.value = Notice("Sent to ${update.from}." + (work?.let { " Task added: $it" + (day?.let { d -> ", ${dayName(d)}" } ?: "") + "." } ?: ""))
        return null
    }

    /** Puts the reply in Outlook Drafts (never sends). Returns the problem, or null when saved. */
    suspend fun saveReplyDraft(update: com.opslegal.tda.core.model.Update, text: String, accepted: Boolean, addWork: Boolean = false): String? {
        val problem = runCatching { app.microsoft.saveReplyDraft(update.mailId, update.from, text) }.exceptionOrNull()
            ?: run {
                val today = LocalDate.now()
                val work = addWork && update.status == com.opslegal.tda.core.model.UpdateStatus.NEW && workTitle(update) != null
                app.boards.update { b ->
                    val withWork = if (work) Updates.apply(b, update, today)?.let { Planner.plan(it, today).board } ?: b else b
                    Updates.rememberReply(Updates.setReplied(withWork, update.id), text)
                }
                noticeState.value = Notice(
                    "Draft saved in your Outlook Drafts. Open Outlook to review and send it" +
                        (if (accepted) ", and accept the invitation there." else "."),
                )
                return null
            }
        return problem.message ?: "The draft wasn't saved. Try again."
    }

    /** Messages: the reply goes to the clipboard and Beeper opens; the user pastes, reviews and sends. */
    fun copyReplyAndOpen(update: com.opslegal.tda.core.model.Update, text: String) {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Reply", text))
        edit { Updates.rememberReply(Updates.setReplied(it, update.id), text) }
        val pm = context.packageManager
        val open = listOf("com.beeper.android", "com.whatsapp", "com.whatsapp.w4b")
            .firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (open != null) runCatching { context.startActivity(open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        noticeState.value = Notice("Reply copied. Open the chat with ${update.from}, paste, review and send.")
    }

    /** The user said OK at every step: the reply assistant is on. */
    fun finishRepliesSetup() {
        editReplies { it.copy(on = true) }
        repliesWizard.value = null
        noticeState.value = Notice("Your assistant for replies is on. Answers to prepare now wait under ✉ in the bell.")
    }

    /** Put away: "Already done" closes the whole card, "Not this time" only this pile. Neither teaches to skip anything. */
    fun putAway(update: com.opslegal.tda.core.model.Update, done: Boolean, pile: String) =
        edit { Updates.putAway(it, update.id, done, pile) }

    /** Forget the examples of how the user writes. */
    fun forgetReplyStyle() = edit { it.copy(replyStyle = emptyList()) }

    /**
     * What an "I'll get back to you" promises: before the person's deadline when they gave one, otherwise around
     * the day the work is in the table. [late] when the table has it after their deadline.
     */
    data class Promise(val text: String?, val tableDay: LocalDate?, val late: Boolean)

    fun promiseFor(update: com.opslegal.tda.core.model.Update): Promise {
        val planned = promiseDate(update)
        val due = update.due.ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val text = when {
            due != null -> "before ${dayName(due)} (their deadline)"
            planned != null -> "around ${dayName(planned)}"
            else -> null
        }
        return Promise(text, planned, due != null && planned != null && planned.isAfter(due))
    }

    /* ---------- Calendar events in the day ---------- */

    private val calendarState = MutableStateFlow<List<com.opslegal.tda.core.agent.CalendarEvent>>(emptyList())

    /** The phone calendar's events for the days the table shows (empty without calendar access). */
    val calendarEvents: StateFlow<List<com.opslegal.tda.core.agent.CalendarEvent>> = calendarState.asStateFlow()

    fun loadCalendar() = viewModelScope.launch {
        val calendar = com.opslegal.tda.data.PhoneCalendar(app).takeIf { settings.value.calendarAccess && it.permitted }
        val today = LocalDate.now()
        calendarState.value = runCatching { calendar?.events(today, today.plusDays(45)) }.getOrNull().orEmpty()
        app.boards.update { withReserved(it) }
    }

    /** Calendar events not reviewed yet keep their cells free for the planner. */
    private fun withReserved(b: Board): Board {
        val reserved = com.opslegal.tda.core.plan.CalendarCells.reserved(b, calendarState.value)
        return if (reserved == b.reserved) b else b.copy(reserved = reserved)
    }

    /* ---------- Weekly review ---------- */

    /** The week waiting for its review (Friday 1 pm to Monday noon), with its facts; null otherwise. */
    /** This week so far, for "How is my week going?" (any day, without waiting for Friday). */
    fun weekSoFar(): Pair<LocalDate, com.opslegal.tda.core.agent.WeekReview.Facts> {
        val today = LocalDate.now()
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        return monday to com.opslegal.tda.core.agent.WeekReview.facts(board.value, monday, today)
    }

    /** Habits: how earlier fixes went (checked when the review opens). */
    fun trackHabits() = edit { com.opslegal.tda.core.plan.Habits.track(it) }

    /** The fix the user chose for a habit, applied now and tracked at the next reviews. */
    fun applyHabit(taskId: String, cause: String, words: String) = viewModelScope.launch {
        var fix: com.opslegal.tda.core.model.HabitFix? = null
        app.boards.update { b -> com.opslegal.tda.core.plan.Habits.apply(b, taskId, cause, words, LocalDate.now())?.let { (nb, h) -> fix = h; nb } ?: b }
        fix?.let { noticeState.value = Notice("Done: ${it.fix}. I'll check with you at the next review how it went.") }
    }

    fun weekToReview(): Pair<LocalDate, com.opslegal.tda.core.agent.WeekReview.Facts>? {
        val now = java.time.LocalDateTime.now()
        if (!com.opslegal.tda.core.agent.WeekReview.due(board.value, now)) return null
        val monday = com.opslegal.tda.core.agent.WeekReview.weekToReview(now) ?: return null
        return monday to com.opslegal.tda.core.agent.WeekReview.facts(board.value, monday, now.toLocalDate())
    }

    /**
     * The review's first part: the last month's unanswered chats and unread emails that still hold a request go to the
     * bell. Runs once a week (again with [force]); returns everything waiting in the bell afterwards.
     */
    suspend fun sweepMonth(monday: LocalDate, force: Boolean = false): List<com.opslegal.tda.core.model.Update> {
        if (force || board.value.review.swept != monday.toString()) {
            com.opslegal.tda.updates.UpdatesWorker.sweep(app)
            edit { it.copy(review = it.review.copy(swept = monday.toString())) }
        }
        return Updates.waiting(board.value)
    }

    suspend fun weekAdvice(f: com.opslegal.tda.core.agent.WeekReview.Facts): com.opslegal.tda.core.agent.WeekReview.Advice {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first.")
        return com.opslegal.tda.core.agent.WeekReview.advise(provider, board.value, f)
    }

    fun tryAdvice(monday: LocalDate, f: com.opslegal.tda.core.agent.WeekReview.Facts, a: com.opslegal.tda.core.agent.WeekReview.Advice) {
        edit { com.opslegal.tda.core.agent.WeekReview.accept(it, monday, f, a) }
        noticeState.value = Notice("Added to your Playbook rules. Next week's review will tell you if it helped.")
    }

    fun declineAdvice(monday: LocalDate, f: com.opslegal.tda.core.agent.WeekReview.Facts, a: com.opslegal.tda.core.agent.WeekReview.Advice) {
        edit { com.opslegal.tda.core.agent.WeekReview.decline(it, monday, f, a) }
        noticeState.value = Notice("Noted. It won't be suggested again.")
    }

    /* ---------- On my mind ---------- */

    /** Adds what was typed; several lines (or "a; b") make several items. */
    fun addToMind(text: String) = edit { b ->
        b.copy(mind = b.mind + text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { com.opslegal.tda.core.model.MindItem(BoardOps.newId(), it) })
    }

    fun removeFromMind(id: String) = edit { b -> b.copy(mind = b.mind.filter { it.id != id }) }

    /** The assistant sorts the list (nothing changes yet). */
    suspend fun sortMind(): List<com.opslegal.tda.core.agent.OnMyMind.Sorted> {
        val provider = app.settings.provider() ?: error("Connect your AI in Settings first.")
        return com.opslegal.tda.core.agent.OnMyMind.sort(provider, board.value, LocalDate.now())
    }

    /** What placing would give, before the tap: the day each item lands on (or what it becomes). */
    fun previewMind(items: List<com.opslegal.tda.core.agent.OnMyMind.Sorted>): Map<String, LocalDate?> {
        val after = com.opslegal.tda.core.agent.OnMyMind.place(board.value, items, java.time.LocalDateTime.now())
        val existing = board.value.tasks.flatMap { t -> t.steps.map { it.id } }.toSet()
        val newSteps = after.tasks.flatMap { t -> t.steps.map { t to it } }.filter { (_, s) -> s.id !in existing }
        return items.associate { it ->
            val match = newSteps.firstOrNull { (t, s) -> s.title == it.title || (t.title.startsWith("Quick things") && t.description.contains(it.title)) }
            it.text to match?.second?.date?.let(LocalDate::parse)
        }
    }

    fun placeMind(items: List<com.opslegal.tda.core.agent.OnMyMind.Sorted>) {
        edit { com.opslegal.tda.core.agent.OnMyMind.place(it, items, java.time.LocalDateTime.now()) }
        noticeState.value = Notice("What was on your mind is in your table now.")
    }

    fun parkProject(name: String) {
        edit { com.opslegal.tda.core.plan.Projects.park(it, name, LocalDate.now()) }
        noticeState.value = Notice("$name is parked with your ideas. Its cells are free again.")
    }

    fun resumeProject(name: String) {
        val today = LocalDate.now()
        val end = com.opslegal.tda.core.plan.Projects.resume(board.value, name, today).let { Projects.end(it, name).end }
        edit { com.opslegal.tda.core.plan.Projects.resume(it, name, today) }
        noticeState.value = Notice("$name is back: it now ends ${end?.let(::dayName) ?: "when cells free up"}.")
    }

    /** What adding an event would do, before the choice: cells left that day and the work that moves. */
    data class CalendarPreview(val cells: Int, val left: Int, val moved: List<String>)

    fun previewCalendar(e: com.opslegal.tda.core.agent.CalendarEvent): CalendarPreview {
        val today = LocalDate.now()
        val before = board.value
        val after = com.opslegal.tda.core.plan.CalendarCells.add(before, e, today)
        val was = before.tasks.flatMap { it.steps }.associate { it.id to it.date }
        val moved = after.tasks.flatMap { t -> t.steps.map { t to it } }
            .filter { (_, s) -> !s.done && was[s.id] != null && was[s.id] != s.date }
            .map { (t, s) -> "“${if (t.project.isNotBlank()) "${t.project}: ${s.title}" else s.title}” → ${s.date?.let { dayName(LocalDate.parse(it)) } ?: "no free day yet"}" }
        val day = com.opslegal.tda.core.plan.CalendarCells.date(e)?.toString()
        val used = after.tasks.flatMap { it.steps }.count { it.date == day && it.slot != null && it.outcome == null }
        return CalendarPreview(com.opslegal.tda.core.plan.CalendarCells.cells(e), (5 - used).coerceAtLeast(0), moved)
    }

    fun addCalendarEvent(e: com.opslegal.tda.core.agent.CalendarEvent) {
        val moved = previewCalendar(e).moved
        edit { withReserved(com.opslegal.tda.core.plan.CalendarCells.add(it, e, LocalDate.now())) }
        noticeState.value = Notice("${e.title} is in your day." + if (moved.isNotEmpty()) " Moved: ${moved.joinToString("; ")}." else "")
    }

    fun leaveOutCalendarEvent(e: com.opslegal.tda.core.agent.CalendarEvent) =
        edit { withReserved(com.opslegal.tda.core.plan.CalendarCells.leaveOut(it, e, LocalDate.now())) }

    /** "Leave the rest out": every event of the day not decided yet, in one tap. */
    fun leaveOutCalendarEvents(events: List<com.opslegal.tda.core.agent.CalendarEvent>) = edit { b ->
        withReserved(events.fold(b) { acc, e -> com.opslegal.tda.core.plan.CalendarCells.leaveOut(acc, e, LocalDate.now()) })
    }

    fun takeOutCalendarEvent(e: com.opslegal.tda.core.agent.CalendarEvent) =
        edit { com.opslegal.tda.core.plan.CalendarCells.takeOut(it, e, LocalDate.now()) }

    /** "No reply needed": it leaves the Replies pile. */
    fun skipReply(id: String) = edit { Updates.setReplied(it, id) }

    /* ---------- To buy ---------- */

    /** The To buy sheet is open. */
    val cartOpen = MutableStateFlow(false)

    /** The line under the cart's question: what was heard, then what was added. */
    val buyStatus = MutableStateFlow<String?>(null)

    fun addToBuy(texts: List<String>, work: Boolean, needBy: String? = null) =
        edit { com.opslegal.tda.core.plan.Shopping.add(it, texts, work, needBy).first }

    fun setBought(id: String, done: Boolean) = edit { com.opslegal.tda.core.plan.Shopping.setDone(it, id, done) }

    fun removeToBuy(id: String) = edit { com.opslegal.tda.core.plan.Shopping.remove(it, id) }

    fun clearBought() = edit { com.opslegal.tda.core.plan.Shopping.clearBought(it) }

    /** One Errands cell for everything still to buy. */
    fun planTrip() = viewModelScope.launch {
        val today = LocalDate.now()
        var id: String? = null
        val next = app.boards.update { b ->
            val trip = com.opslegal.tda.core.plan.Shopping.planTrip(b, today) ?: return@update b
            id = trip.second
            Planner.plan(trip.first, today).board
        }
        val t = id?.let { tid -> next.tasks.firstOrNull { it.id == tid } } ?: return@launch
        val day = t.steps.firstOrNull()?.date?.let(LocalDate::parse)
        noticeState.value = Notice("Errands planned" + (day?.let { " on ${dayName(it)}" } ?: "") + ": ${next.buy.count { it.errand == t.id }} things to buy.")
    }

    /** The cart's mic: listen, let the AI split what was said into items (home or work, by when), and add them. */
    fun listenToBuy() {
        if (voiceState.value is VoiceState.Listening) return voice.finish()
        buyStatus.value = "Listening…"
        voice.listen(board.value.conversation) { heard ->
            buyStatus.value = "“$heard” · sorting it…"
            viewModelScope.launch { addSpokenToBuy(heard) }
        }
    }

    /** Typed text with the mic button (no speech): sorted the same way. */
    fun addSpokenToBuy(heard: String) = viewModelScope.launch {
        data class Item(val text: String, val work: Boolean, val needBy: String?)
        val today = LocalDate.now()
        val items = runCatching {
            val provider = app.settings.provider() ?: error("no AI")
            val prompt = "The user is adding to their shopping list. Today is $today. They said:\n\"\"\"$heard\"\"\"\n" +
                "Reply with only {\"items\":[{\"text\": string, \"work\": boolean, \"needed_by\": string}]}: one entry per thing to buy, " +
                "short (e.g. \"Printer toner\", \"Lait 2 L\"), in their language; work=true for office or client things; " +
                "needed_by = YYYY-MM-DD only if they said when, else \"\"."
            val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt)), emptyList()).text
            val json = kotlinx.serialization.json.Json.parseToJsonElement(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)).jsonObject
            (json["items"] as JsonArray).map { el ->
                val o = el.jsonObject
                Item(
                    o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["work"]?.jsonPrimitive?.contentOrNull == "true",
                    o["needed_by"]?.jsonPrimitive?.contentOrNull?.takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess },
                )
            }
        }.getOrElse {
            // Without the AI: split on commas and "and" / "et".
            heard.split(Regex(",|;| and | et ", RegexOption.IGNORE_CASE)).map { Item(it.trim(), false, null) }
        }.filter { it.text.isNotBlank() }
        val added = mutableListOf<String>()
        app.boards.update { b ->
            items.fold(b) { acc, i ->
                com.opslegal.tda.core.plan.Shopping.add(acc, listOf(i.text), i.work, i.needBy).also { added += it.second }.first
            }
        }
        buyStatus.value = if (added.isEmpty()) "Nothing new to add." else "Added: ${added.joinToString()}."
    }

    fun editConversation(change: (ConversationSettings) -> ConversationSettings) =
        edit { it.copy(conversation = change(it.conversation)) }

    fun resetRules() = edit { b -> b.copy(rules = DefaultRules.all) }

    private val pageState = MutableStateFlow<AssistantPage?>(null)

    /** The page the assistant was opened from (Progress, Playbook, Settings), or null for the general assistant. */
    val page: StateFlow<AssistantPage?> = pageState.asStateFlow()

    fun openAssistantFor(page: AssistantPage?) {
        pageState.value = page
        if (page != null) assistantRequests.value++
    }

    /** Bumped when some screen wants the Assistant tab shown. */
    val assistantRequests = MutableStateFlow(0)

    private val prefillState = MutableStateFlow<String?>(null)

    /** Text to put in the assistant's message box (the user completes it). */
    val prefill: StateFlow<String?> = prefillState.asStateFlow()

    fun consumePrefill(): String? = prefillState.value.also { prefillState.value = null }

    /** Opens the assistant with a message: sent at once, or left to complete when it ends with ":". */
    fun askAssistant(text: String, page: AssistantPage? = null) {
        pageState.value = page
        assistantRequests.value++
        if (text.trimEnd().endsWith(":")) prefillState.value = text else send(text)
    }

    /** Facts about the page the app knows and the board doesn't (settings live outside the board). */
    private fun pageFacts(page: AssistantPage?): String {
        if (page != AssistantPage.SETTINGS) return ""
        val s = settings.value
        val m = board.value.meetings
        val c = board.value.conversation
        val u = board.value.checks
        return "SETTINGS: AI=${if (s.hasApiKey) s.provider.name.lowercase() else "not connected"}, confirm changes=${c.confirmation}, " +
            "day letters=${s.dayLanguage}, theme=${s.theme}, morning review=${s.dailyAiReview}, calendar=${s.calendarAccess}, messages=${s.messagesAccess}.\n" +
            "Meeting hours: days ${m.days}, ${m.windows.joinToString(" and ")}, ${m.durationMinutes} min, max ${m.maxPerDay}/day, ${m.bufferMinutes} min break." +
            "\nUpdate checks: on open=${u.onOpen}, on leave=${u.onLeave}, at ${u.times.joinToString()}; reads messages=${u.messages}, " +
            "notifications=${u.notifications}; urgent = ${listOfNotNull("due today/tomorrow".takeIf { u.urgentToday }, "blocks a project".takeIf { u.urgentBlocks }, "key contacts".takeIf { u.urgentKey }).joinToString()}; protect focus=${u.focus}."
    }

    /** Changes the assistant understood and is waiting for a yes on. */
    val pending = app.agentState.pending

    /** A message the assistant wrote for the user to send (offered meeting times...). */
    val draft = app.agentState.draft

    fun clearDraft() = app.agentState.setDraft(null)

    /** First launch: one tap sets starter values for the user's kind of work ("none" skips). */
    /** Ground · Build · Nourish trial on or off (Settings). Off brings back the values used before. */
    fun setGbn(on: Boolean) = edit { if (on) com.opslegal.tda.core.plan.Gbn.turnOn(it) else com.opslegal.tda.core.plan.Gbn.turnOff(it) }

    /**
     * The user set what a task serves (cell menu or form): saved on the task, or on its project for a project step,
     * and kept as a lesson when it differs from what was proposed.
     */
    fun saveLevels(taskId: String, levels: Map<String, Int>, title: String, learn: Boolean) = edit { b ->
        val task = b.tasks.firstOrNull { it.id == taskId } ?: return@edit b
        val clean = levels.filterValues { it > 0 }
        var next = if (task.isProject) {
            val p = BoardOps.findProject(b, task.project)
            if (p == null) b else BoardOps.saveProject(b, p.copy(serve = clean, values = (p.values + clean.keys).distinct()), p.name)
        } else BoardOps.updateTask(b, taskId) { it.copy(serve = clean, values = (it.values + clean.keys).distinct()) }
        if (learn) next = com.opslegal.tda.core.plan.Gbn.learn(next, title, clean)
        next
    }

    /** Remembers a correction made in a form (task or project) before it is saved. */
    fun learnLevels(title: String, levels: Map<String, Int>) = edit { com.opslegal.tda.core.plan.Gbn.learn(it, title, levels) }

    fun chooseProfile(id: String) = edit { b ->
        // Presets work like an equalizer's: each keeps its own adjustments; Custom starts as a copy of what is shown.
        val current = b.about.profile
        val sets = if (current != null && current != "none") b.valueSets + (current to b.values) else b.valueSets
        if (id == "none") return@edit b.copy(valueSets = sets, about = b.about.copy(profile = "none"))
        val values = sets[id] ?: if (id == CUSTOM) b.values else (Values.profiles[id] ?: com.opslegal.tda.core.plan.Gbn.profiles[id])?.second.orEmpty()
        b.copy(values = values, valueSets = sets, about = b.about.copy(profile = id))
    }

    /** The 60-second voice intro: what matters, what's easy, what gets put off. */
    fun listenAbout() {
        if (voiceState.value is VoiceState.Listening) return voice.finish()
        lastReplyState.value = null
        voice.listen(board.value.conversation) { heard -> saveBio(heard); send("ABOUT ME: $heard", spoken = true) }
    }

    /** Typed "about you": kept as their bio, and the assistant proposes what matters from it (they confirm). */
    fun aboutMe(text: String) {
        saveBio(text)
        askAssistant("ABOUT ME: $text")
    }

    private fun saveBio(text: String) = edit { b -> b.copy(about = b.about.copy(bio = text.trim().take(1500))) }

    fun markWelcomed() = edit { b -> b.copy(about = b.about.copy(welcomed = true)) }

    /** One JSON answer from the connected AI (null without an AI or when it can't be read). */
    suspend fun askJson(prompt: String): kotlinx.serialization.json.JsonObject? {
        val provider = app.settings.provider() ?: return null
        val reply = runCatching { provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt)), emptyList()).text }.getOrNull() ?: return null
        val body = reply.substring(reply.indexOf('{').coerceAtLeast(0), (reply.lastIndexOf('}') + 1).coerceAtLeast(0))
        return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    fun hasAi(): Boolean = app.settings.provider() != null

    // ---- Tags: "I am…" (each attribute weighs the highest of its tags; the strip's own weights win)
    fun toggleTag(tag: String) = editAndPlan { b -> com.opslegal.tda.core.plan.Tags.toggle(if (b.gbn) b else com.opslegal.tda.core.plan.Gbn.turnOn(b), tag) }

    /** A bar tapped on the profile strip: the user's own weight for it, kept over the tags. */
    fun setWeight(name: String, n: Int) = editAndPlan { b ->
        val now = b.values.firstOrNull { it.name == name }?.weight ?: 0
        com.opslegal.tda.core.plan.Tags.apply(b.copy(adjust = b.adjust + (name to if (now == n && n > 1) n - 1 else n)))
    }

    fun clearAdjust() = editAndPlan { com.opslegal.tda.core.plan.Tags.apply(it.copy(adjust = emptyMap())) }

    data class FoundTags(val tags: List<String>, val custom: Map<String, Map<String, Int>>)

    /** A few lines about the person become tags (and up to 3 of their own), shown before they apply. */
    suspend fun findTags(text: String): FoundTags {
        saveBio(text)
        val b = board.value
        val names = com.opslegal.tda.core.plan.Tags.presets.keys
        val json = askJson(buildString {
            appendLine("From what this person says about themselves, pick tags among ${names.joinToString()} and create up to 3 new short tags of their own")
            appendLine("for what the list misses (e.g. Marathoner, Musician), each with weights 1-3 for: ${b.values.joinToString { it.name }.ifBlank { "Home, Admin, Career, Money, Invest, Relations, Health, Joy" }}.")
            appendLine("Reply with only {\"tags\": [string], \"custom\": [{\"t\": string, \"w\": {\"Attribute\": number}}]}.")
            appendLine("They wrote: \"\"\"$text\"\"\"")
        })
        if (json == null) {
            val t = text.lowercase()
            val local = mapOf("Parent" to listOf("kid", "child", "enfant", "parent", "daughter", "son "), "Founder" to listOf("founder", "fondateur", "my company", "startup"),
                "Investor" to listOf("invest", "real estate", "immobilier"), "Lawyer" to listOf("lawyer", "avocat", "law firm", "counsel"),
                "Employee" to listOf("employee", "employé", "work at", "job"), "Passionate" to listOf("passion", "love", "music", "art", "marathon"))
            return FoundTags(local.filter { (_, w) -> w.any { it in t } }.keys.toList(), emptyMap())
        }
        val tags = (json["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }?.filter { it in names }.orEmpty()
        val custom = (json["custom"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val t = o["t"]?.jsonPrimitive?.contentOrNull?.trim()?.take(20)?.ifBlank { null } ?: return@mapNotNull null
            val w = (o["w"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.toDoubleOrNull()?.toInt()?.coerceIn(1, 3)?.let { k to it } }?.toMap().orEmpty()
            if (w.isEmpty()) null else t to w
        }?.take(3)?.toMap().orEmpty()
        return FoundTags(tags, custom)
    }

    fun useTags(found: FoundTags) = editAndPlan { b0 ->
        val b = if (b0.gbn) b0 else com.opslegal.tda.core.plan.Gbn.turnOn(b0)
        com.opslegal.tda.core.plan.Tags.apply(b.copy(gbn = true, customTags = b.customTags + found.custom, tags = (b.tags + found.tags + found.custom.keys).distinct()))
    }

    // ---- Routines
    fun saveRoutine(r: com.opslegal.tda.core.model.Routine, fromWish: String? = null) = edit { b ->
        val serve = r.serve.ifEmpty {
            if (Regex("tennis|gym|run|swim|yoga|sport|hike|walk|bike|crossfit|soccer|hockey|ski|pilates|course|marche|vélo|natation", RegexOption.IGNORE_CASE).containsMatchIn(r.title))
                mapOf("Health" to 3, "Joy" to 1) else com.opslegal.tda.core.plan.Gbn.lessonFor(b, r.title)?.serve.orEmpty()
        }
        val fixed = r.copy(serve = serve)
        b.copy(
            routines = if (b.routines.any { it.id == r.id }) b.routines.map { if (it.id == r.id) fixed else it } else b.routines + fixed,
            routineWishes = if (fromWish != null) b.routineWishes.filter { it.id != fromWish } else b.routineWishes,
        )
    }

    fun deleteRoutine(id: String) = edit { b -> b.copy(routines = b.routines.filter { it.id != id }) }
    fun addWish(title: String) = edit { b -> b.copy(routineWishes = b.routineWishes + com.opslegal.tda.core.model.RoutineWish(BoardOps.newId(), title.trim())) }
    fun removeWish(id: String) = edit { b -> b.copy(routineWishes = b.routineWishes.filter { it.id != id }) }
    fun declineHint(title: String) = edit { b -> b.copy(routineNo = b.routineNo + title.lowercase()) }
    fun routineWentFine(key: String) = edit { b -> b.copy(routineFine = b.routineFine + key) }

    /** Suggested routines: calendar events repeating on the same weekday, and tasks the user keeps creating. */
    fun routineHints(): List<com.opslegal.tda.core.plan.Routines.Hint> {
        val b = board.value
        val events = calendarEvents.value.filter { !it.allDay }.mapNotNull { e ->
            val t = runCatching { java.time.LocalDateTime.parse(e.start) }.getOrNull() ?: return@mapNotNull null
            val m = when (t.hour) { in 0..6 -> com.opslegal.tda.core.model.Moment.EARLY; in 7..10 -> com.opslegal.tda.core.model.Moment.MORNING
                in 11..13 -> com.opslegal.tda.core.model.Moment.MIDDAY; in 14..17 -> com.opslegal.tda.core.model.Moment.AFTERNOON; else -> com.opslegal.tda.core.model.Moment.EVENING }
            Triple(e.title, t.toLocalDate(), m)
        }
        return com.opslegal.tda.core.plan.Routines.calendarHints(b, events) + com.opslegal.tda.core.plan.Routines.taskHints(b, LocalDate.now())
    }

    fun useHint(h: com.opslegal.tda.core.plan.Routines.Hint) =
        saveRoutine(com.opslegal.tda.core.model.Routine(BoardOps.newId(), h.title, h.days, h.moment))

    // ---- Assistant flags (the panel's "From your assistant" rows)
    val flags = board.map { com.opslegal.tda.core.plan.Flags.all(it, LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.opslegal.tda.core.plan.Flags.all(board.value, LocalDate.now()))

    /** Runs a flag's action; returns true when the panel can stay open. */
    fun flagAction(key: String, title: String): Boolean {
        val today = LocalDate.now()
        val k = key.substringBefore(':'); val id = key.substringAfter(':')
        when (k) {
            "now" -> apply({ com.opslegal.tda.core.plan.Flags.doToday(it, id, today) }, doneText = "“$title” is in today. One less thing waiting.")
            "sooner" -> apply({ com.opslegal.tda.core.plan.Flags.bringForward(it, id, today) }, projectName = id)
            "keep" -> edit { b -> b.copy(keepDates = (b.keepDates + id).distinct()) }
            "split" -> { askAssistant("“$title” keeps getting pushed. Help me make the first step smaller: ask me one question, then propose a 30-minute first step. Change nothing until I say."); return false }
            "talk" -> { askAssistant("Let's talk about “$title”: I keep pushing it. Ask me why, one question at a time, then suggest what to do. Change nothing until I say."); return false }
            "pai" -> { askAssistant("Look into my project $id: its intention, where it stands, and what to change to meet the deadline. Change nothing until I say."); return false }
            "addone" -> { askAssistant("My week has no Nourish cell (people, health, joy). Propose one that fits a free cell this week. Change nothing until I say."); return false }
        }
        return true
    }

    fun flagNotNow(id: String, title: String, why: String) = edit { com.opslegal.tda.core.plan.Flags.notNow(it, id, title, why, LocalDate.now()) }

    // ---- Get started
    fun setSetup(change: (com.opslegal.tda.core.model.SetupState) -> com.opslegal.tda.core.model.SetupState) = edit { b -> b.copy(setup = change(b.setup)) }

    /** "Let me do my magic": last month's open messages and requests come back to the bell, as proposals. */
    fun doMagic() = viewModelScope.launch {
        runCatching { sweepMonth(LocalDate.now().with(java.time.DayOfWeek.MONDAY), force = true) }
        checkUpdatesNow()
        edit { b -> b.copy(setup = b.setup.copy(magic = true)) }
    }

    init {
        // The phone's languages (e.g. Français (Canada)) are offered to the voice from the start; Settings can change it.
        viewModelScope.launch {
            val b = app.boards.read()
            // Holidays follow the phone's region until the user picks one in Settings.
            if (b.settings.holidays == "?") {
                val l = java.util.Locale.getDefault()
                val region = com.opslegal.tda.core.plan.Holidays.guess(l.language, l.country)
                app.boards.update { it.copy(settings = it.settings.copy(holidays = region)) }
            }
            if (b.conversation.otherLanguages.isEmpty()) {
                val locales = android.os.LocaleList.getDefault()
                val phone = (0 until locales.size()).map { locales[it] }
                val offered = com.opslegal.tda.core.voice.LanguageGuess.offered
                val extra = phone.mapNotNull { l ->
                    offered.firstOrNull { it.equals(l.toLanguageTag(), ignoreCase = true) }
                        ?: offered.firstOrNull { it.substringBefore('-') == l.language && it.endsWith(l.country.ifBlank { "CA" }) }
                        ?: offered.firstOrNull { it.substringBefore('-') == l.language }
                }.filter { it.substringBefore('-') != b.conversation.voiceLanguage.substringBefore('-') }.distinct()
                if (extra.isNotEmpty()) app.boards.update { it.copy(conversation = it.conversation.copy(otherLanguages = extra)) }
            }
        }
    }

    val voice = VoiceController(application)
    val voiceState = voice.state

    /**
     * Handles a message, typed or spoken. When changes are waiting for confirmation, a
     * clear yes applies them and a clear no drops them without calling the AI.
     */
    fun send(text: String, spoken: Boolean = false) {
        val message = text.trim()
        if (message.isEmpty()) return
        if (busyState.value) {
            if (spoken) voice.speak("One moment, I'm still working on the last request.", board.value.conversation) {}
            return
        }
        val talk = board.value.conversation
        if (pending.value.isNotEmpty()) {
            when (ReplyClassifier.classify(message, talk)) {
                Reply.YES -> return confirm(message, spoken)
                Reply.NO -> if (message.split(" ").size <= 2) return reject(message, spoken)
                Reply.OTHER -> Unit
            }
        }
        val agent = app.agent()
        if (agent == null) {
            errorState.value = "Connect your AI account in Settings first."
            return
        }
        val page = pageState.value
        launchTurn(spoken) {
            agent.send(app.chat.items.value, message, spoken, onItem = { app.chat.append(it) }, page = page, pageFacts = pageFacts(page))
        }
    }

    /** The "Yes, do it" button or a spoken yes. */
    fun confirm(text: String = "Yes, do it.", spoken: Boolean = false) {
        if (busyState.value) return
        val agent = app.agent() ?: return
        launchTurn(spoken) { agent.confirm(text).onEach { app.chat.append(it) } }
    }

    /** The "No" button or a spoken no: nothing changes, the assistant asks what was meant. */
    fun reject(text: String = "No.", spoken: Boolean = false) {
        if (busyState.value) return
        app.agentState.clear()
        launchTurn(spoken) {
            listOf(
                ChatItem.User(text),
                ChatItem.Assistant("OK, I changed nothing. What did you mean?", provider = "local"),
            ).onEach { app.chat.append(it) }
        }
    }

    /**
     * The mic button: start listening, or finish the turn now, or interrupt the voice.
     * With [forDay], what the user says is about that day of the table (they tapped it).
     */
    fun micTapped(forDay: LocalDate? = null) {
        when (voiceState.value) {
            is VoiceState.Listening -> voice.finish()
            else -> listen(forDay)
        }
    }

    private val lastReplyState = MutableStateFlow<String?>(null)

    /** The last spoken answer, shown as subtitles under the table. */
    val lastReply: StateFlow<String?> = lastReplyState.asStateFlow()

    fun dismissReply() {
        lastReplyState.value = null
    }

    fun switchLanguage(tag: String) = voice.switchLanguage(tag)

    /** One tap from "French isn't installed" to installing it. */
    fun installVoiceLanguage(tag: String) = voice.installLanguage(tag)

    fun stopVoice() {
        voice.cancel()
        voice.stopSpeaking()
    }

    private fun listen(forDay: LocalDate? = null) {
        lastReplyState.value = null
        voice.listen(board.value.conversation) { heard ->
            val message = if (forDay != null) "For ${forDay.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $forDay: $heard" else heard
            send(message, spoken = true)
        }
    }

    private fun launchTurn(spoken: Boolean, block: suspend () -> List<ChatItem>) {
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            try {
                val items = block()
                if (spoken) answerAloud(items)
            } catch (e: Exception) {
                errorState.value = e.message ?: "Something went wrong."
                if (spoken) voice.speak(errorState.value!!, board.value.conversation) {}
            } finally {
                busyState.value = false
            }
        }
    }

    /**
     * Reads the answer aloud, then listens again when a reply is expected: a question,
     * changes to confirm, or hands-free mode.
     */
    private fun answerAloud(items: List<ChatItem>) {
        val talk = board.value.conversation
        val reply = (items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
        lastReplyState.value = reply.ifBlank { null }
        val expectsAnswer = talk.handsFree || pending.value.isNotEmpty() || reply.trim().endsWith("?")
        val next = { if (expectsAnswer) listen() }
        if (talk.speakReplies) voice.speak(reply, talk, next) else next()
    }

    override fun onCleared() {
        voice.release()
    }

    fun clearChat() = viewModelScope.launch { app.chat.clear() }

    fun dismissError() {
        errorState.value = null
    }

    fun updateSettings(change: (com.opslegal.tda.data.AppSettings) -> com.opslegal.tda.data.AppSettings) = app.settings.update(change)

    fun setApiKey(key: String?) = app.settings.setApiKey(key)

    /**
     * The setup's Test: a one-line call with a key that is not saved yet. Null when the AI answered,
     * otherwise what went wrong, in plain words.
     */
    suspend fun testKey(kind: com.opslegal.tda.data.ProviderKind, key: String): String? {
        val provider = app.settings.providerFor(kind, key.trim()) ?: return "This AI can't be tested here."
        return try {
            provider.complete("Reply with OK.", listOf(ChatItem.User("Say OK.")), emptyList())
            null
        } catch (e: com.opslegal.tda.core.agent.LlmException) {
            when (e.statusCode) {
                401, 403 -> "The key was refused. Copy it again, the whole line, and paste it here."
                402 -> "Your account has no credit yet. Add some in Billing, then test again."
                429 -> "Your account is out of credit or busy. Check Billing, or try again in a minute."
                400 -> if (e.message.orEmpty().contains("credit", ignoreCase = true)) "Your account has no credit yet. Add some in Billing, then test again."
                else e.message ?: "The AI refused the test."
                else -> e.message ?: "The AI didn't answer. Try again."
            }
        } catch (e: java.io.IOException) {
            "No connection. Check the internet on this phone and test again."
        } catch (e: Exception) {
            e.message ?: "The test failed. Try again."
        }
    }

    /** Saves a tested key and switches the assistant to that AI. */
    fun connectAi(kind: com.opslegal.tda.data.ProviderKind, key: String) {
        app.settings.update { it.copy(provider = kind, model = kind.defaultModel) }
        app.settings.setApiKey(key)
    }

    fun buy(activity: android.app.Activity, offer: com.opslegal.tda.billing.BillingRepository.Offer) = app.billing.buy(activity, offer)
}

/** The "Custom" preset in the Playbook. */
const val CUSTOM = "custom"
