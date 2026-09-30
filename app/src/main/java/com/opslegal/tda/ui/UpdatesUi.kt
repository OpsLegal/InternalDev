package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import java.time.LocalDateTime
import java.time.LocalTime

private val sourceNames = mapOf(
    "outlook" to "Outlook", "gmail" to "Gmail", "email" to "Email", "teams" to "Teams", "onedrive" to "OneDrive",
    "drive" to "Drive", "whatsapp" to "WhatsApp", "sms" to "SMS", "calendar" to "Calendar",
)

internal fun sourceName(id: String) = sourceNames[id] ?: id.replaceFirstChar { it.uppercase() }

/** The bell on the table: how many updates wait for the user. */
@Composable
internal fun UpdatesBell(vm: MainViewModel) {
    val updates by vm.updates.collectAsStateWithLifecycle()
    val urgent = updates.any { it.urgent }
    Box {
        IconButton(onClick = { vm.updatesOpen.value = true }) { Icon(BellIcon, "Updates, ${updates.size} waiting") }
        if (updates.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).defaultMinSize(16.dp, 16.dp).clip(CircleShape)
                    .background(if (urgent) kindColor(TaskKind.DEADLINE) else Navy).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${updates.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** What arrived, what the assistant proposes, and Apply / Discuss / Dismiss. Nothing changes without a tap. */
@Composable
internal fun UpdatesSheet(vm: MainViewModel) {
    val open by vm.updatesOpen.collectAsStateWithLifecycle()
    if (!open) return
    val updates by vm.updates.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val close = { vm.updatesOpen.value = false }
    val last = board.checks.lastCheck?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val next = board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.sorted()
        .let { times -> times.firstOrNull { it.isAfter(LocalTime.now()) } ?: times.firstOrNull() }

    SoftDialog(
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updates", modifier = Modifier.weight(1f))
                last?.let { Text("checked ${"%02d:%02d".format(it.hour, it.minute)}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (updates.isEmpty()) {
                    Text(
                        "Nothing waiting." + (next?.let { " The assistant checks again at ${"%02d:%02d".format(it.hour, it.minute)}." } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                updates.forEach { u -> UpdateCard(u, vm, close) }
                Text(
                    "Only updates that touch your projects, tasks, deadlines or meetings show here. The rest is ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text("Check now") } },
    )
}

@Composable
private fun UpdateCard(u: Update, vm: MainViewModel, close: () -> Unit) {
    val red = kindColor(TaskKind.DEADLINE)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (u.urgent) red else MaterialTheme.colorScheme.outlineVariant, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (u.urgent) Text("Urgent", color = red, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(buildString { append(sourceName(u.source)); if (u.from.isNotBlank()) append(" · ").append(u.from) }, style = MaterialTheme.typography.labelMedium)
        }
        Text(u.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 4, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("→ ${u.summary}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.applyUpdate(u) }) { Text("Apply") }
            OutlinedButton(onClick = { close(); vm.discussUpdate(u) }) { Text("Discuss") }
            TextButton(onClick = { vm.dismissUpdate(u.id) }) { Text("Dismiss") }
        }
    }
}
