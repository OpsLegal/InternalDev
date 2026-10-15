package com.opslegal.tda.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.FirstLook
import java.time.LocalDate
import java.util.Locale

/**
 * The first look: reading (what is being done, in plain words), then one list to tick. Each item has a note for the
 * assistant (always showing) and its own Add; the bottom button adds the rest and says what it creates. A project that
 * looks like one the user has asks first: join it or a new one. A task or a bill already there starts unticked.
 */
@Composable
internal fun FirstLookSheet(vm: MainViewModel) {
    val look by vm.firstLook.collectAsStateWithLifecycle()
    val state = look ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val name = com.opslegal.tda.core.agent.Me.name(board)
    val close = { vm.firstLook.value = null }
    when (state) {
        is MainViewModel.Look.Reading -> SoftDialog(
            onDismissRequest = {}, title = { Text("✨ $name is looking") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Reading your last 30 days of email and messages" + if (state.items > 0) "… ${state.items}" else "…",
                        "Grouping what belongs together…", "Checking your table and your calendar…").forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i == state.step) CircularProgressIndicator(Modifier.width(16.dp).heightIn(max = 16.dp), strokeWidth = 2.dp) else Text(if (i < state.step) "✓" else "·")
                            Text("  $t", fontWeight = if (i == state.step) FontWeight.SemiBold else null)
                        }
                    }
                    Text("🔒 Reading only. Nothing is added until you tap, and nothing is ever sent.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is MainViewModel.Look.Failed -> SoftDialog(onDismissRequest = close, title = { Text("✨ First look") }, text = { Text(state.why) },
            confirmButton = { TextButton(onClick = close) { Text("Close") } }, dismissButton = { TextButton(onClick = { vm.startFirstLook() }) { Text("Try again") } })
        is MainViewModel.Look.Ready -> Results(vm, state.found, close)
    }
}

