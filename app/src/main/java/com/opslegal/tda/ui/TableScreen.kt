package com.opslegal.tda.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.DayLabel
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.voice.VoiceState
import java.time.LocalDate

private const val DAYS_BACK = 14L
private const val DAYS_AHEAD = 60

/** What the table is currently showing on top of itself. */
private sealed interface TableDialog {
    data class CellMenu(val cell: Cell, val date: String) : TableDialog
    data class Add(val date: String?) : TableDialog
    data class Edit(val taskId: String, val stepId: String) : TableDialog
    data object NewProject : TableDialog
}

/** The main screen: the 6-column table (day + 5 equal task cells). */
@Composable
fun TableScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val rows = remember(board, settings.dayLanguage, today) {
        Planner.rows(board, today.minusDays(DAYS_BACK), DAYS_BACK.toInt() + DAYS_AHEAD, settings.dayLanguage)
    }
    val todayIndex = rows.indexOfFirst { it.date >= today.toString() }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (todayIndex - 1).coerceAtLeast(0))
    var dialog by remember { mutableStateOf<TableDialog?>(null) }
    val mic = rememberMicAction(vm)
    val projectNames = remember(board) { BoardOps.projectNames(board) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Header() }
            notice?.let { text ->
                item {
                    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::dismissNotice) { Text("OK") }
                        }
                    }
                }
            }
            items(rows, key = { it.date }) { row ->
                DayLine(
                    row = row,
                    isToday = row.date == today.toString(),
                    isPast = row.date < today.toString(),
                    onCell = { cell -> dialog = TableDialog.CellMenu(cell, row.date) },
                    onEmpty = { dialog = TableDialog.Add(row.date) },
                )
            }
            // Room to scroll the last rows above the buttons.
            item { Box(Modifier.height(260.dp)) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            VoiceDock(vm, onMic = { mic(null) })
            // One column on the right, within reach of the thumb; the mic, used most, at the bottom.
            RoundAction(NewProjectIcon, "New project", Bordeaux, onClick = { dialog = TableDialog.NewProject })
            RoundAction(NewTaskIcon, "New task", Slate, onClick = { dialog = TableDialog.Add(null) })
            val listening = voice is VoiceState.Listening
            RoundAction(
                MicIcon,
                if (listening) "I'm done" else "Talk to the assistant",
                if (listening) Recording else Navy,
                onClick = { mic(null) },
            )
        }
    }

    when (val d = dialog) {
        is TableDialog.CellMenu -> {
            val task = board.tasks.firstOrNull { it.id == d.cell.taskId }
            if (task != null) CellMenu(
                cell = d.cell,
                task = task,
                onDismiss = { dialog = null },
                onDone = { vm.setDone(d.cell.stepId, true); dialog = null },
                onReopen = { vm.reopen(d.cell.stepId); dialog = null },
                onPush = { vm.push(d.cell.stepId); dialog = null },
                onCancel = { vm.cancelCell(d.cell.stepId); dialog = null },
                onCancelTask = { vm.cancelTask(task.id); dialog = null },
                onEdit = { dialog = TableDialog.Edit(task.id, d.cell.stepId) },
                onTalk = { dialog = null; mic(LocalDate.parse(d.date)) },
                onAddHere = { dialog = TableDialog.Add(d.date) },
            )
        }
        is TableDialog.Add -> TaskDialog(
            task = null,
            stepId = null,
            day = d.date,
            dayLabel = d.date?.let { DayLabel.of(LocalDate.parse(it), settings.dayLanguage) },
            projects = projectNames,
            onDismiss = { dialog = null },
            onSave = { spec, _ ->
                if (d.date != null) vm.addTaskOn(spec, LocalDate.parse(d.date)) else vm.addTask(spec)
                dialog = null
            },
            onChooseDay = { spec, chosen -> vm.addTaskOn(spec, chosen); dialog = null },
            onSpeak = { chosen -> dialog = null; mic(chosen) },
        )
        is TableDialog.Edit -> {
            val task = board.tasks.firstOrNull { it.id == d.taskId }
            if (task != null) TaskDialog(
                task = task,
                stepId = d.stepId,
                day = null,
                dayLabel = null,
                projects = projectNames,
                onDismiss = { dialog = null },
                onSave = { spec, cellText ->
                    vm.updateTask(task.id, d.stepId, spec, cellText)
                    dialog = null
                },
                onDelete = { vm.edit { BoardOps.deleteTask(it, task.id) }; dialog = null },
            )
        }
        TableDialog.NewProject -> ProjectDialog(
            projects = board.projects,
            onDismiss = { dialog = null },
            onSave = { project, previous, steps -> vm.saveProject(project, previous, steps); dialog = null },
            onPlan = { project, previous, steps -> vm.planProject(project, previous, steps); dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("My 5 a day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayLine(
    row: DayRow,
    isToday: Boolean,
    isPast: Boolean,
    onCell: (Cell) -> Unit,
    onEmpty: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(2.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.width(44.dp).fillMaxSize().clip(RoundedCornerShape(6.dp))
                .background(if (row.allDone) DoneYellow else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.label,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (row.allDone) DoneInk else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
        }
        row.cells.forEach { cell ->
            val shape = RoundedCornerShape(6.dp)
            val background = when {
                cell == null -> MaterialTheme.colorScheme.background
                cell.done -> DoneYellow
                cell.outcome != null -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape).background(background).border(1.dp, outline, shape)
                    .then(
                        if (cell != null) Modifier.combinedClickable(onClick = { onCell(cell) }, onLongClick = { onCell(cell) })
                        else if (!isPast) Modifier.clickable(onClickLabel = "Add a task on this day", onClick = onEmpty)
                        else Modifier,
                    )
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (cell != null) {
                    Text(
                        (if (cell.outcome == Outcome.PUSHED) "↷ " else "") + cell.title,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontWeight = if (cell.priority >= Priority.HIGH && cell.outcome == null) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = if (cell.outcome == Outcome.CANCELLED) TextDecoration.LineThrough else null,
                        fontStyle = if (isPast && !cell.done && cell.outcome == null) FontStyle.Italic else null,
                        color = when {
                            cell.outcome != null -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> kindColor(cell.kind, onYellow = cell.done)
                        },
                    )
                }
            }
        }
    }
}

/** What you can do with one cell: big buttons, easy to hit with one thumb. */
@Composable
private fun CellMenu(
    cell: Cell,
    task: Task,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onReopen: () -> Unit,
    onPush: () -> Unit,
    onCancel: () -> Unit,
    onCancelTask: () -> Unit,
    onEdit: () -> Unit,
    onTalk: () -> Unit,
    onAddHere: () -> Unit,
) {
    val open = !cell.done && cell.outcome == null
    val otherOpenSteps = task.steps.count { !it.closed && it.id != cell.stepId }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(cell.title, color = kindColor(task.kind), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (task.description.isNotBlank()) {
                    Text(task.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                val facts = listOfNotNull(
                    task.project.takeIf { it.isNotBlank() },
                    when (task.kind) {
                        TaskKind.MEETING -> "meeting"
                        TaskKind.DEADLINE -> "deadline"
                        TaskKind.TASK -> null
                    },
                    task.deadline?.let { "due $it" },
                    when {
                        cell.done -> "done"
                        cell.outcome == Outcome.PUSHED -> "pushed"
                        cell.outcome == Outcome.CANCELLED -> "cancelled"
                        else -> null
                    },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                val actions = buildList {
                    if (open) {
                        add(Triple("Done", Icons.Filled.Check, onDone))
                        add(Triple("Push", PushIcon, onPush))
                        add(Triple("Cancel", Icons.Filled.Close, onCancel))
                    } else {
                        add(Triple("To do", Icons.Filled.Refresh, onReopen))
                    }
                    add(Triple("Edit", Icons.Filled.Edit, onEdit))
                    add(Triple("Talk", MicIcon, onTalk))
                    add(Triple("Add", NewTaskIcon, onAddHere))
                }
                actions.chunked(3).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        line.forEach { (label, icon, action) ->
                            val (color, content) = when (label) {
                                "Done" -> DoneYellow to DoneInk
                                "Push", "Add", "To do" -> Slate to androidx.compose.ui.graphics.Color.White
                                "Cancel" -> Pewter to androidx.compose.ui.graphics.Color.White
                                else -> Navy to androidx.compose.ui.graphics.Color.White
                            }
                            RoundAction(icon, descriptionFor(label), color, action, contentColor = content, label = label)
                        }
                        repeat(3 - line.size) { Box(Modifier.width(64.dp)) }
                    }
                }
                if (open && otherOpenSteps > 0) {
                    TextButton(onClick = onCancelTask, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel the whole task ($otherOpenSteps more cells)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun descriptionFor(label: String) = when (label) {
    "Done" -> "Done: turn it yellow"
    "Push" -> "Push to a later day"
    "Cancel" -> "Cancel this cell"
    "To do" -> "Back to to-do"
    "Edit" -> "Edit the task"
    "Talk" -> "Talk to the assistant about this day"
    else -> "Add a task on this day"
}

/**
 * Adds a task (when [task] is null) or edits one: title, notes, type and project. Priority and
 * deadline belong to the project. The notes are required: they are what the assistant reads
 * to understand a title that may be short or deliberately discreet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDialog(
    task: Task?,
    stepId: String?,
    day: String?,
    dayLabel: String?,
    projects: List<String>,
    onDismiss: () -> Unit,
    onSave: (BoardOps.NewTask, String?) -> Unit,
    onDelete: (() -> Unit)? = null,
    /** New task on a day picked in the form (when it wasn't opened from a cell). */
    onChooseDay: ((BoardOps.NewTask, LocalDate) -> Unit)? = null,
    /** Close the form and tell the assistant instead, about [day] or the day picked. */
    onSpeak: ((LocalDate?) -> Unit)? = null,
) {
    val today = LocalDate.now()
    // For a new task opened with the task button: which day. null = the next free cell.
    var pickedDay by remember { mutableStateOf<LocalDate?>(null) }
    var otherDay by remember { mutableStateOf("") }
    var askOtherDay by remember { mutableStateOf(false) }
    val step = task?.steps?.firstOrNull { it.id == stepId }
    val multiStep = (task?.steps?.size ?: 0) > 1
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.description.orEmpty()) }
    var cellText by remember { mutableStateOf(step?.title.orEmpty()) }
    var project by remember { mutableStateOf(task?.project.orEmpty()) }
    var kind by remember { mutableStateOf(task?.kind ?: TaskKind.TASK) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    SoftDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (task == null) (dayLabel?.let { "New task · $it" } ?: "New task") else "Edit task",
                    modifier = Modifier.weight(1f),
                )
                if (task == null && onSpeak != null) {
                    IconButton(onClick = { onSpeak(day?.let(LocalDate::parse) ?: pickedDay) }) {
                        Icon(MicIcon, contentDescription = "Say it to the assistant instead")
                    }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task == null && day == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(pickedDay == null && !askOtherDay, { pickedDay = null; otherDay = ""; askOtherDay = false }, label = { Text("Next free") })
                        FilterChip(pickedDay == today && !askOtherDay, { pickedDay = today; askOtherDay = false }, label = { Text("Today") })
                        FilterChip(pickedDay == today.plusDays(1) && !askOtherDay, { pickedDay = today.plusDays(1); askOtherDay = false }, label = { Text("Tomorrow") })
                        FilterChip(askOtherDay, { askOtherDay = true; pickedDay = runCatching { LocalDate.parse(otherDay.trim()) }.getOrNull() }, label = { Text("Other day") })
                    }
                    if (askOtherDay) {
                        OutlinedTextField(
                            otherDay,
                            { otherDay = it; pickedDay = runCatching { LocalDate.parse(it.trim()) }.getOrNull() },
                            label = { Text("YYYY-MM-DD") },
                            singleLine = true,
                        )
                    }
                }
                HelpField(title, { title = it }, "Title", "What the cell shows. Keep it short; it can stay discreet.")
                HelpField(
                    notes, { notes = it }, "Notes",
                    "What it is, why it matters, any context. Needed so the assistant understands the task. The cell only shows the title.",
                    singleLine = false, minLines = 2,
                )
                if (multiStep) {
                    HelpField(cellText, { cellText = it }, "This cell", "This task has several cells; this is the text of the one you tapped.")
                }
                HelpLabel("Type", "Blue: regular work. Black: a meeting or call. Red: a delivery, filing or deadline due that day.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(TaskKind.TASK to "Task", TaskKind.MEETING to "Meeting", TaskKind.DEADLINE to "Deadline").forEach { (k, name) ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(name, color = kindColor(k), fontWeight = FontWeight.SemiBold) },
                            )
                        }
                    }
                }
                HelpField(
                    project, { project = it }, "Project",
                    "Pick one below or type a new name. Priority and deadline are set on the project, with the folder button.",
                )
                val shown = projects.filter { project.isBlank() || it.contains(project.trim(), ignoreCase = true) }
                    .filterNot { it.equals(project.trim(), ignoreCase = true) }
                if (shown.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        shown.take(12).forEach { name -> FilterChip(false, { project = name }, label = { Text(name) }) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(
                            if (confirmDelete) "Tap again to delete the task and all its cells" else "Delete this task",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    title.isBlank() -> "A title is needed."
                    notes.isBlank() -> "Add a short note so the assistant understands this task."
                    askOtherDay && pickedDay == null -> "\"$otherDay\" is not a date like 2026-10-15."
                    else -> null
                }
                if (error != null) return@TextButton
                val spec = BoardOps.NewTask(
                    title = title,
                    description = notes,
                    project = project,
                    kind = kind,
                    // Kept as it is when editing; new tasks take their project's.
                    priority = task?.priority ?: Priority.NORMAL,
                    deadline = task?.deadline,
                )
                if (task == null && day == null && pickedDay != null && onChooseDay != null) {
                    onChooseDay(spec, pickedDay!!)
                } else {
                    onSave(spec, if (multiStep) cellText.trim().ifBlank { null } else null)
                }
            }) { Text(if (task == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A project (matter, file): where priority, deadline and suggested steps live. Save it, or hand
 * it to the assistant to build the plan together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSave: (Project, String?, List<String>) -> Unit,
    onPlan: (Project, String?, List<String>) -> Unit,
) {
    var previous by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var deadline by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun collect(): Triple<Project, String?, List<String>>? {
        error = when {
            name.isBlank() -> "A project name is needed."
            deadline.isNotBlank() && runCatching { LocalDate.parse(deadline.trim()) }.isFailure -> "\"$deadline\" is not a date like 2026-10-15."
            else -> null
        }
        if (error != null) return null
        val project = Project(name.trim(), priority, deadline.trim().ifBlank { null }, notes.trim())
        return Triple(project, previous, steps.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (previous == null) "New project" else "Project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projects.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        projects.take(12).forEach { p ->
                            FilterChip(
                                selected = previous == p.name,
                                onClick = {
                                    previous = p.name; name = p.name; priority = p.priority
                                    deadline = p.deadline.orEmpty(); notes = p.notes; steps = ""
                                },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
                HelpField(name, { name = it }, "Name", "The matter or file, e.g. Smith v. Jones, Tax 2026. Tap an existing one above to change it.")
                HelpLabel("Priority", "Every task of this project takes this priority. The most important work gets the first free cells.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(Priority.LOW to "Low", Priority.NORMAL to "Normal", Priority.HIGH to "High", Priority.CRITICAL to "Critical").forEach { (p, label) ->
                            FilterChip(priority == p, { priority = p }, label = { Text(label) })
                        }
                    }
                }
                HelpField(deadline, { deadline = it }, "Deadline (YYYY-MM-DD)", "The final date. Its tasks are planned to finish at least one day before.")
                HelpField(notes, { notes = it }, "Notes", "What the project is about: client, other party, what's at stake. The assistant reads it when it plans.", singleLine = false, minLines = 2)
                HelpField(
                    steps, { steps = it }, "Steps (optional)",
                    "One per line. Each step takes one cell, on different days, in this order. Or leave empty and plan it with the assistant.",
                    singleLine = false, minLines = 2,
                )
                OutlinedButton(onClick = { collect()?.let { (p, prev, s) -> onPlan(p, prev, s) } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(MicIcon, contentDescription = null)
                    Text("  Plan it with the assistant")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { collect()?.let { (p, prev, s) -> onSave(p, prev, s) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
