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
import androidx.compose.foundation.layout.size
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

internal fun inDays(today: LocalDate, d: LocalDate): String = when (val n = java.time.temporal.ChronoUnit.DAYS.between(today, d).toInt()) {
    0 -> "today"; 1 -> "tomorrow"; else -> if (n > 0) "in $n days" else "${-n} days ago"
}

/** 🤝 Expected outside: on when the user said so, or pre-selected by its words (✨); a tap changes it. */
@Composable
internal fun ExpectedChip(task: com.opslegal.tda.core.model.Task, onChange: (Boolean) -> Unit) {
    val on = com.opslegal.tda.core.plan.Order.expected(task)
    TagChip(on, { onChange(!on) }, label = { Text((if (on) "✓ " else "") + "🤝 Expected outside" + if (com.opslegal.tda.core.plan.Order.expectedGuessed(task)) " ✨" else "", fontSize = 12.sp) })
}

/**
 * ↻ Replan: each big task left undone gets a new day, most important first (the planner's order), from tomorrow, on
 * days with room for what the user really finishes. Each day can be picked on the table-like list. Per task: Later,
 * ⚡ Quick thing, or Drop. The next days are shown as they will be, before the tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplanSheet(vm: MainViewModel) {
    val open by vm.replanOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val later = remember { mutableStateMapOf<String, Int>() }
    val picks = remember { mutableStateMapOf<String, LocalDate>() }
    var picking by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf<String?>(null) }
    val plan = remember(board, later.toMap(), picks.toMap()) { Replan.propose(board, today, later.toMap(), picks = picks.toMap()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cap = remember(board) { Replan.realistic(board, today) }
    val close = { vm.replanOpen.value = false }
    SoftDialog(
        onDismissRequest = close,
        title = { Text("↻ Replan") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (plan.items.isEmpty() && plan.late.isEmpty()) Text("Nothing left behind. Well done.")
                else Text("${plan.items.size} big task${if (plan.items.size > 1) "s were" else " was"} not done. Most important first (🚨 → 🤝 → 📦 → 📅 → 🔗 → 🔁 → the rest), from tomorrow, on days with room for what you really finish (about $cap a day). Tap a date to pick another. Check, then Replan.",
                    style = MaterialTheme.typography.bodyMedium)
                plan.items.forEach { it ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(it.title, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(it.why, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
                                if (it.emergency) TagChip(false, { vm.emergencyAsk.value = it.stepId }, label = { Text("🚨 Today?", fontSize = 11.sp, color = Color(0xFFB3261E)) })
                            }
                            // The day: in full, how far, and a tap opens the coming days as in the table.
                            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text("Not done ${com.opslegal.tda.core.plan.Order.short(LocalDate.parse(it.missedOn))}  →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TagChip(picking == it.stepId, { picking = if (picking == it.stepId) null else it.stepId }, label = {
                                    Text(it.to?.let { d -> "📅 ${com.opslegal.tda.core.plan.Order.short(d)} · ${inDays(today, d)} ▾" } ?: "📅 No room: pick a day ▾", fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold, color = if (it.to == null) Color(0xFFB3261E) else Color.Unspecified)
                                })
                                if (it.picked) Text("your pick", style = MaterialTheme.typography.bodySmall)
                            }
                            if (picking == it.stepId) {
                                DayPicker(board, it.to, from = today.plusDays(1)) { d -> picks[it.stepId] = d; picking = null }
                                TextButton(onClick = {
                                    val s0 = it.to ?: today.plusDays(1)
                                    android.app.DatePickerDialog(context, { _, y, m, d -> picks[it.stepId] = LocalDate.of(y, m + 1, d); picking = null }, s0.year, s0.monthValue - 1, s0.dayOfMonth)
                                        .apply { datePicker.minDate = System.currentTimeMillis() + 86_400_000L }.show()
                                }) { Text("📅 Another date…") }
                            }
                            // Always the same choices, the current one highlighted: a tap changes it, nothing disappears.
                            val task = board.tasks.firstOrNull { t -> t.id == it.taskId }
                            val area = task?.let { t -> com.opslegal.tda.core.plan.Areas.of(board, t).id }.orEmpty()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Area", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    com.opslegal.tda.core.plan.Areas.all(board.settings).forEach { a ->
                                        TagChip(a.id == area, { vm.learnArea(it.taskId, a.id) }, label = {
                                            Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(areaColor(board, a.id))); Text(" " + a.name)
                                        })
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Who", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    com.opslegal.tda.core.plan.Labels.WHO.forEach { (k, v) ->
                                        TagChip(it.who == k, { vm.setWho(it.taskId, if (task?.who == k) "" else k) }, label = { Text("${v.first} ${v.second}", fontSize = 12.sp) })
                                    }
                                }
                            }
                            if (task != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("For", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                ExpectedChip(task) { on -> vm.setExpected(task.id, on) }
                            }
                            // Compact, always the same: done after all, change the project or task, later, quick, drop.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                TagChip(false, { vm.setDone(it.stepId, true); vm.notify("✓ “${it.title}” marked done on the day it was planned.") }, label = { Text("✓ Already done", fontSize = 12.sp) })
                                TagChip(false, {
                                    close()
                                    if (task != null && task.isProject) vm.openProject.value = task.project else vm.openCell.value = it.stepId to it.missedOn
                                }, label = { Text(if (task?.isProject == true) "✏️ Modify the project" else "✏️ Modify the task", fontSize = 12.sp) })
                                TagChip(false, { later[it.stepId] = (later[it.stepId] ?: 0) + 1; picks.remove(it.stepId) }, label = { Text("Later", fontSize = 12.sp) })
                                TagChip(it.quick, { vm.replanQuick(it.stepId) }, label = { Text("⚡ It's quick", fontSize = 12.sp) })
                                TagChip(dropping == it.stepId, { dropping = if (dropping == it.stepId) null else it.stepId }, label = {
                                    Text("Drop", fontSize = 12.sp, color = if (dropping == it.stepId) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                                })
                            }
                            if (dropping == it.stepId) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Drop it? The red cell stays as your record.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.replanDrop(it.stepId); dropping = null }) { Text("Yes, drop", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { dropping = null }) { Text("Keep") }
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
