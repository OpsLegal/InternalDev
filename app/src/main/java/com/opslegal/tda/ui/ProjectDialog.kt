package com.opslegal.tda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import kotlinx.coroutines.launch

/** One line of the steps list in the project form. Done steps can't be changed. */
internal data class StepRow(val id: String?, val title: String, val done: Boolean = false)

/**
 * Create a project ([existing] null) or modify one. Two ways: with the assistant (explain it, or explain the
 * modification, and get a short note and the steps) or manual (build the steps one by one).
 */
@Composable
internal fun ProjectDialog(vm: MainViewModel, board: Board, existing: String?, onDismiss: () -> Unit) {
    val project = existing?.let { BoardOps.findProject(board, it) }
    val holder = project?.let { BoardOps.projectTask(board, it.name) }
    var withAssistant by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(project?.name.orEmpty()) }
    var priority by remember { mutableStateOf(project?.priority ?: Priority.NORMAL) }
    var deadline by remember { mutableStateOf(project?.deadline.orEmpty()) }
    var serves by remember { mutableStateOf(project?.values.orEmpty()) }
    var notes by remember { mutableStateOf(project?.notes.orEmpty()) }
    var explain by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf(project != null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val steps = remember {
        mutableStateListOf<StepRow>().apply {
            holder?.steps?.filter { it.outcome == null }?.forEach { add(StepRow(it.id, it.title, it.done)) }
        }
    }
    val scope = rememberCoroutineScope()

    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project != null) "Modify the project" else "New project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(withAssistant, { withAssistant = true }, label = { Text("With the assistant") }, modifier = Modifier.weight(1f))
                    TagChip(!withAssistant, { withAssistant = false }, label = { Text("Manual") }, modifier = Modifier.weight(1f))
                }
                HelpField(name, { name = it; error = null }, "Name", "The matter or file, e.g. Smith v. Jones, Tax 2026.")
                HelpLabel("Priority", "Every step of this project takes this priority. The most important work gets the first free cells.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(Priority.LOW to "Low", Priority.NORMAL to "Normal", Priority.HIGH to "High", Priority.CRITICAL to "Critical").forEach { (p, label) ->
                            TagChip(priority == p, { priority = p }, label = { Text(label) })
                        }
                    }
                }
                DateField("Deadline", deadline, { deadline = it })
                if (board.values.isNotEmpty()) {
                    HelpLabel("Serves", "What this project is good for. Its steps count for these values too.") {
                        ValueChips(board.values.map { it.name }, serves) { serves = it }
                    }
                }
                if (withAssistant) {
                    HelpField(explain, { explain = it }, if (project != null) "Explain the modification" else "Explain the project",
                        if (project != null) "What changes: a new step, a new date, something already done, a different order. The assistant updates the note and the steps."
                        else "In your own words: what it is, who is involved, what's at stake, what you already know. The assistant writes a short note and the steps.",
                        singleLine = false, minLines = 3)
                    Button(
                        enabled = !busy,
                        onClick = {
                            error = when {
                                name.isBlank() -> "A project name is needed."
                                explain.isBlank() -> if (project != null) "Explain the modification first." else "Explain the project in a few sentences first."
                                else -> null
                            }
                            if (error != null) return@Button
                            busy = true; status = "Thinking..."
                            scope.launch {
                                try {
                                    val plan = vm.draftPlan(
                                        name.trim(), priority.name.lowercase(), deadline.ifBlank { null }, serves, explain.trim(),
                                        currentNote = if (project != null) notes else null,
                                        doneSteps = steps.filter { it.done }.map { it.title },
                                        openSteps = if (project != null) steps.filter { !it.done }.map { it.title } else null,
                                    )
                                    notes = plan.note
                                    // Keep done steps; reuse open steps whose wording is unchanged, so their cells stay put.
                                    val old = steps.filter { !it.done }
                                    val done = steps.filter { it.done }
                                    steps.clear(); steps.addAll(done)
                                    plan.steps.forEach { title -> steps.add(old.firstOrNull { it.title == title } ?: StepRow(null, title)) }
                                    planned = true
                                    status = "Check the note and the steps, then Save."
                                } catch (e: Exception) {
                                    status = null
                                    error = e.message ?: "The plan could not be made. Try again, or use Manual."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (project != null) "Update the plan" else "Create the plan") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!withAssistant || planned) {
                    HelpField(notes, { notes = it }, "Notes", "A short summary the assistant reads when it plans. Three lines at most.", singleLine = false, minLines = 2)
                    Text("Steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    StepsEditor(steps)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val trimmed = name.trim()
                error = when {
                    trimmed.isEmpty() -> "A project name is needed."
                    project == null && BoardOps.findProject(board, trimmed) != null -> "A project called “$trimmed” already exists. Use Modify a project."
                    withAssistant && !planned -> "Create the plan first, or switch to Manual."
                    else -> null
                }
                if (error != null) return@TextButton
                vm.saveProject(
                    Project(trimmed, priority, deadline.ifBlank { null }, notes.trim(), serves, blocks = project?.blocks.orEmpty()),
                    project?.name,
                    steps.filter { !it.done }.map { BoardOps.EditedStep(it.id, it.title) },
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Step by step: + in front of a step adds one above it, the pencil changes it, − removes it. Done steps stay. */
@Composable
private fun StepsEditor(steps: SnapshotStateList<StepRow>) {
    var editing by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var added by remember { mutableStateOf("") }
    fun commit() {
        if (editing in steps.indices) {
            if (draft.isBlank()) steps.removeAt(editing) else steps[editing] = steps[editing].copy(title = draft.trim())
        }
        editing = -1
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEachIndexed { i, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    row.done -> {
                        Text("✓", modifier = Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.title, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    editing == i -> {
                        Text("${i + 1}", modifier = Modifier.width(56.dp).padding(start = 32.dp), style = MaterialTheme.typography.bodySmall)
                        CompactField(draft, { draft = it }, "Step ${i + 1}", Modifier.weight(1f))
                        IconButton(onClick = ::commit) { Icon(Icons.Filled.Check, "Keep", Modifier.size(18.dp)) }
                    }
                    else -> {
                        IconButton(onClick = { commit(); steps.add(i, StepRow(null, "")); editing = i; draft = "" }, modifier = Modifier.size(32.dp)) { Text("+") }
                        Text("${i + 1}", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall)
                        Text(row.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { commit(); editing = i; draft = row.title }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, "Change this step", Modifier.size(16.dp)) }
                        IconButton(onClick = { commit(); steps.removeAt(i) }, modifier = Modifier.size(32.dp)) { Icon(MinusIcon, "Remove this step", Modifier.size(16.dp)) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(added, { added = it }, "Add a step...", Modifier.weight(1f).padding(start = 32.dp))
            IconButton(onClick = { commit(); if (added.isNotBlank()) { steps.add(StepRow(null, added.trim())); added = "" } }) { Text("+") }
        }
    }
}
