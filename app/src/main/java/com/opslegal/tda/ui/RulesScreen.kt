package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.opslegal.tda.core.agent.AssistantPage
import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Values

/**
 * Playbook: what matters (values), what is easy or hard, then the assistant's rules, most
 * important first. Rules are sent to the AI in this order; the top rule wins a conflict.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val rules = board.rules.sortedBy { it.order }
    var editing by remember { mutableStateOf<AssistantRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editingValue by remember { mutableStateOf<Value?>(null) }
    var addingValue by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    val talkAboutMe = rememberWithMic { vm.listenAbout() }
    // Tiles first; one section at a time, with a big back button (and the back gesture).
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val goTo by vm.goTo.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(goTo) { goTo?.takeIf { it.first == "playbook" }?.let { section = it.second; vm.goTo.value = null } }
    androidx.activity.compose.BackHandler(section != null) { section = null }

    if (profiles) ProfilesDialog(onUse = { vm.chooseProfile(it); profiles = false }, onDone = { profiles = false })
    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (section == null) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("What guides your assistant: what matters to you, what is easy or hard for you, and its rules.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { ProfileCard(vm, board, onMore = { section = "What matters" }) }
            item {
                Tiles(listOfNotNull("⚖️" to "What matters", "🔁" to "My routines", "🙂" to "Easy and hard for me", "📜" to "Assistant rules",
                    if (board.memory.isNotEmpty()) "🧠" to "What the assistant learned" else null)) { section = it }
            }
        } else item { BackToTiles("Playbook") { section = null } }
        if (section == "What matters") item { Text("What matters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
        if (section == "What matters") item {
            // About you first: the assistant proposes what matters from it. Ready-made profiles are the shortcut.
            if (board.about.bio.isBlank() && board.values.isEmpty()) AboutYouCard(vm, onVoice = talkAboutMe, onSkip = null)
            else Column {
                if (board.about.bio.isNotBlank()) Text("About you: “${board.about.bio.take(160)}${if (board.about.bio.length > 160) "…" else ""}”", style = MaterialTheme.typography.bodySmall)
                if (!board.gbn) Row {
                    TextButton(onClick = talkAboutMe) { Icon(MicIcon, contentDescription = null); Text("  Tell me again") }
                    TextButton(onClick = { profiles = true }) { Text("Ready-made profiles") }
                }
            }
        }
        if (section == "What matters" && board.gbn) {
            item {
                // Ground · Build · Nourish: profiles are presets (one tap), the strip is the user's equalizer.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GbnStrip(
                        board.values, emptyMap(),
                        onTap = { name, n -> vm.setWeight(name, n) },
                        onName = { editingValue = it },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tap a bar to set how much it counts; tap a name to say what it means to you or set a weekly minimum.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (board.values.size < com.opslegal.tda.core.plan.Gbn.MAX_ATTRIBUTES) TextButton(onClick = { addingValue = true }) { Text("+ Add") }
                    }
                }
            }
        } else if (section == "What matters" && (board.values.isNotEmpty() || (board.about.profile != null && board.about.profile != "none"))) {
            item {
                Equalizer(
                    board.values,
                    onWeight = { name, w -> vm.edit { b -> b.copy(values = b.values.map { if (it.name == name) it.copy(weight = w) else it }) } },
                    onOpen = { editingValue = it },
                    onAdd = { addingValue = true },
                )
            }
            item {
                Text(
                    "Tap a bar to set how much a value counts when choices must be made. Tap a name to say what it means to you or set a weekly minimum.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (section == "My routines") item { RoutinesSection(vm, board) }
        if (section == "Easy and hard for me") item { Text("Easy and hard for me", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)) }
        if (section == "Easy and hard for me") item {
            AboutList("Easy for me", "What you do easily or enjoy: it feels lighter, and makes a good reward.", board.about.easy) { list ->
                vm.edit { b -> b.copy(about = b.about.copy(easy = list)) }
            }
        }
        if (section == "Easy and hard for me") item {
            AboutList("I tend to put off", "Work that feels heavy for you: the assistant gives it an easy first step.", board.about.hard) { list ->
                vm.edit { b -> b.copy(about = b.about.copy(hard = list)) }
            }
        }
        if (section == "Assistant rules") item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Assistant rules", style = MaterialTheme.typography.titleMedium)
                    Text("By priority. The higher rule wins.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add rule") }
            }
        }
        if (section == "Assistant rules") itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                    Text(
                        rule.text,
                        modifier = Modifier.weight(1f).clickable { editing = rule },
                        color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column {
                        IconButton(onClick = { vm.edit { BoardOps.moveRule(it, rule.id, -1) } }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Higher priority")
                        }
                        IconButton(onClick = { vm.edit { BoardOps.moveRule(it, rule.id, 1) } }, enabled = index < rules.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Lower priority")
                        }
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { on -> vm.edit { BoardOps.updateRule(it, rule.id) { r -> r.copy(enabled = on) } } })
                }
            }
        }
        if (section == "Assistant rules") item {
            TextButton(onClick = vm::resetRules) { Text("Reset to the default rules") }
        }
        if (section == "What the assistant learned" && board.memory.isNotEmpty()) {
            item {
                Text("What the assistant learned about you", style = MaterialTheme.typography.titleMedium)
            }
            itemsIndexed(board.memory) { _, note ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $note", modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.edit { it.copy(memory = it.memory - note) } }) { Icon(Icons.Filled.Delete, "Forget") }
                }
            }
        }
        item { Box(Modifier.height(88.dp)) }
    }
    PageAssistantButton(vm, AssistantPage.PLAYBOOK)
    }

    if (addingValue || editingValue != null) {
        ValueDialog(
            value = editingValue,
            onDismiss = { addingValue = false; editingValue = null },
            onSave = { v ->
                val old = editingValue
                vm.edit { b -> b.copy(values = if (old == null) b.values + v else b.values.map { if (it.name == old.name) v else it }) }
                addingValue = false; editingValue = null
            },
            onDelete = editingValue?.let { old -> { vm.edit { b -> b.copy(values = b.values.filterNot { it.name == old.name }) }; editingValue = null } },
            categories = board.gbn,
        )
    }
    if (adding) RuleDialog(null, onDismiss = { adding = false }, onSave = { text -> vm.edit { BoardOps.addRule(it, text) }; adding = false })
    editing?.let { rule ->
        RuleDialog(
            rule,
            onDismiss = { editing = null },
            onSave = { text -> vm.edit { BoardOps.updateRule(it, rule.id) { r -> r.copy(text = text) } }; editing = null },
            onDelete = { vm.edit { BoardOps.deleteRule(it, rule.id) }; editing = null },
        )
    }
}

@Composable
private fun RuleDialog(rule: AssistantRule?, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)? = null) {
    var text by remember { mutableStateOf(rule?.text.orEmpty()) }
    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "New rule" else "Edit rule") },
        text = { CompactField(text, { text = it }, "Rule", Modifier.fillMaxWidth(), singleLine = false, minLines = 3) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A short list of words (easy for me, hard for me): tap one to remove it, type to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutList(title: String, help: String, items: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    HelpLabel(title, help) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                InputChip(selected = false, onClick = { onChange(items - item) }, label = { Text("$item  ×") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Add...") }, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (text.isNotBlank()) { onChange((items + text.trim()).distinct()); text = "" } }) {
                Icon(Icons.Filled.Add, "Add")
            }
        }
    }
}

@Composable
private fun ValueDialog(value: Value?, onDismiss: () -> Unit, onSave: (Value) -> Unit, onDelete: (() -> Unit)?, categories: Boolean = false) {
    var name by remember { mutableStateOf(value?.name.orEmpty()) }
    var bucket by remember { mutableStateOf(value?.bucket?.ifBlank { null } ?: com.opslegal.tda.core.plan.Gbn.GROUND) }
    var meaning by remember { mutableStateOf(value?.meaning.orEmpty()) }
    var weight by remember { mutableStateOf(value?.weight ?: 2) }
    var min by remember { mutableStateOf(value?.minPerWeek ?: 0) }
    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(if (value == null) "New value" else "Value") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpField(name, { name = it }, "Name", "One or two words, e.g. Brand, Credit, Family.")
                HelpField(meaning, { meaning = it }, "What it means to you", "Why it matters, what hurts it. The assistant reads it.", singleLine = false, minLines = 2)
                if (categories) HelpLabel("Category", "Ground keeps life running, Build creates value that turns into money, Nourish gives you energy.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.opslegal.tda.core.plan.Gbn.buckets.forEach { b -> TagChip(bucket == b, { bucket = b }, label = { Text(com.opslegal.tda.core.plan.Gbn.names.getValue(b), color = bucketInk(b)) }) }
                    }
                }
                HelpLabel("Weight", "How much it counts when choices must be made.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..3).forEach { w -> TagChip(weight == w, { weight = w }, label = { Text("●".repeat(w)) }) }
                    }
                }
                HelpLabel("At least ... a week", "For what you tend to neglect. The table shows when the week falls short.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..5).forEach { n -> TagChip(min == n, { min = n }, label = { Text(if (n == 0) "–" else "$n") }) }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete this value", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(Value(name.trim(), weight, meaning.trim(), min.takeIf { it > 0 }, if (categories) bucket else value?.bucket.orEmpty()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The values as equalizer bands: three bars each (tap one to set the weight), the name under it. */
@Composable
private fun Equalizer(values: List<Value>, onWeight: (String, Int) -> Unit, onOpen: (Value) -> Unit, onAdd: () -> Unit) {
    val on = projectBarColor()
    val off = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                (3 downTo 1).forEach { n ->
                    Box(
                        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (v.weight >= n) on else off)
                            .clickable(onClickLabel = "${v.name}: weight $n of 3") { onWeight(v.name, n) },
                    )
                }
                Text(
                    v.name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpen(v) }.padding(top = 2.dp),
                )
                Text(v.minPerWeek?.let { "$it a week" } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(4.dp))
                    .background(off).clickable(onClickLabel = "Add a value", onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            Text("Add", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
