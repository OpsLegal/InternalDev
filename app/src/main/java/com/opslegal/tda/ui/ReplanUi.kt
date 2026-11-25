package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

private val NAVY = Color(0xFF1F2A44)
private val SHORT_WHO = mapOf("office" to "Office", "others" to "With others", "alone" to "Alone")

/** A light line between the parts of a card. */
@Composable
private fun Line() = androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

/** An action: an icon on top, a short word under it; the row shares the width. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ActButton(icon: String, label: String, on: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).border(if (on) 2.dp else 1.dp, if (danger) MaterialTheme.colorScheme.error else if (on) NAVY else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 16.sp); Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

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
                else Text("${plan.items.size} not done. Each gets a day from tomorrow, most important first (about $cap a day). Tap a date to change it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan.items.forEach { it ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(it.title, fontWeight = FontWeight.SemiBold)
                            // One line: its group when it matters, and when it wasn't done.
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val missed = com.opslegal.tda.core.plan.Order.short(LocalDate.parse(it.missedOn))
                                val head = when { it.meeting && it.to == null -> if (it.repeating) "🔁 Repeating meeting" else "📅 Meeting"; it.why.contains("Normal", true) || it.why.isBlank() -> ""; else -> it.why.substringBefore(" · ") }
                                Text(buildString { if (head.isNotBlank()) append("$head · "); append(if (it.meeting && it.to == null) missed else "not done $missed") }.replaceFirstChar { c -> c.uppercase() },
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                                if (it.emergency) TagChip(false, { vm.emergencyAsk.value = it.stepId }, label = { Text("🚨 Today?", fontSize = 11.sp, color = Color(0xFFB3261E)) })
                            }
                            val pickDays: @Composable () -> Unit = {
                                DayPicker(board, it.to, from = today.plusDays(1)) { d -> picks[it.stepId] = d; picking = null }
                                TextButton(onClick = {
                                    val s0 = it.to ?: today.plusDays(1)
                                    android.app.DatePickerDialog(context, { _, y, m, d -> picks[it.stepId] = LocalDate.of(y, m + 1, d); picking = null }, s0.year, s0.monthValue - 1, s0.dayOfMonth)
                                        .apply { datePicker.minDate = System.currentTimeMillis() + 86_400_000L }.show()
                                }) { Text("📅 Another date…") }
                            }
                            if (it.meeting && it.to == null) {
                                // A meeting is not moved by itself: it happened, or not; a new date only if it was postponed.
                                Line()
                                Text("Did it happen?", fontWeight = FontWeight.SemiBold)
                                Text(if (it.repeating) "The next one stays in your calendar: nothing to move." else "Pick a new date only if it was postponed.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Line()
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ActButton("✓", "It happened") { vm.setDone(it.stepId, true); vm.notify("✓ “${it.title}” happened.") }
                                    ActButton("✕", if (it.repeating) "Skipped" else "Didn't happen") { vm.replanDrop(it.stepId) }
                                    if (!it.repeating) ActButton("📅", "New date", on = picking == it.stepId) { picking = if (picking == it.stepId) null else it.stepId }
                                }
                                if (picking == it.stepId) pickDays()
                                return@Column
                            }
                            // The day: one big button; a tap opens the coming days as in the table.
                            Line()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { picking = if (picking == it.stepId) null else it.stepId }, shape = RoundedCornerShape(12.dp)) {
                                    Text(it.to?.let { d -> "📅 ${com.opslegal.tda.core.plan.Order.short(d)}" } ?: "📅 No room: pick a day", fontWeight = FontWeight.SemiBold,
                                        color = if (it.to == null) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurface)
                                    it.to?.let { d -> Text("  ${inDays(today, d)} ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                if (it.picked) Text("your pick", style = MaterialTheme.typography.bodySmall)
                            }
                            if (picking == it.stepId) pickDays()
                            // Area (a list), who (one line), for: always the same, the current one highlighted.
                            val task = board.tasks.firstOrNull { t -> t.id == it.taskId }
                            val area = task?.let { t -> com.opslegal.tda.core.plan.Areas.of(board, t).id }.orEmpty()
                            Line()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Area", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { menu = true }, shape = RoundedCornerShape(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                        Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(areaColor(board, area)))
                                        Text("  " + (com.opslegal.tda.core.plan.Areas.all(board.settings).firstOrNull { a -> a.id == area }?.name ?: "Pick") + "  ▾", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                        com.opslegal.tda.core.plan.Areas.all(board.settings).forEach { a ->
                                            androidx.compose.material3.DropdownMenuItem(text = { Text((if (a.id == area) "✓ " else "") + a.name) }, onClick = { vm.learnArea(it.taskId, a.id); menu = false },
                                                leadingIcon = { Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(areaColor(board, a.id))) })
                                        }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Who", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))) {
                                    com.opslegal.tda.core.plan.Labels.WHO.entries.forEachIndexed { n, (k, v) ->
                                        val on = it.who == k
                                        if (n > 0) Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outline))
                                        Box(Modifier.weight(1f).height(34.dp).background(if (on) NAVY else Color.Transparent).clickable { vm.setWho(it.taskId, if (task?.who == k) "" else k) }, contentAlignment = Alignment.Center) {
                                            Text("${v.first} ${SHORT_WHO[k] ?: v.second}", fontSize = 12.sp, maxLines = 1, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            if (task != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("For", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                                ExpectedChip(task) { on -> vm.setExpected(task.id, on) }
                            }
                            // The actions: real buttons, always the same five, in the same order.
                            Line()
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ActButton("✓", "Done") { vm.setDone(it.stepId, true); vm.notify("✓ “${it.title}” marked done on the day it was planned.") }
                                ActButton("⚡", "Quick", on = it.quick) { vm.replanQuick(it.stepId) }
                                ActButton("⏭", "Later") { later[it.stepId] = (later[it.stepId] ?: 0) + 1; picks.remove(it.stepId) }
                                ActButton("✏️", "Edit") { close(); if (task != null && task.isProject) vm.openProject.value = task.project else vm.openCell.value = it.stepId to it.missedOn }
                                ActButton("🗑", "Drop", danger = dropping == it.stepId) { dropping = if (dropping == it.stepId) null else it.stepId }
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
