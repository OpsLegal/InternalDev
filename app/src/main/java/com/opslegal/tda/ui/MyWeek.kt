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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Area
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.BoardOps

/**
 * My week, in Settings → Planner & layout: one row per area of life, one tap per day. Work for company A on its days,
 * company B on others, repairs on the weekend; a Cairo office works Sun–Thu. Each project or task belongs to one area.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekGrid(vm: MainViewModel, board: Board, french: Boolean) {
    val list = Areas.all(board.settings)
    val letters = if (french) listOf("L", "Ma", "Me", "J", "V", "S", "D") else listOf("M", "Tu", "W", "Th", "F", "Sa", "Su")
    var edit by remember { mutableStateOf<String?>(null) }
    val notice by vm.notice.collectAsStateWithLifecycle()
    fun save(next: List<Area>, what: String) = vm.setAreas(next, what)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("My week: what is planned on which days")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(2.8f))
            letters.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        list.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (a.work) "💼" else "🏠"} ${a.name}", Modifier.weight(2.8f).clickable { edit = a.id }.padding(vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (1..7).forEach { d ->
                    val on = d in a.days
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (on) Navy else MaterialTheme.colorScheme.surface)
                            .border(1.dp, if (on) Navy else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                val days = if (on) a.days - d else (a.days + d).sorted()
                                if (days.isNotEmpty()) save(list.map { if (it.id == a.id) it.copy(days = days) else it }, "${a.name}: ${Areas.daysText(days)}.")
                            },
                        contentAlignment = Alignment.Center,
                    ) { if (on) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Text(list.joinToString(" · ") { "${it.name}: ${Areas.daysText(it.days)}" + if (it.words.isNotEmpty()) " (${it.words.take(4).joinToString()}${if (it.words.size > 4) "…" else ""})" else "" } +
            ". Days with no area stay off the table. Tap a name to add the words that point to it.",
            style = MaterialTheme.typography.bodySmall)
        val work = list.filter { it.work }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Work weekend:", style = MaterialTheme.typography.bodySmall)
            listOf("Sat & Sun" to listOf(1, 2, 3, 4, 5), "Fri & Sat" to listOf(1, 2, 3, 4, 7)).forEach { (label, days) ->
                TagChip(work.isNotEmpty() && work.all { it.days.sorted() == days }, {
                    save(list.map { if (it.work) it.copy(days = days) else it }, "Work: ${Areas.daysText(days)}.")
                }, label = { Text(label) })
            }
            TagChip(false, { edit = "" }, label = { Text("＋ Add an area") })
        }
        notice?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.warn) MaterialTheme.colorScheme.error else projectBarColor()) }
    }
    edit?.let { id ->
        AreaDialog(list.firstOrNull { it.id == id }, list,
            onSave = { next, what -> save(next, what); edit = null },
            onDismiss = { edit = null })
    }
}

/** Add or change an area: its name, and whether it is work (public holidays and days off are free of it). */
@Composable
private fun AreaDialog(area: Area?, list: List<Area>, onSave: (List<Area>, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(area?.name.orEmpty()) }
    var words by remember { mutableStateOf(area?.words.orEmpty().joinToString(", ")) }
    var work by remember { mutableStateOf(area?.work ?: true) }
    var sure by remember { mutableStateOf(false) }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(if (area == null) "A new area" else "Change the area") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") },
                    supportingText = { Text("E.g. OPS LEGAL, Company B, Buildings, Couche-Tard. Its projects and tasks go only on its days.") })
                OutlinedTextField(words, { words = it.take(600) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Words that point to it") },
                    supportingText = { Text("Clients, nicknames, file names, people, separated by commas. E.g. for Couche-Tard: ACT, Alimentation Couche-Tard, Circle K. A task or project with one of them goes to this area by itself.") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(work, { work = true }, label = { Text("💼 Work") })
                    TagChip(!work, { work = false }, label = { Text("🏠 Personal") })
                }
                Text(if (work) "Public holidays and your days off are free of it." else "It may still be planned on holidays and days off.",
                    style = MaterialTheme.typography.bodySmall)
                if (area == null) Text("It starts on ${Areas.daysText((list.firstOrNull { it.work == work } ?: list.first()).days)}; then tap its days in the grid.",
                    style = MaterialTheme.typography.bodySmall)
                if (area != null && list.size > 1) TextButton(onClick = {
                    if (!sure) sure = true else onSave(list.filter { it.id != area.id }, "${area.name} removed: its work follows the first area again.")
                }) { Text(if (sure) "Tap again to remove ${area.name}" else "Remove this area", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name.trim()
                val w = words.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(30)
                if (area != null) onSave(list.map { if (it.id == area.id) it.copy(name = n, work = work, words = w) else it }, "$n saved.")
                else onSave(list + Area(BoardOps.newId(), n, (list.firstOrNull { it.work == work } ?: list.first()).days, work, w),
                    "$n added. Tap its days in the grid, then pick it on its projects.")
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Planned on" in the task and project forms: the area, with its days shown at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AreaChips(board: Board, selected: String, onPick: (String) -> Unit) {
    val list = Areas.all(board.settings)
    val cur = list.firstOrNull { it.id == selected } ?: list.first()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Planned on", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { a -> TagChip(a.id == cur.id, { onPick(a.id) }, label = { Text(a.name) }) }
        }
        Text("${cur.name}: ${Areas.daysText(cur.days)}", style = MaterialTheme.typography.bodySmall)
    }
}
