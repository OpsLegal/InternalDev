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
internal data class StepRow(val id: String?, val title: String, val done: Boolean = false, val date: String? = null,
    val waitDays: Int = 0, val waitFor: String = "", val added: Boolean = false)

/**
 * Create a project ([existing] null) or modify one. Name, then the project explanation: in Assistant mode the ✨
 * button rewrites it in clear words and fills the rest (intention, what it serves, the steps). To modify, the user
 * edits the explanation and taps ✨ again. Manual: the user writes everything.
 */
@Composable
internal fun ProjectDialog(vm: MainViewModel, board: Board, existing: String?, onDismiss: () -> Unit, fromTask: String? = null) {
    val project = existing?.let { BoardOps.findProject(board, it) }
    // "Make it a project": the same form, filled from the task (same intention, steps in order), all editable.
    val ft = fromTask?.let { id -> board.tasks.firstOrNull { it.id == id } }
    val holder = project?.let { BoardOps.projectTask(board, it.name) }
    // A parked idea (no steps yet) opens as a new project to plan, with the idea as its explanation.
    val idea = project != null && Projects.isIdea(board, project)
    val planning = project == null || idea
    val hasAi by vm.settings.collectAsStateWithLifecycle()
    var withAssistant by remember { mutableStateOf(hasAi.hasApiKey) }
    var name by remember { mutableStateOf(project?.name ?: ft?.title?.let { t -> var n = t; var i = 2; while (BoardOps.findProject(board, n) != null) n = "$t (${i++})"; n }.orEmpty()) }
    var priority by remember { mutableStateOf(project?.priority ?: ft?.priority ?: Priority.NORMAL) }
    var deadline by remember { mutableStateOf(project?.deadline ?: ft?.deadline.orEmpty()) }
    var area by remember { mutableStateOf(project?.name?.let { com.opslegal.tda.core.plan.Areas.ofProject(board, it).id } ?: ft?.let { com.opslegal.tda.core.plan.Areas.of(board, it).id }
        ?: com.opslegal.tda.core.plan.Areas.all(board.settings).let { l -> (l.firstOrNull { it.work } ?: l.first()).id }) }
    var serves by remember { mutableStateOf(project?.values ?: ft?.values.orEmpty()) }
    var notes by remember { mutableStateOf(project?.notes ?: ft?.let { listOf(it.title + ".", it.description).filter { s -> s.isNotBlank() }.joinToString(" ") }.orEmpty()) }
    var intention by remember { mutableStateOf(project?.intention ?: ft?.intention.orEmpty()) }
    var levels by remember { mutableStateOf(project?.serve ?: ft?.let { com.opslegal.tda.core.plan.Gbn.levelsOf(board, it) }.orEmpty()) }
    var proposedLevels by remember { mutableStateOf(levels) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The explanation the steps were last written from: changed since, the steps are rewritten by ✨.
    val original = remember { project?.notes.orEmpty() }
    val steps = remember {
        mutableStateListOf<StepRow>().apply {
            holder?.steps?.filter { it.outcome == null }?.forEach { add(StepRow(it.id, it.title, it.done, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added)) }
            // From a task: a simple two-step draft; ✨ proposes better ones. The first keeps the task's cell.
            if (ft != null) {
                val day = ft.steps.firstOrNull { !it.closed }?.date?.takeIf { !java.time.LocalDate.parse(it).isBefore(java.time.LocalDate.now()) }
                add(StepRow(null, "Prepare: what “${ft.title}” needs", date = day)); add(StepRow(null, ft.title))
            }
        }
    }
    val scope = rememberCoroutineScope()

    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(when { ft != null -> "Make it a project"; idea -> "Start the idea"; project != null -> "Modify project"; else -> "New project" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ft != null) Text("“${ft.title}” becomes a project with the same intention. Check or change anything, then Save. Its first step keeps the task's cell.",
                    style = MaterialTheme.typography.bodySmall)
                Tags(listOf(true to "Assistant mode", false to "Manual mode"), withAssistant, { withAssistant = it })
                HelpField(name, { name = it; error = null }, "Name", "The matter or file, e.g. Smith v. Jones, Kitchen renovation.")
                HelpField(notes, { notes = it; error = null }, "Project explanation",
                    if (withAssistant) "In your own words: what it is, why you want it, who is involved, what's at stake. ✨ rewrites it clearly and writes the rest. To modify the project, edit this text (e.g. \"the hearing moved to Nov 3\") and tap ✨ again."
                    else "What it is and why you want it. The assistant reads it when it plans.",
                    singleLine = false, minLines = 3)
                if (withAssistant) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            error = when {
                                name.isBlank() -> "A project name is needed."
                                notes.isBlank() -> "Explain the project in a few sentences first."
                                else -> null
                            }
                            if (error != null) return@Button
                            busy = true; status = "Thinking..."
                            scope.launch {
                                try {
                                    val modifying = !planning && steps.isNotEmpty()
                                    val plan = vm.draftPlan(
                                        name.trim(), priority.name.lowercase(), deadline.ifBlank { null }, serves, notes.trim(),
                                        currentNote = if (modifying) original else null,
                                        doneSteps = steps.filter { it.done }.map { it.title },
                                        openSteps = if (modifying) steps.filter { !it.done }.map { it.title } else null,
                                    )
                                    if (plan.note.isNotBlank()) notes = plan.note
                                    if (plan.intention.isNotBlank()) intention = plan.intention
                                    if (board.gbn) { levels = plan.serve; proposedLevels = plan.serve }
                                    // Keep done steps; reuse open steps whose wording is unchanged, so their cells stay put.
                                    if (plan.steps.isNotEmpty()) {
                                        val old = steps.filter { !it.done }
                                        val done = steps.filter { it.done }
                                        steps.clear(); steps.addAll(done)
                                        plan.steps.forEach { f -> steps.add(old.firstOrNull { it.title == f.title } ?: StepRow(null, f.title, waitDays = f.waitDays, waitFor = f.waitFor, added = f.added)) }
                                    }
                                    status = "Check it, then Save."
                                } catch (e: Exception) {
                                    status = null
                                    error = e.message ?: "The plan could not be made. Try again, or use Manual."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (ft != null) "✨ Steps from the assistant" else if (planning) "✨ Write it and plan the steps" else "✨ Apply my changes") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                HelpField(intention, { intention = it }, "Intention", "Why this project, in one sentence. Every step serves it. Correct it if the assistant got it wrong.")
                if (board.gbn) {
                    HelpLabel("What it serves", "Tap a bar to raise or lower it, as many as apply. Its steps serve the same.") {
                        GbnStrip(board.values, com.opslegal.tda.core.plan.Gbn.share(board, levels), levels, onTap = { n, l -> levels = levels.tapped(n, l) })
                    }
                    Text("Value for you: ${valueWords(com.opslegal.tda.core.plan.Gbn.valueFor(board, levels))}", style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else if (board.values.isNotEmpty()) {
                    HelpLabel("Serves", "What this project is good for. Its steps count for these values too.") {
                        ValueChips(board.values.map { it.name }, serves) { serves = it }
                    }
                }
                Text("Steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                // What saving would do, shown now: each step's day, and what moves to make room.
                val preview = remember(steps.toList(), name, priority, deadline, notes, area) {
                    vm.previewProject(
                        Project(name, priority, deadline.ifBlank { null }, notes, serves, blocks = project?.blocks.orEmpty(), area = area),
                        project?.name, steps.filter { !it.done }.map { BoardOps.EditedStep(it.id, it.title, it.date, it.waitDays, it.waitFor, it.added) },
                    )
                }
                StepsEditor(steps, preview.first, vm::dayName)
                if (deadline.isNotBlank() && steps.any { !it.done }) Text(
                    "📐 Paced to the deadline: steps spread over the work days, with a few days of buffer, holidays skipped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.second.isNotEmpty()) {
                    Text(
                        "To meet the deadline, these move later: ${preview.second.joinToString()}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HelpLabel("Priority", "Every step of this project takes this priority. The most important work gets the first free cells.") {
                    Tags(
                        listOf(Priority.LOW to "Low", Priority.NORMAL to "Normal", Priority.HIGH to "High", Priority.CRITICAL to "Critical"),
                        priority, { priority = it },
                    )
                }
                // My week: the days its steps go on change with the area, shown right away in the steps above.
                if (board.settings.areas.isNotEmpty()) AreaChips(board, area) { area = it }
                DateField("Deadline", deadline, { deadline = it })
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
                    steps.none { !it.done && it.title.isNotBlank() } && steps.none { it.done } -> if (withAssistant) "Tap ✨ to write the steps, or add them below." else "Add at least one step."
                    intention.isBlank() && planning -> "Say why you want this project, in one sentence (the intention)."
                    else -> null
                }
                if (error != null) return@TextButton
                if (board.gbn && levels.isNotEmpty() && levels != proposedLevels) vm.learnLevels(trimmed, levels)
                val saved = Project(trimmed, priority, deadline.ifBlank { null }, notes.trim(), (serves + levels.keys).distinct(), blocks = project?.blocks.orEmpty(),
                    intention = intention.trim(), serve = if (board.gbn) levels else project?.serve.orEmpty(), area = area)
                val edited = steps.filter { !it.done }.map { BoardOps.EditedStep(it.id, it.title, it.date, it.waitDays, it.waitFor, it.added) }
                if (ft != null) vm.saveProjectFromTask(ft.id, saved, edited) else vm.saveProject(saved, project?.name, edited)
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
            if (!row.done && editing != i && (row.waitDays > 0 || row.added)) Text(
                listOfNotNull(
                    if (row.waitDays > 0) "⏳ waits ${row.waitDays} work days" + (if (row.waitFor.isNotBlank()) " for ${row.waitFor}" else "") else null,
                    if (row.added) "✚ added by the assistant" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp),
            )
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
