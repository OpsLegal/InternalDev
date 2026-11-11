package com.opslegal.tda.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Asset
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Upkeep
import com.opslegal.tda.core.plan.Assets
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Paperwork
import java.time.LocalDate

/** 🏷 My things: what the user owns, each with its papers, costs, upkeep and tasks. */
@Composable
internal fun ThingsPage(vm: MainViewModel, board: Board) {
    var editing by remember { mutableStateOf<Asset?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    editing?.let { a -> AssetDialog(vm, board, a, isNew = false) { editing = null } }
    if (adding) AssetDialog(vm, board, Asset(BoardOps.newId(), "car", ""), isNew = true) { adding = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What you own, with its papers, costs, upkeep and tasks. Upkeep reminders become quick things a week before.", style = MaterialTheme.typography.bodySmall)
        if (board.assets.isEmpty()) Text("🏷 Nothing yet: add your car, your home, a rental unit, a watch…", style = MaterialTheme.typography.bodyMedium)
        board.assets.forEach { a ->
            val next = a.upkeep.minByOrNull { it.next }
            val open = Assets.tasks(board, a).size
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).clickable { editing = a }.padding(12.dp)) {
                Text(Assets.label(a), fontWeight = FontWeight.Bold)
                if (a.details.isNotBlank()) Text(a.details, style = MaterialTheme.typography.bodySmall)
                Text("📄 ${a.docs.size} · 💲 ${Paperwork.money(Assets.spent(a, today))} this year" + (next?.let { " · 🔧 ${it.what} ${vm.dayName(LocalDate.parse(it.next))} ${LocalDate.parse(it.next).month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}" } ?: "") +
                    if (open > 0) " · 📋 $open open" else "", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("＋ A thing") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetDialog(vm: MainViewModel, board: Board, start: Asset, isNew: Boolean, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val today = LocalDate.now()
    var type by remember { mutableStateOf(start.type) }
    var name by remember { mutableStateOf(start.name) }
    var details by remember { mutableStateOf(start.details) }
    val upkeep = remember { mutableStateListOf<Upkeep>().apply { addAll(start.upkeep) } }
    var what by remember { mutableStateOf("") }
    var every by remember { mutableStateOf("6") }
    var next by remember { mutableStateOf(today.plusMonths(1)) }
    var task by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val a = board.assets.firstOrNull { it.id == start.id } ?: start
    SoftDialog(keepOpen = true, onDismissRequest = onDone,
        title = { Text(if (isNew) "🏷 A new thing" else Assets.label(a)) },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Assets.TYPES.forEach { (k, l) -> PickChip(type == k, l) { type = k } }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, placeholder = { Text("e.g. Honda CR-V 2019, Unit 3", fontSize = 12.sp) })
                OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Details (one line)") }, placeholder = { Text("plate, serial, address, tenant…", fontSize = 12.sp) })
                Text("🔧 Upkeep · a reminder a week before", style = MaterialTheme.typography.labelMedium)
                upkeep.toList().forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${u.what} · every ${u.every} month${if (u.every > 1) "s" else ""} · next ${u.next}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { upkeep.remove(u) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(what, { what = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. Oil change", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(every, { every = it.filter(Char::isDigit).take(2) }, Modifier.width(56.dp), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(false, { android.app.DatePickerDialog(context, { _, y, m, d -> next = LocalDate.of(y, m + 1, d) }, next.year, next.monthValue - 1, next.dayOfMonth).show() },
                        label = { Text("📅 next $next", fontSize = 12.sp) })
                    TagChip(false, {
                        if (what.isNotBlank()) { upkeep.add(Upkeep(BoardOps.newId(), what.trim(), every.toIntOrNull()?.coerceAtLeast(1) ?: 6, next.toString())); what = "" }
                    }, label = { Text("＋ Add", fontSize = 12.sp) })
                    Text("every N months", style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew) {
                    Text("📄 Documents · ${a.docs.size}", style = MaterialTheme.typography.labelMedium)
                    a.docs.takeLast(5).reversed().forEach { d -> Text("📄 ${d.name} · ${d.at}", style = MaterialTheme.typography.bodySmall) }
                    Text("💲 Expenses · ${Paperwork.money(Assets.spent(a, today))} this year", style = MaterialTheme.typography.labelMedium)
                    a.expenses.takeLast(4).reversed().forEach { e -> Text("${e.vendor} · ${Paperwork.money(e.amount)} · ${e.date}", style = MaterialTheme.typography.bodySmall) }
                    Text("📋 Tasks", style = MaterialTheme.typography.labelMedium)
                    val open = Assets.tasks(board, a)
                    if (open.isEmpty()) Text("None open.", style = MaterialTheme.typography.bodySmall)
                    open.forEach { t -> Text("📋 ${t.title}", style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TagChip(false, { onDone(); vm.captureFor.value = "asset:${a.id}" }, label = { Text("📷 Capture for it", fontSize = 12.sp) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(task, { task = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("＋ A task for it, e.g. Fix the dishwasher", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                        TagChip(false, { vm.assetTask(a.id, task); task = "" }, label = { Text("Add", fontSize = 12.sp) })
                    }
                    TextButton(onClick = { if (confirmRemove) { vm.removeAsset(a.id); onDone() } else confirmRemove = true }) {
                        Text(if (confirmRemove) "Tap again to remove it (its papers and costs go too)" else "Remove this thing", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { vm.saveAsset(a.copy(type = type, name = name.trim(), details = details.trim(), upkeep = upkeep.toList())); onDone() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
