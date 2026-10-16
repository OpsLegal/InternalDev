package com.opslegal.tda.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.LookAhead
import java.time.LocalDate

/**
 * The next 60 days, reviewed: the same thing planned twice (merge after the user says which one to keep, or "not the same"),
 * then the assistant's few ideas (apply, discuss or skip). Nothing changes without a tap.
 */
@Composable
internal fun LookAheadSheet(vm: MainViewModel) {
    val state by vm.lookAhead.collectAsStateWithLifecycle()
    val s = state ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val name = com.opslegal.tda.core.agent.Me.name(board)
    val close = { vm.lookAhead.value = null }
    if (s is MainViewModel.Ahead.Reading) {
        SoftDialog(onDismissRequest = {}, title = { Text("🔭 $name is looking ahead") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(16.dp).heightIn(max = 16.dp), strokeWidth = 2.dp)
                    Text("  Your next 60 days: duplicates, days, titles…", fontWeight = FontWeight.SemiBold)
                }
                Text("🔒 Nothing changes until you tap.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {})
        return
    }
    val r = s as MainViewModel.Ahead.Ready
    // What the user settled, with a word on how: it stays on screen, checked.
    val settled = remember(r) { mutableStateMapOf<String, String>() }
    fun day(d: String?) = d?.let { runCatching { vm.dayName(LocalDate.parse(it)) }.getOrNull() } ?: "not planned"
    SoftDialog(
        keepOpen = true,
        onDismissRequest = close,
        title = { Text("🔭 Your next 60 days") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.cells} cells checked. " + when {
                    r.dups.isEmpty() && r.ideas.isEmpty() -> "Nothing to fix: your plan is clean."
                    else -> listOfNotNull("${r.dups.size} planned twice".takeIf { r.dups.isNotEmpty() }, "${r.ideas.size} idea${if (r.ideas.size > 1) "s" else ""}".takeIf { r.ideas.isNotEmpty() }).joinToString(" · ") + "."
                }, style = MaterialTheme.typography.bodySmall)
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
                if (r.dups.isNotEmpty()) Text("Same thing twice", style = MaterialTheme.typography.labelLarge)
                r.dups.forEach { d ->
                    val k = d.keep.id + d.other.id
                    var keep by remember(k) { mutableStateOf(d.keep.id) }
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        settled[k]?.let { Text("✓ $it", fontWeight = FontWeight.SemiBold); return@Column }
                        Text("Which one do you keep?", style = MaterialTheme.typography.labelMedium)
                        listOf(d.keep, d.other).forEach { c ->
                            TagChip(keep == c.id, { keep = c.id }, label = { Text((if (keep == c.id) "✓ " else "") + "${c.title} · ${day(c.date)}") })
                        }
                        Text("The other is removed; its words go into the one you keep.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val drop = if (keep == d.keep.id) d.other.id else d.keep.id; vm.mergeCells(keep, drop); settled[k] = "Merged: one cell left" }) { Text("Merge") }
                            TextButton(onClick = { vm.notSameCells(d.keep.id, d.other.id); settled[k] = "Kept both: not asked again" }) { Text("Not the same") }
                        }
                    }
                }
                if (r.ideas.isNotEmpty()) Text("$name's ideas", style = MaterialTheme.typography.labelLarge)
                r.ideas.forEach { i ->
                    val k = i.type + i.step + i.other
                    val title = LookAhead.titleOf(board, i.step)
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        settled[k]?.let { Text("✓ $it", fontWeight = FontWeight.SemiBold); return@Column }
                        Text(when (i.type) {
                            "merge" -> "🔗 Merge “$title” with “${LookAhead.titleOf(board, i.other)}”"
                            "move" -> "📅 Move “$title” to ${day(i.date)}"
                            "rename" -> "✏️ “$title” → “${i.title}”"
                            "drop" -> "🗑 Remove “$title”"
                            else -> "💬 $title"
                        }, fontWeight = FontWeight.SemiBold)
                        if (i.why.isNotBlank()) Text(i.why, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (i.type != "note") Button(onClick = { vm.applyAheadIdea(i); settled[k] = "Done" }) { Text(if (i.type == "drop") "Remove" else "Apply") }
                            OutlinedButton(onClick = { close(); vm.askAssistant("About “$title”: ${i.why} What do you suggest?") }) { Text("Discuss") }
                            Box(Modifier.weight(1f)); TextButton(onClick = { settled[k] = "Skipped" }) { Text("Skip") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(onClick = { vm.startLookAhead() }) { Text("Look again") } },
    )
}
