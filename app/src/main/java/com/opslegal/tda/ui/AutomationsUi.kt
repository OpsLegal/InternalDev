package com.opslegal.tda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board

private val KIND = mapOf("ad" to ("📣" to "Ads"), "scam" to ("⚠️" to "Suspicious"), "ignored" to ("🙈" to "Senders you ignore"))

/** 🧹 Filtered: what didn't deserve an alert, kept 30 days, same cards and buttons as Notifications. Each tap teaches the filter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilteredSheet(vm: MainViewModel) {
    val open by vm.filteredOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val close = { vm.filteredOpen.value = false }
    SoftDialog(
        onDismissRequest = close,
        title = { Text("🧹 Filtered · ${board.filtered.size}") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing is ever dropped silently: what doesn't look important waits here without an alert, 30 days. Each tap teaches the filter.",
                    style = MaterialTheme.typography.bodySmall)
                if (board.filtered.isEmpty()) Text("Nothing filtered right now.", style = MaterialTheme.typography.bodyMedium)
                KIND.forEach { (k, label) ->
                    val items = board.filtered.filter { it.kind == k }
                    if (items.isEmpty()) return@forEach
                    Text("${label.first} ${label.second} · ${items.size}", fontWeight = FontWeight.SemiBold)
                    items.forEach { x ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${x.from} · ${x.at.replace('T', ' ').take(16)}", style = MaterialTheme.typography.bodySmall)
                                Text(x.title, fontWeight = FontWeight.SemiBold, maxLines = 3)
                                Text("Why here: ${x.why}.", style = MaterialTheme.typography.bodySmall,
                                    color = if (k == "scam") Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { vm.filterAction(x, "show") }) { Text("Show in Notifications") }
                                    when (k) {
                                        "scam" -> OutlinedButton(onClick = { vm.filterAction(x, "scam") }) { Text("It's a scam: block") }
                                        "ad" -> OutlinedButton(onClick = { vm.filterAction(x, "ignore") }) { Text("Always ignore") }
                                        else -> Unit
                                    }
                                    TextButton(onClick = { vm.filterAction(x, "ok") }) { Text("OK") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(onClick = { close(); vm.hubOpen.value = true }) { Text("‹ Back") } },
    )
}

private val LIBS = listOf(
    Triple("stores", "🏷️ Stores → category, area", "Each store you sort once is sorted the same way next time."),
    Triple("filter", "🧹 Filtering", "Senders to show, to ignore, or blocked as scams."),
    Triple("commands", "⚡ Quick commands", "“move … to Friday”, “… done”, “buy …”, “call … tomorrow”: understood on the phone, you confirm."),
)

/**
 * Settings › Automations: what runs by rules on this phone (free, no AI) and what the rules learned from the user's
 * taps, each undoable. The assistant can go through them with the user.
 */
@Composable
internal fun AutomationsCard(vm: MainViewModel, board: Board) {
    var lib by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automations", fontWeight = FontWeight.SemiBold)
            Text("Receipts, bills, filtering and quick commands run by rules on this phone: free, instant, no AI. The AI helps only when the rules aren't sure.",
                style = MaterialTheme.typography.bodySmall)
            LIBS.forEach { (k, name, what) ->
                val n = board.ruleLearned.count { it.lib == k }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(name); Text(what, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { lib = k }) { Text(if (n > 0) "$n learned ›" else "›") }
                }
            }
            OutlinedButton(onClick = { vm.askAssistant("Go through my automations with me: what the rules learned (stores, senders, commands). One at a time; change nothing until I say.") }) {
                Text("💬 Go through them with ${com.opslegal.tda.core.agent.Me.name(board)}")
            }
        }
    }
    lib?.let { k ->
        val list = board.ruleLearned.filter { it.lib == k }
        SoftDialog(
            onDismissRequest = { lib = null },
            title = { Text(LIBS.first { it.first == k }.second) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) Text("Nothing learned yet: the built-in rules work alone.", style = MaterialTheme.typography.bodySmall)
                    list.forEach { x ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${x.key} → ${x.value.replace("|", " · ").trimEnd(' ', '·')}")
                                Text("From ${x.from}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { vm.forgetLearned(x.id) }) { Text("Undo") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lib = null }) { Text("Close") } },
        )
    }
}
