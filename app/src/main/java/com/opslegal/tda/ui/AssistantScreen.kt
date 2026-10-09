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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.opslegal.tda.core.agent.AssistantPage
import com.opslegal.tda.core.agent.ChatItem

private val examples = listOf(
    "Add a meeting with ACME on Thursday",
    "The expert report must be filed before the pre-trial conference. Plan it.",
    "What does my week look like?",
    "Find a 1-hour slot next week for a call with Jean",
)

/** Chat with the Docket 5 assistant, which reads and edits the table through tools. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(vm: MainViewModel, modifier: Modifier = Modifier, onOpenSettings: () -> Unit, onBack: (AssistantPage) -> Unit = {}) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val shared by vm.sharedText.collectAsStateWithLifecycle()
    val prefill by vm.prefill.collectAsStateWithLifecycle()
    LaunchedEffect(prefill) { vm.consumePrefill()?.let { input = it } }
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
        // The assistant as a person: its face and the name the user gave it.
        PageHeader(com.opslegal.tda.core.agent.Me.name(board), leading = { Avatar(board.persona, 34.dp) }) {
            if (chat.isNotEmpty()) IconButton(onClick = vm::clearChat) { Icon(Icons.Filled.Delete, "Clear chat") }
            HeadIcons(vm)
        }
        page?.let { p ->
            // Opened from a page: the assistant knows what the user is looking at.
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Looking at ${p.title}", style = MaterialTheme.typography.titleSmall)
                            if (p.allowedTools != null) Text("Changes stay on this page", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onBack(p) }) { Text("← Back") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        p.suggestions.forEach { q ->
                            // "Park an idea:" waits for the user's words; the others are sent at once.
                            TagChip(false, { if (!q.endsWith(":") && !busy && premium && settings.hasApiKey) vm.send(q) else input = "$q " }, label = { Text(q) })
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            !premium -> Notice("The assistant is part of Docket 5 Premium.", "See plans", onOpenSettings)
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
            if (visible.isEmpty() && page == null) {
                item { Text("Try:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                items(examples) { example -> TextButton(onClick = { input = example }) { Text(example) } }
            }
            items(visible) { item -> Bubble(item) }
        }

        DraftCard(vm)
        if (pending.isNotEmpty()) {
            ConfirmCard(pending.map { it.summary }, enabled = !busy, onYes = { vm.confirm() }, onNo = { vm.reject() })
        }

        VoicePanel(
            voice, board.conversation.endPhrases.firstOrNull(), onFinish = onMic, onStop = vm::stopVoice,
            languages = board.conversation.languages, onLanguage = vm::switchLanguage, onInstall = vm::installVoiceLanguage,
        )

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
                placeholder = { Text("Tell ${if (com.opslegal.tda.core.agent.Me.named(board)) com.opslegal.tda.core.agent.Me.name(board) else "your assistant"}...") },
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