@Composable
private fun Results(vm: MainViewModel, f: FirstLook.Found, close: () -> Unit) {
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    // Decided once, when the list opens: what looks already there.
    val same = remember(f) {
        f.projects.mapIndexed { i, p -> "p$i" to FirstLook.lookalikeProject(board, p.name)?.name }.toMap() +
            f.tasks.mapIndexed { i, t -> "t$i" to FirstLook.lookalikeCell(board, t.title) }.toMap() +
            f.bills.mapIndexed { i, b -> "b$i" to (if (FirstLook.billThere(board, b.vendor)) "already in To pay" else null) }.toMap()
    }
    val on = remember(f) { mutableStateMapOf<String, Boolean>().apply { same.forEach { (k, v) -> put(k, k.startsWith("p") || v == null) } } }
    val note = remember(f) { mutableStateMapOf<String, String>() }
    val choice = remember(f) { mutableStateMapOf<String, String>() }
    val done = remember(f) { mutableStateMapOf<String, String>() }
    fun ready(k: String) = !(k.startsWith("p") && same[k] != null && choice[k] == null)
    fun add(k: String) {
        val i = k.drop(1).toInt(); val n = note[k].orEmpty().trim()
        when (k[0]) {
            'p' -> { val join = if (choice[k] == "join") same[k] else null; vm.firstLookProject(f.projects[i], join, n)
                done[k] = if (join != null) "Its steps added to $join" else "New project in Progress" }
            't' -> { vm.firstLookTask(f.tasks[i], n); done[k] = "In your table" }
            else -> { vm.firstLookBill(f.bills[i], n); done[k] = "Added to To pay" }
        }
    }
    val keys = f.projects.indices.map { "p$it" } + f.tasks.indices.map { "t$it" } + f.bills.indices.map { "b$it" }
    val rest = keys.filter { on[it] == true && it !in done }
    val undecided = rest.count { !ready(it) }
    val np = rest.count { it.startsWith("p") && ready(it) && choice[it] != "join" }; val nj = rest.count { it.startsWith("p") && choice[it] == "join" }
    val nt = rest.count { it.startsWith("t") }; val nb = rest.count { it.startsWith("b") }
    val label = when {
        undecided > 0 -> "First choose for $undecided project${if (undecided > 1) "s" else ""} above"
        np + nj + nt + nb == 0 -> if (done.isNotEmpty()) "Done" else "Nothing ticked"
        else -> (if (done.isNotEmpty()) "Add the rest: " else "Add all: ") + listOfNotNull(
            np.takeIf { it > 0 }?.let { "$it new project${if (it > 1) "s" else ""}" }, nj.takeIf { it > 0 }?.let { "steps to $it project${if (it > 1) "s" else ""}" },
            nt.takeIf { it > 0 }?.let { "$it task${if (it > 1) "s" else ""}" }, nb.takeIf { it > 0 }?.let { "$it bill${if (it > 1) "s" else ""}" }).joinToString(" · ")
    }
    @Composable
    fun Item(k: String, title: String, sub: String, extra: String = "") {
        val d = done[k]
        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (d != null) Text("✓ ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                else Checkbox(on[k] == true, { on[k] = it })
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(d ?: sub, style = MaterialTheme.typography.bodySmall)
                    if (d == null && extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (d != null) return@Column
            val s = same[k]
            if (s != null && k.startsWith("p")) Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                Text("Looks like your project “$s”. The same one? Joining adds its steps there.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(choice[k] == "join", { choice[k] = "join" }, label = { Text("Yes, join it") })
                    TagChip(choice[k] == "new", { choice[k] = "new" }, label = { Text("No, a new one") })
                }
            } else if (s != null) Text(if (k.startsWith("b")) "✓ Already in To pay. Add it anyway if it is another one." else "✓ Looks already in your table: “$s”. Add it anyway if it is another one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactField(note[k].orEmpty(), { note[k] = it }, "📝 Note for ${com.opslegal.tda.core.agent.Me.name(board)} (optional)", Modifier.weight(1f))
                Button(enabled = ready(k), onClick = { add(k) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("Add") }
            }
        }
    }
    SoftDialog(
        keepOpen = true, onDismissRequest = close,
        title = { Text("✨ What I found") },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("I read ${f.read} emails and messages" + (if (f.skipped > 0) "; ${f.skipped} needed nothing" else "") + ". " +
                    if (f.isEmpty) "Nothing waits: your inbox is in order." else "Untick what you don't want. A note tells me more (“Friday”, “with Julie”); Add puts one item in its place now.",
                    style = MaterialTheme.typography.bodySmall)
                if (f.projects.isNotEmpty()) Text("📁 Projects in the making", style = MaterialTheme.typography.labelLarge)
                f.projects.forEachIndexed { i, p -> Item("p$i", p.name, p.from, "${p.steps.size} steps" + (p.deadline?.let { " · deadline ${vm.dayName(it)}" } ?: "") + ": " + p.steps.take(3).joinToString(" → ")) }
                if (f.tasks.isNotEmpty()) Text("☑️ Things you were asked to do", style = MaterialTheme.typography.labelLarge)
                f.tasks.forEachIndexed { i, t -> Item("t$i", t.title, t.from + (t.project.takeIf { it.isNotBlank() }?.let { " · in $it" } ?: "") + (t.date?.let { " · ${vm.dayName(it)}" } ?: "")) }
                if (f.bills.isNotEmpty()) Text("🧾 Bills to pay", style = MaterialTheme.typography.labelLarge)
                f.bills.forEachIndexed { i, b -> Item("b$i", b.vendor + (b.amount?.let { " · %.2f $".format(Locale.CANADA, it) } ?: ""), b.from + (b.due?.let { " · due ${vm.dayName(it)}" } ?: "")) }
                Text("💬 People waiting for an answer go to your updates, with a reply ready to check.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = undecided == 0 && (rest.isNotEmpty() || done.isNotEmpty()), onClick = {
                rest.filter { ready(it) }.forEach { add(it) }
                vm.notify("Organized: projects in Progress, tasks in your table, bills in To pay. Tap any cell to change it.")
                close()
            }) { Text(label) }
        },
        dismissButton = { TextButton(onClick = close) { Text(if (done.isNotEmpty()) "Close" else "Not now") } },
    )
}
