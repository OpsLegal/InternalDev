package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.cellsPerDay
import com.opslegal.tda.core.plan.Replan
import java.time.LocalDate

/**
 * ↻ Replan: each big task left undone gets a new day, one old task a day, on days with room for what the user really
 * finishes. Per task: Later, ⚡ Quick thing, or Drop. The next days are shown as they will be, before the tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplanSheet(vm: MainViewModel) {
    val open by vm.replanOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val later = remember { mutableStateMapOf<String, Int>() }
    var dropping by remember { mutableStateOf<String?>(null) }
    val plan = remember(board, later.toMap()) { Replan.propose(board, today, later.toMap()) }
    val cap = remember(board) { Replan.realistic(board, today) }
    val close = { vm.replanOpen.value = false }
    SoftDialog(
        onDismissRequest = close,
        title = { Text("↻ Replan") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (plan.items.isEmpty() && plan.late.isEmpty()) Text("Nothing left behind. Well done.")
                else Text("${plan.items.size} big task${if (plan.items.size > 1) "s were" else " was"} not done. I put them back one a day, on days with room for what you really finish (about $cap a day). Check, then Replan.",
                    style = MaterialTheme.typography.bodyMedium)
                plan.items.forEach { it ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(it.title, fontWeight = FontWeight.SemiBold)
                            Text("Not done on ${vm.dayName(LocalDate.parse(it.missedOn))}" + (it.to?.let { d -> "  →  ${vm.dayName(d)}" } ?: "  →  no room in the coming weeks"),
                                style = MaterialTheme.typography.bodySmall, color = if (it.to == null) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (dropping == it.stepId) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Drop it? The red cell stays as your record.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.replanDrop(it.stepId); dropping = null }) { Text("Drop", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { dropping = null }) { Text("Keep") }
                            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { later[it.stepId] = (later[it.stepId] ?: 0) + 1 }) { Text("Later") }
                                OutlinedButton(onClick = { vm.replanQuick(it.stepId, it.to ?: today) }) { Text("⚡ Quick thing") }
                                TextButton(onClick = { dropping = it.stepId }) { Text("Drop") }
                            }
                        }
                    }
                }
                if (plan.items.isEmpty()) plan.late.forEach { (name, end) ->
                    Text("⚠ $name ends ${end.end?.let { vm.dayName(it) } ?: "later"}, after its deadline ${end.deadline?.let { vm.dayName(it) }}.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { close(); vm.askAssistant("My project $name ends after its deadline. Help me choose: fewer steps, a later deadline, or more time on it. One question at a time; change nothing until I say.") }) {
                        Text("💬 Find a way with ${com.opslegal.tda.core.agent.Me.name(board)}")
                    }
                }
                if (plan.items.isNotEmpty()) {
                    // The next days as they will be: big tasks per day against what the day holds.
                    Text("Your next days after Replan", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        plan.week.forEach { (d, n) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(18.dp).height((10 + 10 * n).dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (n > cap) Color(0xFFE0A100) else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
                                Text("$n", fontSize = 11.sp)
                                Text(vm.dayName(d).take(3), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Up to ${board.cellsPerDay} big tasks a day; you usually finish about $cap.", style = MaterialTheme.typography.bodySmall)
                    plan.late.forEach { (name, end) ->
                        Text("⚠ $name would end ${end.end?.let { vm.dayName(it) } ?: "later"}, after its deadline ${end.deadline?.let { vm.dayName(it) }}.",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFB3261E), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { close(); vm.askAssistant("My project $name will end after its deadline once I replan what I didn't do. Help me choose: fewer steps, a later deadline, or more time on it. One question at a time; change nothing until I say.") }) {
                            Text("💬 Find a way with ${com.opslegal.tda.core.agent.Me.name(board)}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (plan.items.any { it.to != null }) Button(onClick = { vm.applyReplan(plan.items) }) { Text("↻ Replan (${plan.items.count { it.to != null }})") }
            else TextButton(onClick = close) { Text("Close") }
        },
        dismissButton = { if (plan.items.any { it.to != null }) TextButton(onClick = close) { Text("Not now") } },
    )
}
