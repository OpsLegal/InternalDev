package com.opslegal.tda.ui

import java.time.LocalDate

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.horizontalScroll
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Projects
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** One line of the steps list in the project form. Done steps can't be changed. */
internal data class StepRow(val id: String?, val title: String, val done: Boolean = false, val date: String? = null,
    val waitDays: Int = 0, val waitFor: String = "", val added: Boolean = false,
    /** Done when the form opened, and on which day: ticking it off again makes it to do. */
    val wasDone: Boolean = false, val doneOn: String? = null)

/**
 * Create a project ([existing] null) or modify one. Name, then the project explanation: in Assistant mode the ✨
 * button rewrites it in clear words and fills the rest (intention, what it serves, the steps). To modify, the user
 * edits the explanation and taps ✨ again. Manual: the user writes everything.
 */
@Composable
internal fun ProjectDialog(vm: MainViewModel, board: Board, existing: String?, onDismiss: () -> Unit, fromTask: String? = null, start: String? = null) {
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
    // Starts: the first step's day (Next free = the planner chooses). From an empty cell, that day.
    var startDay by remember { mutableStateOf(start?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The explanation the steps were last written from: changed since, the steps are rewritten by ✨.
    val original = remember { project?.notes.orEmpty() }
    val steps = remember {
        mutableStateListOf<StepRow>().apply {
            holder?.steps?.filter { it.outcome == null }?.forEach { add(StepRow(it.id, it.title, it.done, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added, wasDone = it.done, doneOn = it.date.takeIf { _ -> it.done })) }
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
        title = { Text(when { ft != null -> "Make it a project"; idea -> "Start the idea"; project != null -> "Modify project"; start != null -> "New project · from ${vm.dayName(LocalDate.parse(start))}"; else -> "New project" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ft != null) Text("“${ft.title}” becomes a project with the same intention. Check or change anything, then Save. Its first step keeps the task's cell.",
                    style = MaterialTheme.typography.bodySmall)
                Tags(listOf(true to "Assistant mode", false to "Manual mode"), withAssistant, { withAssistant = it })
                // A new project can start from a finished one kept as a template: its steps, intention and explanation, all editable.
                if (project == null && ft == null && board.templates.isNotEmpty()) {
                    Text("Start from a template (optional)", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        board.templates.forEach { t ->
                            androidx.compose.material3.OutlinedButton(onClick = {
                                steps.removeAll { !it.done }
                                t.steps.forEach { steps.add(StepRow(null, it.title, waitDays = it.waitDays, waitFor = it.waitFor)) }
                                if (intention.isBlank()) intention = t.intention
                                if (notes.isBlank()) notes = t.notes
                                if (t.area.isNotBlank() && com.opslegal.tda.core.plan.Areas.all(board.settings).any { it.id == t.area }) area = t.area
                            }) { Text("📋 ${t.name} · ${t.steps.size} steps") }
                        }
                    }
                }
                // 🎤 Spoken explanation: it goes into this project only, then ✨ structures it here (nothing saved before Save).
                var spoken by remember { mutableStateOf(0) }
                HelpField(name, { name = it; error = null }, "Name", "The matter or file, e.g. Smith v. Jones, Kitchen renovation.",
                    onDictate = { name = it.trim().replaceFirstChar { c -> c.uppercase() }.take(60); error = null })
                // Every project belongs to an area (its colour on the cards); its steps go on that area's days.
                AreaChips(board, area, dots = true) { area = it }
                HelpField(notes, { notes = it; error = null }, "Project explanation",
                    if (withAssistant) "In your own words: what it is, why you want it, who is involved, what's at stake. ✨ rewrites it clearly and writes the rest. To modify the project, edit this text (e.g. \"the hearing moved to Nov 3\") and tap ✨ again. Or tap 🎤 and say it."
                    else "What it is and why you want it. The assistant reads it when it plans.",
                    singleLine = false, minLines = 3,
                    onDictate = { said ->
                        notes = listOf(notes.trim(), said.trim()).filter { it.isNotBlank() }.joinToString(" ")
                        if (name.isBlank()) name = said.trim().split(Regex("\\s+")).take(4).joinToString(" ").replaceFirstChar { c -> c.uppercase() }
                        error = null; if (withAssistant) spoken++
                    })
                if (withAssistant) {
                    val assist: () -> Unit = assist@{
                            error = when {
                                name.isBlank() -> "A project name is needed."
                                notes.isBlank() -> "Explain the project in a few sentences first."
                                else -> null
                            }
                            if (error != null) return@assist
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
                    }
                    LaunchedEffect(spoken) { if (spoken > 0 && !busy) assist() }
                    Button(enabled = !busy, onClick = assist) { Text(if (ft != null) "✨ Steps from the assistant" else if (planning) "✨ Write it and plan the steps" else "✨ Apply my changes") }
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
                        tickedDone(steps), tickedOpen(steps),
                    )
                }
                StepsEditor(steps, preview.first, vm::dayName, deadline)
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
                if (project == null && ft == null) WhenChips(startDay, { startDay = it }, label = "Starts", board = board)
                DateField("Deadline", deadline, { deadline = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (idea) {
                    TextButton(onClick = { vm.deleteProject(project!!.name); onDismiss() }) {
                        Text("Delete this idea", color = MaterialTheme.colorScheme.error)
                    }
                }
                // No need to keep long projects in the list: a finished one can become a template first, then go.
                if (project != null && !idea) {
                    val finished = remember(board) { Projects.stats(board, project).finished }
                    var confirm by remember { mutableStateOf(false) }
                    if (finished) Text("Finished. Keep its steps as a template for the next one like it, or delete it.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (finished) Button(onClick = { vm.saveTemplate(project.name, thenDelete = true); onDismiss() }) { Text("📋 Template, then delete") }
                        TextButton(onClick = { confirm = true }) { Text("Delete project", color = MaterialTheme.colorScheme.error) }
                    }
                    if (confirm) {
                        val open = holder?.steps.orEmpty().count { !it.closed }
                        SoftDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Delete “${project.name}”?") },
                            text = { Text((if (open > 0) "Its $open open cell${if (open > 1) "s" else ""} leave the table. " else "") + "Its steps and history go. This can't be undone.") },
                            confirmButton = { Button(onClick = { confirm = false; vm.deleteProject(project.name); onDismiss() }) { Text("Delete") } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                        )
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
                // From an empty cell: the project starts that day.
                val edited = steps.filter { !it.done }.mapIndexed { i, it -> BoardOps.EditedStep(it.id, it.title, it.date ?: startDay?.toString()?.takeIf { i == 0 && project == null }, it.waitDays, it.waitFor, it.added) }
                if (ft != null) vm.saveProjectFromTask(ft.id, saved, edited) else vm.saveProject(saved, project?.name, edited, tickedDone(steps), tickedOpen(steps))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Steps ticked done in the form (they become done cells on Save), and done ones ticked off again (to do). */
private fun tickedDone(steps: List<StepRow>) = steps.filter { it.done && !it.wasDone && it.id != null }.mapNotNull { it.id }.toSet()
private fun tickedOpen(steps: List<StepRow>) = steps.filter { !it.done && it.wasDone && it.id != null }.mapNotNull { it.id }.toSet()

/**
 * A project's steps, seen four ways: one under another (tiles), a Gantt, a board, a table. Tap a step: its options
 * open (always the same, greyed when they don't apply): Done, Change, Day, Step after, ↑ ↓, Join with next, Remove.
 * Everything applies on Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepsEditor(steps: SnapshotStateList<StepRow>, days: List<MainViewModel.StepDay>, dayName: (LocalDate) -> String, deadline: String) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var view by rememberSaveable { mutableStateOf("steps") }
    var sel by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var added by remember { mutableStateOf("") }
    val openIdx = steps.indices.filter { !steps[it].done && steps[it].title.isNotBlank() }
    fun dayOf(i: Int): LocalDate? =
        if (steps[i].done) steps[i].doneOn?.let(LocalDate::parse) ?: today
        else days.getOrNull(openIdx.indexOf(i))?.date ?: steps[i].date?.let(LocalDate::parse)
    fun late(i: Int) = !steps[i].done && days.getOrNull(openIdx.indexOf(i))?.late == true
    fun dayText(i: Int) = dayOf(i)?.let { (if (steps[i].done) "✓ " else "📅 ") + dayName(it) } ?: if (steps[i].done) "✓" else "No day yet"
    fun commit() {
        if (editing in steps.indices) {
            if (draft.isBlank()) { steps.removeAt(editing); sel = -1 } else steps[editing] = steps[editing].copy(title = draft.trim())
        }
        editing = -1
    }
    val shown = steps.indices.filter { steps[it].done || steps[it].title.isNotBlank() || it == editing }
    val red = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)

    @Composable
    fun Tile(i: Int, compact: Boolean = false) {
        val r = steps[i]
        val shape = RoundedCornerShape(10.dp)
        val ink = if (r.done) DoneInk else MaterialTheme.colorScheme.onSurface
        val (bw, bc) = when {
            sel == i -> 2.5.dp to MaterialTheme.colorScheme.onSurface
            late(i) -> 2.dp to red
            r.done -> 1.5.dp to DoneYellow
            else -> 1.5.dp to MaterialTheme.colorScheme.outline
        }
        val m = Modifier.fillMaxWidth().clip(shape).background(if (r.done) DoneYellow else MaterialTheme.colorScheme.surfaceVariant).border(bw, bc, shape)
            .clickable { if (editing < 0) sel = if (sel == i) -1 else i }.padding(horizontal = 8.dp, vertical = 6.dp)
        if (compact) Column(m) {
            Text(r.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, color = ink)
            Text(dayText(i), fontSize = 10.sp, color = if (late(i)) red else ink.copy(alpha = 0.7f))
        } else Row(m.heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.done) "✓" else "${i + 1}", Modifier.width(20.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (r.done) DoneInk else projectBarColor())
            Text(r.title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = ink)
            Text(dayText(i), Modifier.padding(start = 6.dp), fontSize = 11.sp, maxLines = 1, color = if (late(i)) red else ink.copy(alpha = 0.7f))
        }
    }

    @Composable
    fun EditRow(i: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
        CompactField(draft, { draft = it }, "Step ${i + 1}", Modifier.weight(1f))
        IconButton(onClick = ::commit) { Icon(Icons.Filled.Check, "Keep", Modifier.size(18.dp)) }
    }

    @Composable
    fun Options(i: Int) {
        val r = steps[i]
        val open = !r.done
        val k = openIdx.indexOf(i)
        val prev = openIdx.getOrNull(k - 1)
        val next = openIdx.getOrNull(k + 1)
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(2.dp, projectBarColor(), shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { sel = -1 }) { Text("✕") }
            }
            Text(dayText(i) + if (r.date != null && open) " · your day" else "", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Done is a toggle: highlighted once done. A new step (not saved yet) can't be done.
                TagChip(r.done, { if (r.id != null) steps[i] = r.copy(done = !r.done, doneOn = if (!r.done) today.toString() else r.doneOn.takeIf { r.wasDone }) }, label = { Text("✓ Done") })
                OutlinedButton(enabled = open, onClick = { editing = i; draft = r.title }) { Text("✏️ Change") }
                OutlinedButton(enabled = open, onClick = {
                    val d = dayOf(i) ?: today
                    android.app.DatePickerDialog(context, { _, y, m, dd -> steps[i] = steps[i].copy(date = LocalDate.of(y, m + 1, dd).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }) { Text("📅 Day") }
                OutlinedButton(enabled = open, onClick = { steps.add(i + 1, StepRow(null, "")); editing = i + 1; draft = ""; sel = i + 1 }) { Text("＋ Step after") }
                OutlinedButton(enabled = open && prev != null, onClick = { val a = steps[i]; steps[i] = steps[prev!!]; steps[prev] = a; sel = prev }) { Text("↑") }
                OutlinedButton(enabled = open && next != null, onClick = { val a = steps[i]; steps[i] = steps[next!!]; steps[next] = a; sel = next }) { Text("↓") }
                TextButton(enabled = open && next != null, onClick = {
                    val a = steps[i]; val b = steps[next!!]
                    steps[i] = a.copy(title = "${a.title} + ${b.title}", id = a.id ?: b.id, date = a.date ?: b.date); steps.removeAt(next)
                }) { Text("Join with next") }
                TextButton(enabled = open && r.date != null, onClick = { steps[i] = r.copy(date = null) }) { Text("Let the planner choose") }
                TextButton(enabled = open, onClick = { steps.removeAt(i); sel = -1 }) { Text("Remove", color = if (open) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("steps" to "▦ Steps", "gantt" to "📊 Gantt", "board" to "🗂 Board", "table" to "☰ Table").forEach { (k, l) ->
                TagChip(view == k, { view = k }, label = { Text(l, fontSize = 11.sp, maxLines = 1) }, modifier = Modifier.weight(1f))
            }
        }
        val last = openIdx.mapNotNull { dayOf(it) }.maxOrNull()
        Text("${shown.count { steps[it].done }} of ${shown.size} done" + (last?.let { " · last step ${dayName(it)}" } ?: "") + ". Tap a step to change it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (view) {
            "steps" -> shown.forEach { i ->
                if (editing == i) EditRow(i) else Tile(i)
                if (sel == i && editing < 0) Options(i)
            }
            "board" -> {
                val week = today.plusDays(6)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf<Pair<String, (Int) -> Boolean>>(
                        "Later" to { i -> !steps[i].done && dayOf(i)?.isAfter(week) != false },
                        "This week" to { i -> !steps[i].done && dayOf(i)?.isAfter(week) == false },
                        "Done" to { i -> steps[i].done },
                    ).forEach { (h, f) ->
                        val list = shown.filter(f)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$h · ${list.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            list.forEach { i -> if (editing == i) EditRow(i) else Tile(i, compact = true) }
                        }
                    }
                }
            }
            "table" -> {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    listOf("#" to 22, "Step" to 0, "Day" to 64, "Status" to 64).forEach { (h, w) ->
                        Text(h, if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                shown.forEach { i ->
                    if (editing == i) { EditRow(i); return@forEach }
                    val st = when { steps[i].done -> "✓ Done"; late(i) -> "⚠ Late"; dayOf(i) == null -> "No day"; else -> "Planned" }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (sel == i) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { if (editing < 0) sel = if (sel == i) -1 else i }.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text("${i + 1}", Modifier.width(22.dp), fontSize = 12.sp)
                        Text(steps[i].title, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(dayOf(i)?.let(dayName) ?: "–", Modifier.width(64.dp), fontSize = 12.sp)
                        Text(st, Modifier.width(64.dp).background(if (steps[i].done) DoneYellow.copy(alpha = 0.6f) else Color.Transparent), fontSize = 12.sp,
                            color = if (late(i)) red else MaterialTheme.colorScheme.onSurface, fontWeight = if (late(i)) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            else -> GanttRows(shown.map { i -> GanttRow((if (steps[i].done) "✓ " else "${i + 1}. ") + steps[i].title, dayOf(i), dayOf(i),
                when { steps[i].done -> "done"; late(i) -> "late"; else -> "planned" }, sel == i) { if (editing < 0) sel = if (sel == i) -1 else i } },
                deadline.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }
        if (view != "steps" && sel in steps.indices && editing < 0) Options(sel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(added, { added = it }, "Add a step...", Modifier.weight(1f))
            Box(Modifier.padding(start = 6.dp, top = 7.dp).size(32.dp).clip(CircleShape).background(Pewter)
                .clickable(onClickLabel = "Add") { commit(); if (added.isNotBlank()) { steps.add(StepRow(null, added.trim())); added = "" } },
                contentAlignment = Alignment.Center) { Text("＋", color = Color.White, fontSize = 18.sp) }
        }
    }
}

/** One line of a Gantt: from [from] to [to] (one day for a step); [state] "done", "late", "planned" or "plan" (a project's light bar). */
internal data class GanttRow(val label: String, val from: LocalDate?, val to: LocalDate?, val state: String, val selected: Boolean = false,
    val doneShare: Float = 0f, val dot: Color? = null, val onClick: () -> Unit)

/** A Gantt with a date every few days, a light line down each row, today (dark) and the deadline (red) when given. */
@Composable
internal fun GanttRows(rows: List<GanttRow>, deadline: LocalDate? = null, floor: LocalDate? = null) {
    val today = LocalDate.now()
    val red = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)
    val all = rows.flatMap { listOfNotNull(it.from, it.to) } + listOfNotNull(today, deadline)
    var start = all.min(); if (floor != null && start.isBefore(floor)) start = floor
    val end = maxOf(all.max(), today.plusDays(7))
    val n = java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1
    fun at(d: LocalDate) = (java.time.temporal.ChronoUnit.DAYS.between(start, if (d.isBefore(start)) start else d).toFloat() / n).coerceIn(0f, 1f)
    val every = when { n > 90 -> 14; n > 35 -> 7; n > 14 -> 3; else -> 1 }
    val ticks = (0 until n step every).map { start.plusDays(it.toLong()) }
    val md = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
    val outline = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val bar = projectBarColor()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Spacer(Modifier.weight(0.4f))
            BoxWithConstraints(Modifier.weight(0.6f).height(14.dp)) {
                ticks.forEachIndexed { k, d -> if (k % (if (ticks.size > 7) 2 else 1) == 0) Text(d.format(md), Modifier.offset(x = maxWidth * at(d)), fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (r.selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent).clickable(onClick = r.onClick).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(0.4f), verticalAlignment = Alignment.CenterVertically) {
                    r.dot?.let { Box(Modifier.padding(end = 4.dp).size(8.dp).clip(CircleShape).background(it)) }
                    Text(r.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (r.state == "late") red else ink)
                }
                BoxWithConstraints(Modifier.weight(0.6f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val w = maxWidth
                    ticks.forEach { d -> Box(Modifier.offset(x = w * at(d)).width(1.dp).fillMaxHeight().background(outline.copy(alpha = 0.5f))) }
                    if (r.from != null) {
                        val to = r.to ?: r.from
                        val width = maxOf(w * (at(to) - at(r.from) + 1f / n), 6.dp)
                        val color = when (r.state) { "done" -> DoneYellow; "late" -> red; "plan" -> bar.copy(alpha = 0.35f); else -> bar }
                        Box(Modifier.offset(x = w * at(r.from)).padding(vertical = 2.dp).width(width).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color)
                            .then(if (r.state == "done") Modifier.border(1.dp, DoneInk, RoundedCornerShape(3.dp)) else Modifier))
                        if (r.doneShare > 0f) Box(Modifier.offset(x = w * at(r.from)).padding(vertical = 2.dp).width(width * r.doneShare).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(bar))
                    } else Text("no day yet", Modifier.padding(start = 6.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.offset(x = w * at(today)).width(2.dp).fillMaxHeight().background(ink))
                    deadline?.let { Box(Modifier.offset(x = w * at(it) + w / n).width(2.dp).fillMaxHeight().background(red)) }
                }
            }
        }
        Text("▮ planned · ▮ done (yellow) · ▮ late (red) · │ today" + if (deadline != null) " · │ deadline" else "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
