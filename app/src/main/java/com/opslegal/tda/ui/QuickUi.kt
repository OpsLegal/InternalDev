package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.cellsPerDay
import java.time.LocalDate

/**
 * A day's ⚡ line opened. Today: the picks (closest deadline, most impact, the most demanding first); each can be ticked
 * or swapped for another (↻), and once all are done a few more. Another day: what is set for it.
 */
@Composable
internal fun QuickDayDialog(vm: MainViewModel, board: Board, date: String, onAdd: () -> Unit, onDone: () -> Unit) {
    val today = LocalDate.now()
    val day = LocalDate.parse(date)
    val isToday = day == today
    val items = com.opslegal.tda.core.plan.Quick.onDay(board, day, today)
    val left = items.count { !it.done }
    val ids = items.map { it.id }.toSet()
    val more = isToday && items.isNotEmpty() && left == 0 && com.opslegal.tda.core.plan.Quick.pool(board).any { it.id !in ids }
    SoftDialog(
        onDismissRequest = onDone,
        title = { Text("⚡ Quick things · ${vm.dayName(day)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${items.size - left} of ${items.size} done" + if (isToday) ". Picked for you: closest deadline, most impact. Don't feel like one? ↻ swaps it." else " · 15 minutes or less each.",
                    style = MaterialTheme.typography.bodySmall,
                )
                items.forEach { q ->
                    Row(Modifier.fillMaxWidth().clickable { vm.tickQuick(q.id, !q.done) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(q.done, { vm.tickQuick(q.id, it) })
                        Column(Modifier.weight(1f)) {
                            Text(q.title, textDecoration = if (q.done) TextDecoration.LineThrough else null,
                                color = if (q.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            if (q.due.isNotBlank() && !q.done) Text("by ${vm.dayName(LocalDate.parse(q.due))}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (isToday && !q.done && q.date != date) TextButton(onClick = { vm.swapQuick(q.id) }) { Text("↻") }
                        else if (!isToday) TextButton(onClick = { vm.removeQuick(q.id) }) { Text("✕") }
                    }
                }
                if (more) Button(onClick = { vm.moreQuick() }, modifier = Modifier.fillMaxWidth()) { Text("⚡ ${board.settings.quickPerDay} more") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onAdd) { Text("＋ Add a quick thing") }
                    if (isToday) TextButton(onClick = { onDone(); vm.mindOpen.value = true }) { Text("✏️ Choose on the map") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

/**
 * ＋ Quick thing: pick ones already on your mind for this day, or type new ones (one per line). The choices (when,
 * area, deadline, how much it matters, effort) show only for new ones. Bigger: the task form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickAddDialog(vm: MainViewModel, date: String, start: String = "", onBig: () -> Unit, onDone: () -> Unit) {
    val board by vm.board.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(start) }
    val today = LocalDate.now()
    val given = LocalDate.parse(date)
    val target = if (given.isAfter(today)) given else today
    // New ones: the pool by default; a day only when the user opened a later day's line.
    var day by remember { mutableStateOf<LocalDate?>(if (given.isAfter(today)) given else null) }
    var choice by remember { mutableStateOf(MindChoice(area = com.opslegal.tda.core.plan.Areas.guess(board, start))) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val lines = text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val isNew = lines.isNotEmpty()
    val count = lines.size + picked.size
    SoftDialog(
        keepOpen = true, onDismissRequest = onDone,
        title = { Text("⚡ Quick thing") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("15 minutes or less: a call, a text, an appointment, a quick email.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.Top) {
                    CompactField(text, { v -> if (text.isBlank() && v.isNotBlank()) choice = choice.copy(area = com.opslegal.tda.core.plan.Areas.guess(board, v)); text = v },
                        "＋ New (one per line for several)", Modifier.weight(1f), singleLine = false, minLines = 2)
                    DictateButton(Modifier.padding(start = 6.dp, top = 7.dp)) { said -> text = listOf(text.trim(), said.trim()).filter { it.isNotBlank() }.joinToString("\n") }
                }
                if (isNew) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("For the new one${if (lines.size > 1) "s" else ""}" + if (picked.isNotEmpty()) " · and ${picked.size} picked from your mind" else "",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("When", Modifier.width(66.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            TagChip(day == null, { day = null }, label = { Text("✨ Picked for me") })
                            TagChip(day == today, { day = today }, label = { Text("Today") })
                            TagChip(day == today.plusDays(1), { day = today.plusDays(1) }, label = { Text("Tomorrow") })
                            if (given.isAfter(today.plusDays(1))) TagChip(day == given, { day = given }, label = { Text(vm.dayName(given)) })
                        }
                    }
                    Choices(vm, board, choice, withKind = false) { c, _ -> choice = c }
                } else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Or pick from what's on your mind · for ${if (target == today) "today" else vm.dayName(target)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pool = remember(board) { board.quick.filter { !it.done && it.date != target.toString() }.sortedByDescending { com.opslegal.tda.core.plan.Quick.score(it, today) }.take(8) }
                    if (pool.isEmpty()) Text("Nothing waiting: type a new one above.", style = MaterialTheme.typography.bodySmall)
                    pool.forEach { q ->
                        val area = q.area.ifBlank { com.opslegal.tda.core.plan.Areas.guess(board, q.title) }
                        val c = areaColor(board, area)
                        val on = q.id in picked
                        val shape = RoundedCornerShape(8.dp)
                        Row(Modifier.fillMaxWidth().clip(shape).background(if (on) DoneYellow.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface).border(1.5.dp, c, shape)
                            .clickable { if (on) picked.remove(q.id) else picked.add(q.id) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "☑" else "☐", Modifier.width(22.dp))
                            Text(q.title, Modifier.weight(1f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp)
                            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                            Text(" " + (q.due.takeIf { it.isNotBlank() }?.let { "📅 " + vm.dayName(LocalDate.parse(it)) } ?: com.opslegal.tda.core.plan.Areas.all(board.settings).firstOrNull { it.id == area }?.name.orEmpty()),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = onBig) { Text("Bigger than that? Make it a task") }
            }
        },
        confirmButton = {
            Button(enabled = count > 0, onClick = {
                if (lines.isNotEmpty()) vm.addQuick(lines, day, choice.due, choice.impact, choice.effort, choice.area)
                if (picked.isNotEmpty()) vm.placeQuick(picked.toList(), target)
                onDone()
            }) { Text(if (count > 1) "Add $count" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Settings › Planner & layout › My day: big tasks a day and quick things per day, with the day shown as it will be. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MyDayCard(vm: MainViewModel, board: Board) {
    val s = board.settings
    val big = board.cellsPerDay
    val learned = remember(board.tasks) { com.opslegal.tda.core.plan.Quick.learned(board, LocalDate.now()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("My day", fontWeight = FontWeight.SemiBold)
            Text("Big tasks a day", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (2..5).forEach { n -> TagChip(!s.bigLearn && big == n, { vm.setBigPerDay(n, learn = false) }, label = { Text("$n") }) }
                TagChip(s.bigLearn, { vm.setBigPerDay(learned ?: big, learn = true) }, label = { Text(if (learned != null) "Let it learn ($learned)" else "Let it learn") })
            }
            Text(
                "$big focused block${if (big > 1) "s" else ""} a day, then a ⚡ line of ${s.quickPerDay} quick things. " +
                    if (s.bigLearn) (if (learned != null) "It follows what you really finish: about $learned a day." else "It learns once you have 2 weeks of done cells.")
                    else "Most people finish 3 or 4 big things a day.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Quick things a day (picked for you, swappable)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 5, 8, 10).forEach { n -> TagChip(s.quickPerDay == n, { vm.setQuickPerDay(n) }, label = { Text("$n") }) }
            }
            Text("Your day will look like this:", style = MaterialTheme.typography.bodySmall)
            // The day as it will be: the day's name spans its big cells and its ⚡ line.
            Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.padding(0.dp).weight(0.16f).height(70.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Th23", fontSize = 12.sp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(big) { i -> Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Big ${i + 1}", fontSize = 10.sp) } }
                    }
                    Box(Modifier.fillMaxWidth().height(21.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text("⚡ 1/3  Reply to Marc · Call the garage", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
