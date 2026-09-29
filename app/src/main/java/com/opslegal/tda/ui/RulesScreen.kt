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
import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Values

/**
 * About me: what matters (values), what is easy or hard, then the assistant's rules, most
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
    val talkAboutMe = rememberWithMic { vm.listenAbout() }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("What matters to me", style = MaterialTheme.typography.titleLarge)
                    Text("Tap a weight to change it. Heavier values win when choices must be made.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { addingValue = true }) { Icon(Icons.Filled.Add, "Add a value") }
            }
        }
        if (board.values.isEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Values.profiles.forEach { (id, profile) -> OutlinedButton(onClick = { vm.chooseProfile(id) }) { Text(profile.first) } }
                }
            }
        }
        itemsIndexed(board.values, key = { _, v -> "value-" + v.name }) { _, value ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editingValue = value }) {
                        Text(value.name, fontWeight = FontWeight.SemiBold)
                        if (value.meaning.isNotBlank()) Text(value.meaning, style = MaterialTheme.typography.bodySmall)
                        value.minPerWeek?.let { Text("At least $it a week", style = MaterialTheme.typography.labelSmall) }
                    }
                    // Tap to cycle 1 → 2 → 3.
                    TextButton(onClick = { vm.edit { b -> b.copy(values = b.values.map { if (it.name == value.name) it.copy(weight = value.weight % 3 + 1) else it }) } }) {
                        Text("●".repeat(value.weight) + "○".repeat(3 - value.weight), fontSize = 18.sp)
                    }
                }
            }
        }
        item {
            AboutList("Easy for me", "What you do easily or enjoy: it feels lighter, and makes a good reward.", board.about.easy) { list ->
                vm.edit { b -> b.copy(about = b.about.copy(easy = list)) }
            }
        }
        item {
            AboutList("I tend to put off", "Work that feels heavy for you: the assistant gives it an easy first step.", board.about.hard) { list ->
                vm.edit { b -> b.copy(about = b.about.copy(hard = list)) }
            }
        }
        item {
            TextButton(onClick = talkAboutMe) {
                Icon(MicIcon, contentDescription = null)
                Text("  Tell the assistant about you (60 s)")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Assistant rules", style = MaterialTheme.typography.titleLarge)
                    Text("By priority. The higher rule wins.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add rule") }
            }
        }
        itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
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
        item {
            TextButton(onClick = vm::resetRules) { Text("Reset to the default rules") }
        }
        if (board.memory.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("What the assistant learned about you", style = MaterialTheme.typography.titleMedium)
            }
            itemsIndexed(board.memory) { _, note ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $note", modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.edit { it.copy(memory = it.memory - note) } }) { Icon(Icons.Filled.Delete, "Forget") }
                }
            }
        }
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
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "New rule" else "Edit rule") },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
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
private fun ValueDialog(value: Value?, onDismiss: () -> Unit, onSave: (Value) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(value?.name.orEmpty()) }
    var meaning by remember { mutableStateOf(value?.meaning.orEmpty()) }
    var weight by remember { mutableStateOf(value?.weight ?: 2) }
    var min by remember { mutableStateOf(value?.minPerWeek ?: 0) }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (value == null) "New value" else "Value") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpField(name, { name = it }, "Name", "One or two words, e.g. Brand, Credit, Family.")
                HelpField(meaning, { meaning = it }, "What it means to you", "Why it matters, what hurts it. The assistant reads it.", singleLine = false, minLines = 2)
                HelpLabel("Weight", "How much it counts when choices must be made.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..3).forEach { w -> FilterChip(weight == w, { weight = w }, label = { Text("●".repeat(w)) }) }
                    }
                }
                HelpLabel("At least ... a week", "For what you tend to neglect. The table shows when the week falls short.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..5).forEach { n -> FilterChip(min == n, { min = n }, label = { Text(if (n == 0) "–" else "$n") }) }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete this value", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(Value(name.trim(), weight, meaning.trim(), min.takeIf { it > 0 }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
