package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
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

/** ＋ Quick thing: one per line; it goes to the pool (picked for the right day) or on a set day, never a cell. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickAddDialog(vm: MainViewModel, date: String, start: String = "", onBig: () -> Unit, onDone: () -> Unit) {
    var text by remember { mutableStateOf(start) }
    val today = LocalDate.now()
    val given = LocalDate.parse(date)
    // The pool by default; a day only when the user opened a later day's line.
    var day by remember { mutableStateOf<LocalDate?>(if (given.isAfter(today)) given else null) }
    var due by remember { mutableStateOf("") }
    var impact by remember { mutableStateOf(2) }
    SoftDialog(
        keepOpen = true, onDismissRequest = onDone,
        title = { Text("⚡ Quick thing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("15 minutes or less: a call, a text, an appointment, a quick email.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.Top) {
                    CompactField(text, { text = it }, "What? One per line for several", Modifier.weight(1f), singleLine = false, minLines = 2)
                    DictateButton(Modifier.padding(start = 6.dp, top = 7.dp)) { said -> text = listOf(text.trim(), said.trim()).filter { it.isNotBlank() }.joinToString("\n") }
                }
                Text("When", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(day == null, { day = null }, label = { Text("✨ Picked for me") })
                    TagChip(day == today, { day = today }, label = { Text("Today") })
                    TagChip(day == today.plusDays(1), { day = today.plusDays(1) }, label = { Text("Tomorrow") })
                    if (day != null && day!!.isAfter(today.plusDays(1))) TagChip(true, {}, label = { Text(vm.dayName(day!!)) })
                }
                DateField("Deadline (optional)", due, { due = it })
                Text("How much does it matter?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "A little", 2 to "Normal", 3 to "A lot").forEach { (n, l) -> TagChip(impact == n, { impact = n }, label = { Text(l) }) }
                }
                TextButton(onClick = onBig) { Text("Bigger than that? Make it a task") }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val lines = text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
                vm.addQuick(lines, day, due, impact, area = com.opslegal.tda.core.plan.Areas.guess(vm.board.value, lines.first())); onDone()
            }) { Text("Add") }
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
