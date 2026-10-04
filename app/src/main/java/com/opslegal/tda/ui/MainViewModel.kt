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
                values = spec.values,
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
    )

    /** Asks the connected AI to fill the task form from the user's own words. */
    suspend fun draftTask(describe: String, current: com.opslegal.tda.core.model.Task? = null, currentDay: LocalDate? = null): DraftTask {
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
                appendLine("Change the task as the user asks; keep everything else exactly as it is. Prefer a day with a free cell.")
                appendLine("Also give \"reason\": one short sentence saying what you changed and why.")
                appendLine("The user's change request:")
            } else appendLine("The user's words:")
            appendLine("\"\"\"$describe\"\"\"")
            appendLine("Reply with only a JSON object: {\"title\": string, \"notes\": string, \"type\": \"task\"|\"meeting\"|\"deadline\", " +
                "\"effort\": \"light\"|\"normal\"|\"heavy\", \"project\": string, \"day\": string}.")
            appendLine("- title: at most 6 words, what the cell shows.")
            appendLine("- notes: 1 or 2 short sentences of context, from the user's words only.")
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
        return DraftTask(
            title = str("title").ifBlank { describe.trim().take(40) },
            notes = str("notes").ifBlank { describe.trim() },
            kind = when (str("type").lowercase()) { "meeting" -> com.opslegal.tda.core.model.TaskKind.MEETING; "deadline" -> com.opslegal.tda.core.model.TaskKind.DEADLINE; else -> com.opslegal.tda.core.model.TaskKind.TASK },
            effort = when (str("effort").lowercase()) { "light" -> com.opslegal.tda.core.model.Effort.LIGHT; "heavy" -> com.opslegal.tda.core.model.Effort.HEAVY; else -> com.opslegal.tda.core.model.Effort.NORMAL },
            project = str("project").let { p -> b.projects.firstOrNull { it.name.equals(p, ignoreCase = true) }?.name.orEmpty() },
            day = runCatching { LocalDate.parse(str("day")) }.getOrNull()?.takeIf { !it.isBefore(today) },
            reason = str("reason"),
        )
    }

    /** A plan written by the AI for the project form: a short note and the steps. */
    data class DraftPlan(val note: String, val steps: List<String>)

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
            appendLine("Reply with only a JSON object: {\"note\": string, \"steps\": [string, ...]}.")
            appendLine("- note: a summary for the planner, at most 3 short lines separated by line breaks.")
            appendLine(if (openSteps != null) "- steps: the full list of steps still to do after the modification, in order; never repeat done steps; keep the exact wording of unchanged steps."
            else "- steps: 2 to 8 steps in order.")
            appendLine("Each step is one focused block of a few hours, a short title (at most 7 words); make the first one small and easy to start.")
            appendLine("Write in the language of the user's text.")
        }
        val reply = provider.complete("Reply with JSON only.", listOf(ChatItem.User(prompt)), emptyList()).text
        val body = reply.substring(reply.indexOf('{').coerceAtLeast(0), (reply.lastIndexOf('}') + 1).coerceAtLeast(0))
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("The plan could not be read. Try again, or use Manual.")
        val note = json["note"]?.jsonPrimitive?.contentOrNull.orEmpty().lines().take(3).joinToString("\n")
        val steps = (json["steps"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.ifBlank { null } }.orEmpty().take(12)
        return DraftPlan(note, steps)
    }

    /** Proposals from the user's channels waiting for Apply, Discuss or Dismiss (urgent first). */
    val updates = board.map { Updates.waiting(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.waiting(board.value))

    /** The two piles behind the bell: changes to the table, and answers to prepare. */
    val updateTasks = board.map { Updates.tasks(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.tasks(board.value))
    val updateReplies = board.map { Updates.replies(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Updates.replies(board.value))

    /** The updates sheet is open (bell, or a tap on an updates notification). */
    val updatesOpen = MutableStateFlow(false)

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
        if (Updates.apply(board.value, update, today) == null) {
            edit { Updates.setStatus(it, update.id, com.opslegal.tda.core.model.UpdateStatus.DISMISSED) }
            noticeState.value = Notice("That update no longer applies (the item changed since).")
            return
        }
        val project = update.project.ifBlank { null }?.let { BoardOps.findProject(board.value, it)?.name }
        apply({ b -> Updates.apply(b, update, today) ?: b }, project, doneText = "Applied.")
    }

    fun dismissUpdate(id: String) = edit { Updates.setStatus(it, id, com.opslegal.tda.core.model.UpdateStatus.DISMISSED) }

    /** Discuss: the assistant gets the update and the proposal, and the user decides with it. */
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
            Updates.setReplied(withWork, update.id)
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
                    Updates.setReplied(withWork, update.id)
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
        edit { Updates.setReplied(it, update.id) }
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

    /** Put away with its reason: "Already done" closes it and teaches nothing; "Not needed" teaches to skip similar ones. */
    fun putAway(update: com.opslegal.tda.core.model.Update, done: Boolean, pile: String) =
        edit { Updates.putAway(it, update.id, done, pile, BoardOps.newId()) }

    fun forgetLesson(id: String) = edit { Updates.forget(it, id) }

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
        edit { com.opslegal.tda.core.plan.CalendarCells.add(it, e, LocalDate.now()) }
        noticeState.value = Notice("${e.title} is in your day." + if (moved.isNotEmpty()) " Moved: ${moved.joinToString("; ")}." else "")
    }

    fun leaveOutCalendarEvent(e: com.opslegal.tda.core.agent.CalendarEvent) =
        edit { com.opslegal.tda.core.plan.CalendarCells.leaveOut(it, e, LocalDate.now()) }

    /** "Leave the rest out": every event of the day not decided yet, in one tap. */
    fun leaveOutCalendarEvents(events: List<com.opslegal.tda.core.agent.CalendarEvent>) = edit { b ->
        events.fold(b) { acc, e -> com.opslegal.tda.core.plan.CalendarCells.leaveOut(acc, e, LocalDate.now()) }
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
    fun chooseProfile(id: String) = edit { b ->
        // Presets work like an equalizer's: each keeps its own adjustments; Custom starts as a copy of what is shown.
        val current = b.about.profile
        val sets = if (current != null && current != "none") b.valueSets + (current to b.values) else b.valueSets
        if (id == "none") return@edit b.copy(valueSets = sets, about = b.about.copy(profile = "none"))
        val values = sets[id] ?: if (id == CUSTOM) b.values else Values.profiles[id]?.second.orEmpty()
        b.copy(values = values, valueSets = sets, about = b.about.copy(profile = id))
    }

    /** The 60-second voice intro: what matters, what's easy, what gets put off. */
    fun listenAbout() {
        if (voiceState.value is VoiceState.Listening) return voice.finish()
        lastReplyState.value = null
        voice.listen(board.value.conversation) { heard -> send("ABOUT ME: $heard", spoken = true) }
    }

    init {
        // The phone's languages (e.g. Français (Canada)) are offered to the voice from the start; Settings can change it.
        viewModelScope.launch {
            val b = app.boards.read()
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
