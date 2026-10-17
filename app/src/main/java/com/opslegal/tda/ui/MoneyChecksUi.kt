package com.opslegal.tda.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.MoneyCheck
import com.opslegal.tda.core.plan.Areas
import java.util.Locale

/** The two buttons of the invoice sheet, side by side: what was missed (90 days back) and what is left to sort. */
@Composable
internal fun MoneyCheckTiles(vm: MainViewModel, toSort: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LookTile("🔎 Last 90 days", "invoices & receipts missed in your mail", listOf(Color(0xFF1F2A44), Color(0xFF33415E)), Modifier.weight(1f)) { vm.startMoneyCheck() }
        LookTile("🧩 To sort", if (toSort > 0) "$toSort expense${if (toSort > 1) "s" else ""} without an area" else "every expense has an area", listOf(Color(0xFF1F5A5E), Color(0xFF2F7F80)), Modifier.weight(1f)) { vm.startSort() }
    }
}

private fun money(x: Double) = "%.2f $".format(Locale.CANADA, x)

/** One place for an expense: the area chips (pre-picked when sure) and the project or task. */
@Composable
private fun PlacePicker(vm: MainViewModel, area: String, onArea: (String) -> Unit, project: String, onProject: (String) -> Unit) {
    val board by vm.board.collectAsStateWithLifecycle()
    val areas = Areas.all(board.settings)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { areas.forEach { a -> TagChip(area == a.id, { onArea(if (area == a.id) "" else a.id) }, label = { Text(a.name) }) } }
    ProjectPicker(board, project, onProject, "For a project or a task? (optional)")
}

@Composable
internal fun MoneyCheckSheet(vm: MainViewModel) {
    val state by vm.moneyCheck.collectAsStateWithLifecycle()
    val s = state ?: return
    val close = { vm.moneyCheck.value = null }
    when (s) {
        is MainViewModel.Money.Reading -> SoftDialog(onDismissRequest = {}, title = { Text("🔎 Looking for missed invoices") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(16.dp).heightIn(max = 16.dp), strokeWidth = 2.dp)
                    Text("  Your last 90 days of email and messages…", fontWeight = FontWeight.SemiBold)
                }
                Text("🔒 Reading only. Nothing is added until you tap, and nothing is ever paid.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {})
        is MainViewModel.Money.Found -> {
            val settled = remember(s) { mutableStateMapOf<Int, String>() }
            SoftDialog(keepOpen = true, onDismissRequest = close, title = { Text("🔎 Missed in 90 days") }, text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.note.isNotBlank()) s.note else if (s.list.isEmpty()) "${s.read} messages about money read: nothing missed. Everything is in the app." else
                        "${s.list.size} not in the app yet. Give each an area (and a project if you like), then add it.", style = MaterialTheme.typography.bodySmall)
                    s.list.forEachIndexed { i, f ->
                        var paid by remember(s, i) { mutableStateOf(f.paid) }
                        var area by remember(s, i) { mutableStateOf(f.area) }
                        var project by remember(s, i) { mutableStateOf(f.project) }
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settled[i]?.let { Text("✓ ${f.vendor} · $it", fontWeight = FontWeight.SemiBold); return@Column }
                            Row { Text(f.vendor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(money(f.amount), fontWeight = FontWeight.Bold) }
                            Text(listOf(f.date, f.note, f.from.takeIf { it.isNotBlank() }?.let { "from $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TagChip(paid, { paid = true }, label = { Text("✓ Already paid") })
                                TagChip(!paid, { paid = false }, label = { Text("🧾 To pay") })
                            }
                            if (f.area.isNotBlank()) Text("✨ Pre-picked where it clearly belongs. Change it if needed.", style = MaterialTheme.typography.bodySmall)
                            PlacePicker(vm, area, { area = it }, project, { project = it })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)); TextButton(onClick = { settled[i] = "skipped" }) { Text("Not mine") }
                                Button(enabled = area.isNotBlank() || !paid, onClick = { vm.addFoundMoney(f, area, project, paid); settled[i] = if (paid) "in your expenses" else "in To pay" }) {
                                    Text(if (paid) "Add to expenses" else "Add to To pay")
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
        }
        is MainViewModel.Money.Sort -> {
            val settled = remember(s) { mutableStateMapOf<String, String>() }
            val board by vm.board.collectAsStateWithLifecycle()
            val areas = Areas.all(board.settings)
            SoftDialog(keepOpen = true, onDismissRequest = close, title = { Text("🧩 Sort expenses") }, text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.list.isEmpty()) "Every expense has an area. Nothing to sort." else
                        "${s.list.size} without an area. ✨ = pre-picked where it clearly belongs: check, then save.", style = MaterialTheme.typography.bodySmall)
                    val ready = s.list.filter { it.sure && it.id !in settled }
                    if (ready.size > 1) Button(onClick = { ready.forEach { t -> vm.sortExpense(t.id, t.area, t.project, null); settled[t.id] = areas.firstOrNull { it.id == t.area }?.name.orEmpty() } }, modifier = Modifier.fillMaxWidth()) {
                        Text("✓ Save the ${ready.size} pre-picked")
                    }
                    s.list.forEach { t ->
                        var area by remember(s, t.id) { mutableStateOf(t.area) }
                        var project by remember(s, t.id) { mutableStateOf(t.project) }
                        var amt by remember(s, t.id) { mutableStateOf("") }
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            settled[t.id]?.let { Text("✓ ${t.vendor} · $it", fontWeight = FontWeight.SemiBold); return@Column }
                            Row { Text((if (t.sure) "✨ " else "") + t.vendor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(money(t.amount), fontWeight = FontWeight.Bold) }
                            Text(listOf(t.date, t.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (t.amount == 0.0) CompactField(amt, { amt = it }, "How much? ($)", Modifier.fillMaxWidth())
                            PlacePicker(vm, area, { area = it }, project, { project = it })
                            Row { Box(Modifier.weight(1f)); Button(enabled = area.isNotBlank(), onClick = {
                                vm.sortExpense(t.id, area, project, amt.replace(',', '.').toDoubleOrNull()); settled[t.id] = listOfNotNull(areas.firstOrNull { it.id == area }?.name, project.ifBlank { null }).joinToString(" · ")
                            }) { Text("Save") } }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
        }
    }
}
