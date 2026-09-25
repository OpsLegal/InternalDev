package com.opslegal.tda.core.agent

import com.opslegal.tda.core.plan.RescheduleOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A change the assistant wants to make, waiting for the user's yes. */
@Serializable
data class PendingAction(val tool: String, val input: JsonObject, val summary: String)

/**
 * What the assistant is waiting on between two messages: changes to confirm and
 * rescheduling options to choose from. The app keeps one instance for its lifetime.
 */
class AgentState {
    private val pendingState = MutableStateFlow<List<PendingAction>>(emptyList())
    val pending: StateFlow<List<PendingAction>> = pendingState.asStateFlow()

    var options: List<RescheduleOption> = emptyList()

    fun stage(action: PendingAction) {
        pendingState.value = pendingState.value + action
    }

    fun clear() {
        pendingState.value = emptyList()
    }
}
