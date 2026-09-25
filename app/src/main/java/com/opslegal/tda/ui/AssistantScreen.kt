package com.opslegal.tda.ui

import com.opslegal.tda.voice.VoiceState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.ChatItem

private val examples = listOf(
    "Add a meeting with ACME on Thursday",
    "The tax report must be filed before we can refinance the buildings. Plan it.",
    "What does my week look like?",
)

/** Chat with the TDA Assistant, which reads and edits the table through tools. */
@Composable
fun AssistantScreen(vm: MainViewModel, modifier: Modifier = Modifier, onOpenSettings: () -> Unit) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val shared by vm.sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        vm.consumeShared()?.let { text ->
            input = "I received this. Tell me if it needs something in my table (and check it against what is already planned):\n\n$text"
        }
    }
    val onMic = rememberMicAction(vm).let { mic -> { mic(null) } }
    val listState = rememberLazyListState()
    val visible = chat.filter { it !is ChatItem.ToolResults && !(it is ChatItem.Assistant && it.text.isBlank() && it.toolCalls.isEmpty()) }

    LaunchedEffect(visible.size) { if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex) }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TDA Assistant", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (chat.isNotEmpty()) IconButton(onClick = vm::clearChat) { Icon(Icons.Filled.Delete, "Clear chat") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            !premium -> Notice("The assistant is part of TDA Premium.", "See plans", onOpenSettings)
            !settings.hasApiKey -> Notice(
                "Connect your own AI account (Anthropic, OpenAI...) to talk to your assistant. It uses your tokens.",
                "Connect", onOpenSettings,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visible.isEmpty()) {
                item { Text("Try:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(examples) { example -> TextButton(onClick = { input = example }) { Text(example) } }
            }
            items(visible) { item -> Bubble(item) }
        }

        if (pending.isNotEmpty()) {
            ConfirmCard(pending.map { it.summary }, enabled = !busy, onYes = { vm.confirm() }, onNo = { vm.reject() })
        }

        VoicePanel(voice, board.conversation.endPhrases.firstOrNull(), onFinish = onMic, onStop = vm::stopVoice)

        error?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissError) { Text("OK") }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell your assistant...") },
                maxLines = 5,
            )
            if (input.isBlank()) {
                val listening = voice is VoiceState.Listening
                FilledIconButton(
                    enabled = !busy && premium && settings.hasApiKey,
                    onClick = onMic,
                    colors = if (listening) IconButtonDefaults.filledIconButtonColors(containerColor = DoneYellow, contentColor = DoneInk)
                    else IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(MicIcon, if (listening) "I'm done" else "Talk") }
            } else {
                IconButton(
                    enabled = !busy && premium,
                    onClick = { vm.send(input); input = "" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    val mine = item is ChatItem.User
    val text = when (item) {
        is ChatItem.User -> item.text
        is ChatItem.Assistant -> item.text.ifBlank { "Updating the table..." }
        is ChatItem.ToolResults -> ""
    }
    val actions = (item as? ChatItem.Assistant)?.toolCalls?.map { it.name.replace('_', ' ') }.orEmpty()
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            Text(text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            if (actions.isNotEmpty()) {
                Text(
                    actions.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

