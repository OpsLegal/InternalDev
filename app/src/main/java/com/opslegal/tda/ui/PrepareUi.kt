package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.Prepare

/**
 * ✨ Prepare: the cell's brief in the format of its kind (a call, an email, a meeting…). Each line shows where it comes
 * from; what is not known is "to confirm". Keep it in the cell, save the draft in Outlook, or change the format.
 */
@Composable
internal fun PrepareSheet(vm: MainViewModel) {
    val state by vm.prep.collectAsStateWithLifecycle()
    val s = state ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val name = com.opslegal.tda.core.agent.Me.name(board)
    val close = { vm.prep.value = null }
    var editing by remember(s) { mutableStateOf(false) }
    var kept by remember(s) { mutableStateOf(false) }
    when (s) {
        is MainViewModel.Prep.Reading -> SoftDialog(onDismissRequest = {}, title = { Text("✨ $name is preparing") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.title, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(16.dp).heightIn(max = 16.dp), strokeWidth = 2.dp)
                    Text("  Your emails, messages, calendar and notes about it…")
                }
                Text("🔒 Only your own sources. Nothing invented, nothing sent.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {})
        is MainViewModel.Prep.Failed -> SoftDialog(onDismissRequest = close, title = { Text("✨ Prepare") }, text = { Text(s.why) },
            confirmButton = { TextButton(onClick = close) { Text("Close") } }, dismissButton = { TextButton(onClick = { vm.prepareCell(s.stepId) }) { Text("Try again") } })
        is MainViewModel.Prep.Ready -> {
            val b = s.brief
            val src = b.sources.associateBy { it.id }
            if (editing) { FormatDialog(vm, b.kind, onDone = { editing = false; vm.prepareCell(s.stepId) }, onCancel = { editing = false }); return }
            SoftDialog(keepOpen = true, onDismissRequest = close, title = {
                Column { Text("${b.kind.icon} ${b.kind.name}", style = MaterialTheme.typography.labelLarge); Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 2) }
            }, text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (b.sources.isEmpty()) "No email, message or note found about it: only what to confirm." else
                        "From ${b.sources.size} of your sources. Each line says where it comes from.", style = MaterialTheme.typography.bodySmall)
                    b.sections.forEach { sec ->
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sec.title, style = MaterialTheme.typography.labelLarge)
                            if (sec.lines.isEmpty()) Text("— nothing found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            sec.lines.forEach { l ->
                                Text((if (l.toConfirm) "❓ " else "• ") + l.text, fontWeight = if (l.toConfirm) FontWeight.Normal else FontWeight.Medium)
                                if (l.sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    l.sources.mapNotNull { src[it] }.forEach { so ->
                                        Text(so.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (b.missing.isNotEmpty()) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text("❓ To confirm (not in your sources)", style = MaterialTheme.typography.labelLarge)
                        b.missing.forEach { Text("• $it") }
                    }
                    b.draft?.let { d ->
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("✉️ Draft" + if (d.to.isNotBlank()) " to ${d.to}" else "", style = MaterialTheme.typography.labelLarge)
                            if (d.subject.isNotBlank()) Text(d.subject, fontWeight = FontWeight.SemiBold)
                            Text(d.body, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { vm.saveBriefDraft(d) }) { Text("Save in Outlook drafts") }
                            Text("🔒 Saved as a draft only. You send it yourself.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !kept, onClick = { vm.keepBrief(s.stepId, b); kept = true }) { Text(if (kept) "✓ In the cell" else "Keep in the cell") }
                        TextButton(onClick = { editing = true }) { Text("✏️ Format") }
                    }
                }
            }, confirmButton = { TextButton(onClick = close) { Text("Close") } },
                dismissButton = { TextButton(onClick = { close(); vm.askAssistant("About “${s.title}”, prepared with you: ") }) { Text("Discuss") } })
        }
    }
}

/** The format of one kind of action: its sections, one per line, editable here or with the assistant's help. */
@Composable
private fun FormatDialog(vm: MainViewModel, kind: Prepare.Kind, onDone: () -> Unit, onCancel: () -> Unit) {
    val board by vm.board.collectAsStateWithLifecycle()
    var text by remember(kind) { mutableStateOf(Prepare.canvas(board, kind).joinToString("\n")) }
    SoftDialog(onDismissRequest = onCancel, title = { Text("${kind.icon} Your format for ${kind.name.lowercase()}s") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("One section per line, in the order you like. Every ${kind.name.lowercase()} is prepared this way.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { text = kind.sections.joinToString("\n") }) { Text("Default") }
                TextButton(onClick = { onCancel(); vm.prep.value = null; vm.askAssistant("Help me design my format for ${kind.name.lowercase()}s (now: ${Prepare.canvas(board, kind).joinToString(" / ")}). ") }) { Text("Ask the assistant") }
            }
        }
    }, confirmButton = { Button(onClick = { vm.setFormat(kind.key, text.lines()); onDone() }) { Text("Save · prepare again") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
