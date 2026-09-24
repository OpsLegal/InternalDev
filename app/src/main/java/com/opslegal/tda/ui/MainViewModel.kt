package com.opslegal.tda.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.DefaultRules
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

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

    fun resetRules() = edit { b -> b.copy(rules = DefaultRules.all) }

    fun send(text: String) {
        if (text.isBlank() || busyState.value) return
        val agent = app.agent()
        if (agent == null) {
            errorState.value = "Connect your AI account in Settings first."
            return
        }
        busyState.value = true
        errorState.value = null
        viewModelScope.launch {
            try {
                agent.send(app.chat.items.value, text.trim()) { app.chat.append(it) }
            } catch (e: Exception) {
                errorState.value = e.message ?: "Something went wrong."
            } finally {
                busyState.value = false
            }
        }
    }

    fun clearChat() = viewModelScope.launch { app.chat.clear() }

    fun dismissError() {
        errorState.value = null
    }

    fun updateSettings(change: (com.opslegal.tda.data.AppSettings) -> com.opslegal.tda.data.AppSettings) = app.settings.update(change)

    fun setApiKey(key: String?) = app.settings.setApiKey(key)

    fun buy(activity: android.app.Activity, offer: com.opslegal.tda.billing.BillingRepository.Offer) = app.billing.buy(activity, offer)
}
