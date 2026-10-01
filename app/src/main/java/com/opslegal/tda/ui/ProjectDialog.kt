package com.opslegal.tda.ui

import java.time.LocalDate

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.clickable

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
import com.opslegal.tda.core.plan.Projects
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** One line of the steps list in the project form. Done steps can't be changed. */
internal data class StepRow(val id: String?, val title: String, val done: Boolean = false, val date: String? = null)

/**
 * Create a project ([existing] null) or modify one. Two ways: with the assistant (explain it, or explain the
 * modification, and get a short note and the steps) or manual (build the steps one by one).
 */
@Composable
internal fun ProjectDialog(vm: MainViewModel, board: Board, existing: String?, onDismiss: () -> Unit) {
    val project = existing?.let { BoardOps.findProject(board, it) }
    val holder = project?.let { BoardOps.projectTask(board, it.name) }
    // A parked idea (no steps yet) opens as a new project to plan, with the idea as its explanation.
    val idea = project != null && Projects.isIdea(board, project)
    val planning = project == null || idea
    val hasAi by vm.settings.collectAsStateWithLifecycle()
    var withAssistant by remember { mutableStateOf(hasAi.hasApiKey) }
    var name by remember { mutableStateOf(project?.name.orEmpty()) }
    var priority by remember { mutableStateOf(project?.priority ?: Priority.NORMAL) }
    var deadline by remember { mutableStateOf(project?.deadline.orEmpty()) }
    var serves by remember { mutableStateOf(project?.values.orEmpty()) }
    var notes by remember { mutableStateOf(project?.notes.orEmpty()) }
    var explain by remember { mutableStateOf(if (idea) project?.notes?.ifBlank { project.name }.orEmpty() else "") }
    var planned by remember { mutableStateOf(!planning) }
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
        title = { Text(when { idea -> "Start the idea"; project != null -> "Modify project"; else -> "New project" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tags(listOf(true to "Assistant mode", false to "Manual mode"), withAssistant, { withAssistant = it })
                HelpField(name, { name = it; error = null }, "Name", "The matter or file, e.g. Smith v. Jones, Tax 2026.")
                HelpLabel("Priority", "Every step of this project takes this priority. The most important work gets the first free cells.") {
                    Tags(
                        listOf(Priority.LOW to "Low", Priority.NORMAL to "Normal", Priority.HIGH to "High", Priority.CRITICAL to "Critical"),
                        priority, { priority = it },
                    )
                }
                DateField("Deadline", deadline, { deadline = it })
                if (board.values.isNotEmpty()) {
                    HelpLabel("Serves", "What this project is good for. Its steps count for these values too.") {
                        ValueChips(board.values.map { it.name }, serves) { serves = it }
                    }
                }
                if (withAssistant) {
                    HelpField(explain, { explain = it }, if (!planning) "Explain the change" else "Explain the project",
                        if (!planning) "What changes: a new step, a new date, something already done, a different order. The assistant updates the note and the steps."
                        else "In your own words: what it is, who is involved, what's at stake, what you already know. The assistant writes a short note and the steps.",
                        singleLine = false, minLines = 3)
                    Button(
                        enabled = !busy,
                        onClick = {
                            error = when {
                                name.isBlank() -> "A project name is needed."
                                explain.isBlank() -> if (!planning) "Explain the change first." else "Explain the project in a few sentences first."
                                else -> null
                            }
                            if (error != null) return@Button
                            busy = true; status = "Thinking..."
                            scope.launch {
                                try {
                                    val plan = vm.draftPlan(
                                        name.trim(), priority.name.lowercase(), deadline.ifBlank { null }, serves, explain.trim(),
                                        currentNote = if (!planning) notes else null,
                                        doneSteps = steps.filter { it.done }.map { it.title },
                                        openSteps = if (!planning) steps.filter { !it.done }.map { it.title } else null,
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
                    ) { Text(if (!planning) "Update the plan" else "Create the plan") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!withAssistant || planned) {
                    HelpField(notes, { notes = it }, "Notes", "A short summary the assistant reads when it plans. Three lines at most.", singleLine = false, minLines = 2)
                    Text("Steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    // What saving would do, shown now: each step's day, and what moves to make room.
                    val preview = remember(steps.toList(), name, priority, deadline, notes) {
                        vm.previewProject(
                            Project(name, priority, deadline.ifBlank { null }, notes, serves, blocks = project?.blocks.orEmpty()),
                            project?.name, steps.filter { !it.done }.map { BoardOps.EditedStep(it.id, it.title, it.date) },
                        )
                    }
                    StepsEditor(steps, preview.first, vm::dayName)
                    if (preview.second.isNotEmpty()) {
                        Text(
                            "To meet the deadline, these move later: ${preview.second.joinToString()}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (idea) {
                    TextButton(onClick = { vm.deleteProject(project!!.name); onDismiss() }) {
                        Text("Delete this idea", color = MaterialTheme.colorScheme.error)
                    }
                }
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
                    steps.filter { !it.done }.map { BoardOps.EditedStep(it.id, it.title, it.date) },
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Step by step: + in front of a step adds one above it, the pencil changes it, − removes it. Done steps stay.
 * Under each step, its day (tap to choose another; red when after the deadline) and Join, which merges it with
 * the next step into one cell.
 */
@Composable
private fun StepsEditor(steps: SnapshotStateList<StepRow>, days: List<MainViewModel.StepDay>, dayName: (LocalDate) -> String) {
    val context = LocalContext.current
    val openIds = steps.withIndex().filter { !it.value.done && it.value.title.isNotBlank() }.map { it.index }
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
            val k = openIds.indexOf(i)
            if (!row.done && k >= 0 && editing != i) {
                val day = days.getOrNull(k)
                val nextOpen = openIds.getOrNull(k + 1)
                Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = when {
                        day == null -> "Choose a day"
                        day.date == null -> "No free cell yet"
                        else -> dayName(day.date) + if (row.date != null) " · your day" else ""
                    }
                    Text(
                        "📅 $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day?.late == true) kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE) else projectBarColor(),
                        modifier = Modifier.clickable {
                            val d = day?.date ?: LocalDate.now()
                            android.app.DatePickerDialog(context, { _, y, m, dd ->
                                steps[i] = steps[i].copy(date = LocalDate.of(y, m + 1, dd).toString())
                            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }.padding(vertical = 4.dp, horizontal = 2.dp),
                    )
                    if (row.date != null) {
                        Text("×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { steps[i] = steps[i].copy(date = null) }.padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                    Box(Modifier.weight(1f))
                    if (nextOpen != null) {
                        Text(
                            "Join with next ↓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                commit()
                                val a = steps[i]
                                val b = steps[nextOpen]
                                steps[i] = a.copy(title = "${a.title} + ${b.title}", id = a.id ?: b.id, date = a.date ?: b.date)
                                steps.removeAt(nextOpen)
                            }.padding(vertical = 4.dp, horizontal = 4.dp),
                        )
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
