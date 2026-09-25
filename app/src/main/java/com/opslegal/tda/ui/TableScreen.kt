package com.opslegal.tda.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
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
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.DayLabel
import com.opslegal.tda.core.plan.Planner
import java.time.LocalDate

private const val DAYS_BACK = 14L
private const val DAYS_AHEAD = 60

/** What the table is currently showing on top of itself. */
private sealed interface TableDialog {
    data class CellMenu(val cell: Cell, val date: String) : TableDialog
    data class Add(val date: String?) : TableDialog
    data class Edit(val taskId: String, val stepId: String) : TableDialog
}

/** The main screen: the 6-column table (day + 5 equal task cells). */
@Composable
fun TableScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val rows = remember(board, settings.dayLanguage, today) {
        Planner.rows(board, today.minusDays(DAYS_BACK), DAYS_BACK.toInt() + DAYS_AHEAD, settings.dayLanguage)
    }
    val todayIndex = rows.indexOfFirst { it.date >= today.toString() }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (todayIndex - 1).coerceAtLeast(0))
    var dialog by remember { mutableStateOf<TableDialog?>(null) }
    val mic = rememberMicAction(vm)

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
            item { Box(Modifier.height(160.dp)) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            VoiceDock(vm, onMic = { mic(null) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(onClick = { dialog = TableDialog.Add(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a task yourself")
                }
                // Talk to the assistant from the table: ask, plan or add, hands busy.
                FloatingActionButton(
                    onClick = { mic(null) },
                    containerColor = DoneYellow,
                    contentColor = DoneInk,
                ) { Icon(MicIcon, contentDescription = "Talk to the assistant") }
            }
        }
    }

    when (val d = dialog) {
        is TableDialog.CellMenu -> {
            val task = board.tasks.firstOrNull { it.id == d.cell.taskId }
            if (task != null) CellMenu(
                cell = d.cell,
                task = task,
                dayLabel = DayLabel.of(LocalDate.parse(d.date), settings.dayLanguage),
                onDismiss = { dialog = null },
                onDone = { vm.setDone(d.cell.stepId, true); dialog = null },
                onReopen = { vm.reopen(d.cell.stepId); dialog = null },
                onPush = { vm.push(d.cell.stepId); dialog = null },
                onCancel = { vm.cancelCell(d.cell.stepId); dialog = null },
                onCancelTask = { vm.cancelTask(task.id); dialog = null },
                onEdit = { dialog = TableDialog.Edit(task.id, d.cell.stepId) },
                onAddHere = { dialog = TableDialog.Add(d.date) },
            )
        }
        is TableDialog.Add -> TaskDialog(
            task = null,
            stepId = null,
            day = d.date,
            dayLabel = d.date?.let { DayLabel.of(LocalDate.parse(it), settings.dayLanguage) },
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
                onDismiss = { dialog = null },
                onSave = { spec, cellText ->
                    vm.updateTask(task.id, d.stepId, spec, cellText)
                    dialog = null
                },
                onDelete = { vm.edit { BoardOps.deleteTask(it, task.id) }; dialog = null },
            )
        }
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
                        color = when {
                            cell.done -> DoneInk
                            cell.outcome != null -> MaterialTheme.colorScheme.onSurfaceVariant
                            isPast -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** What you can do with one cell: tap it and choose. */
@Composable
private fun CellMenu(
    cell: Cell,
    task: Task,
    dayLabel: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onReopen: () -> Unit,
    onPush: () -> Unit,
    onCancel: () -> Unit,
    onCancelTask: () -> Unit,
    onEdit: () -> Unit,
    onAddHere: () -> Unit,
) {
    val open = !cell.done && cell.outcome == null
    val otherOpenSteps = task.steps.count { !it.closed && it.id != cell.stepId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cell.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.description.ifBlank { "No explanation yet. Add one with Edit so the assistant understands this task." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.description.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                val facts = listOfNotNull(
                    task.project.takeIf { it.isNotBlank() },
                    "priority ${task.priority.name.lowercase()}",
                    task.deadline?.let { "due $it" },
                    when {
                        cell.done -> "done"
                        cell.outcome == Outcome.PUSHED -> "pushed"
                        cell.outcome == Outcome.CANCELLED -> "cancelled"
                        else -> null
                    },
                )
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                HorizontalDivider()
                if (open) {
                    MenuAction("Done: turn it yellow", onDone)
                    MenuAction("Push to a later day (grey here)", onPush)
                    MenuAction("Cancel this cell (grey)", onCancel)
                    if (otherOpenSteps > 0) MenuAction("Cancel the whole task ($otherOpenSteps more cells)", onCancelTask)
                } else {
                    MenuAction("Back to to-do", onReopen)
                }
                MenuAction("Edit the title and explanation", onEdit)
                MenuAction("Add a new task on $dayLabel", onAddHere)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

/**
 * Adds a task (when [task] is null) or edits one. The explanation is required: it is what the
 * assistant reads to understand a title that may be short or deliberately discreet.
 */
@Composable
private fun TaskDialog(
    task: Task?,
    stepId: String?,
    day: String?,
    dayLabel: String?,
    onDismiss: () -> Unit,
    onSave: (BoardOps.NewTask, String?) -> Unit,
    onDelete: (() -> Unit)? = null,
    /** New task on a day picked in the form (when it wasn't opened from a cell). */
    onChooseDay: ((BoardOps.NewTask, LocalDate) -> Unit)? = null,
    /** Close the form and tell the assistant instead, about [day] or the day picked. */
    onSpeak: ((LocalDate?) -> Unit)? = null,
) {
    val today = LocalDate.now()
    // For a new task opened with +: which day. null = the next free cell.
    var pickedDay by remember { mutableStateOf<LocalDate?>(null) }
    var otherDay by remember { mutableStateOf("") }
    val step = task?.steps?.firstOrNull { it.id == stepId }
    val multiStep = (task?.steps?.size ?: 0) > 1
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var description by remember { mutableStateOf(task?.description.orEmpty()) }
    var cellText by remember { mutableStateOf(step?.title.orEmpty()) }
    var project by remember { mutableStateOf(task?.project.orEmpty()) }
    var priority by remember { mutableStateOf(task?.priority ?: Priority.NORMAL) }
    var deadline by remember { mutableStateOf(task?.deadline.orEmpty()) }
    var fixedDate by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) (dayLabel?.let { "New task on $it" } ?: "New task") else "Edit task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task == null && day == null) {
                    Text("Which day?", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(pickedDay == null && otherDay.isBlank(), { pickedDay = null; otherDay = "" }, label = { Text("Next free") })
                        FilterChip(pickedDay == today, { pickedDay = today; otherDay = "" }, label = { Text("Today") })
                        FilterChip(pickedDay == today.plusDays(1), { pickedDay = today.plusDays(1); otherDay = "" }, label = { Text("Tomorrow") })
                    }
                    OutlinedTextField(
                        otherDay,
                        { otherDay = it; pickedDay = runCatching { LocalDate.parse(it.trim()) }.getOrNull() },
                        label = { Text("Or another day (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }
                if (task == null && onSpeak != null) {
                    OutlinedButton(onClick = { onSpeak(day?.let(LocalDate::parse) ?: pickedDay) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(MicIcon, contentDescription = null)
                        Text("  Say it to the assistant instead")
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title (what the cell shows)") }, singleLine = true)
                OutlinedTextField(
                    description, { description = it },
                    label = { Text("Explanation (for you and the assistant)") },
                    supportingText = { Text("What it is, why it matters, any context. The title can stay short or discreet.") },
                    minLines = 3,
                )
                if (multiStep) {
                    OutlinedTextField(cellText, { cellText = it }, label = { Text("Text of this cell") }, singleLine = true)
                }
                OutlinedTextField(project, { project = it }, label = { Text("Project") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name.take(4).lowercase()) })
                    }
                }
                OutlinedTextField(deadline, { deadline = it }, label = { Text("Deadline (YYYY-MM-DD)") }, singleLine = true)
                if (task == null) {
                    OutlinedTextField(steps, { steps = it }, label = { Text("Steps, one per line (optional)") }, minLines = 2)
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
                val bad = listOf(deadline, fixedDate).firstOrNull { it.isNotBlank() && runCatching { LocalDate.parse(it.trim()) }.isFailure }
                error = when {
                    title.isBlank() -> "A title is needed."
                    description.isBlank() -> "Add a short explanation so the assistant understands this task."
                    bad != null -> "\"$bad\" is not a date like 2026-10-15."
                    else -> null
                }
                if (error == null && otherDay.isNotBlank() && pickedDay == null) error = "\"$otherDay\" is not a date like 2026-10-15."
                if (error == null && task == null && day == null && pickedDay != null && onChooseDay != null) {
                    onChooseDay(
                        BoardOps.NewTask(
                            title = title,
                            description = description,
                            project = project,
                            priority = priority,
                            deadline = deadline.trim().ifBlank { null },
                            stepTitles = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                        pickedDay!!,
                    )
                } else if (error == null) {
                    onSave(
                        BoardOps.NewTask(
                            title = title,
                            description = description,
                            project = project,
                            priority = priority,
                            deadline = deadline.trim().ifBlank { null },
                            fixedDate = fixedDate.trim().ifBlank { null },
                            stepTitles = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                        if (multiStep) cellText.trim().ifBlank { null } else null,
                    )
                }
            }) { Text(if (task == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
