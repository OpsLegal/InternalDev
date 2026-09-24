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
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.AssistantRule
import com.opslegal.tda.core.plan.BoardOps

/**
 * The assistant's rules, most important first. They are sent to the AI in this order,
 * and the top rule wins when two rules conflict.
 */
@Composable
fun RulesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val rules = board.rules.sortedBy { it.order }
    var editing by remember { mutableStateOf<AssistantRule?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    AlertDialog(
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
