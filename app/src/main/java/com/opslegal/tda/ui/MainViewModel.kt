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
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val noticeState = MutableStateFlow<String?>(null)

    /** A one-line message shown on top of the table. */
    val notice: StateFlow<String?> = noticeState.asStateFlow()

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
            noticeState.value = "That day already has 5 open tasks, so \"${spec.title}\" went to the next free day. " +
                "Push or cancel a cell to make room."
        }
    }

    fun setDone(stepId: String, done: Boolean) = edit { BoardOps.setStepDone(it, stepId, done) }

    fun reopen(stepId: String) = edit { BoardOps.reopenStep(it, stepId) }

    fun push(stepId: String) = editAndPlan { BoardOps.pushStep(it, stepId, LocalDate.now()) }

    fun cancelCell(stepId: String) = edit { BoardOps.cancelStep(it, stepId, LocalDate.now()) }

    fun cancelTask(taskId: String) = edit { BoardOps.cancelTask(it, taskId, LocalDate.now()) }

    /** Saves the user's corrections. Their wording wins: the assistant is told to keep it. */
    fun updateTask(taskId: String, stepId: String?, spec: BoardOps.NewTask, cellText: String?) = edit { b ->
        var next = BoardOps.updateTask(b, taskId) { t ->
            t.copy(
                title = spec.title.trim(),
                description = spec.description.trim(),
                project = spec.project.trim(),
                priority = spec.priority,
                deadline = spec.deadline,
                // A single-cell task shows the task title: keep the cell in step with it.
                steps = if (t.steps.size == 1) t.steps.map { it.copy(title = spec.title.trim()) } else t.steps,
            )
        }
        if (stepId != null && cellText != null) next = BoardOps.renameStep(next, stepId, cellText)
        next
    }

    fun editConversation(change: (ConversationSettings) -> ConversationSettings) =
        edit { it.copy(conversation = change(it.conversation)) }

    fun resetRules() = edit { b -> b.copy(rules = DefaultRules.all) }

    /** Changes the assistant understood and is waiting for a yes on. */
    val pending = app.agentState.pending

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
        launchTurn(spoken) { agent.send(app.chat.items.value, message, spoken, onItem = { app.chat.append(it) }) }
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

    fun buy(activity: android.app.Activity, offer: com.opslegal.tda.billing.BillingRepository.Offer) = app.billing.buy(activity, offer)
}
