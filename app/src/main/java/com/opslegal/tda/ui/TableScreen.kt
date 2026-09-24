package com.opslegal.tda.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import java.time.LocalDate

private const val DAYS_BACK = 14L
private const val DAYS_AHEAD = 60

/** The main screen: the 6-column table (day + 5 equal task cells). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TableScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val rows = remember(board, settings.dayLanguage, today) {
        Planner.rows(board, today.minusDays(DAYS_BACK), DAYS_BACK.toInt() + DAYS_AHEAD, settings.dayLanguage)
    }
    val todayIndex = rows.indexOfFirst { it.date >= today.toString() }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (todayIndex - 1).coerceAtLeast(0))
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Cell?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Header() }
            items(rows, key = { it.date }) { row ->
                DayLine(
                    row = row,
                    isToday = row.date == today.toString(),
                    isPast = row.date < today.toString(),
                    onToggle = { cell -> vm.edit { BoardOps.toggleStep(it, cell.stepId) } },
                    onOpen = { selected = it },
                )
            }
            item { Box(Modifier.height(88.dp)) }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Add task") }
    }

    if (adding) AddTaskDialog(onDismiss = { adding = false }, onAdd = { vm.addTask(it); adding = false })
    selected?.let { cell ->
        CellDialog(
            cell = cell,
            onDismiss = { selected = null },
            onToggle = { vm.edit { BoardOps.toggleStep(it, cell.stepId) }; selected = null },
            onTomorrow = {
                vm.edit { b -> BoardOps.moveStep(b, cell.stepId, nextWorkDay(today, b.settings.workDays)) ?: b }
                selected = null
            },
            onDelete = { vm.edit { BoardOps.deleteTask(it, cell.taskId) }; selected = null },
        )
    }
}

private fun nextWorkDay(from: LocalDate, workDays: List<Int>): LocalDate {
    var d = from.plusDays(1)
    while (d.dayOfWeek.value !in workDays) d = d.plusDays(1)
    return d
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
    onToggle: (Cell) -> Unit,
    onOpen: (Cell) -> Unit,
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
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape).background(background).border(1.dp, outline, shape)
                    .then(
                        if (cell != null) Modifier.combinedClickable(onClick = { onToggle(cell) }, onLongClick = { onOpen(cell) })
                        else Modifier,
                    )
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (cell != null) {
                    Text(
                        cell.title,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontWeight = if (cell.priority >= Priority.HIGH) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            cell.done -> DoneInk
                            isPast -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CellDialog(
    cell: Cell,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onTomorrow: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cell.title) },
        text = {
            Column {
                if (cell.project.isNotBlank()) Text("Project: ${cell.project}")
                Text("Priority: ${cell.priority.name.lowercase()}")
                if (confirmDelete) Text("Delete the whole task and all its cells?", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onToggle) { Text(if (cell.done) "Not done" else "Done") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onTomorrow) { Text("Next day") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (BoardOps.NewTask) -> Unit) {
    var title by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var deadline by remember { mutableStateOf("") }
    var fixedDate by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(project, { project = it }, label = { Text("Project") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.name.take(4).lowercase()) })
                    }
                }
                OutlinedTextField(deadline, { deadline = it }, label = { Text("Deadline (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(fixedDate, { fixedDate = it }, label = { Text("Fixed day, for meetings (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(
                    steps, { steps = it },
                    label = { Text("Steps, one per line (optional)") },
                    minLines = 2,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val bad = listOf(deadline, fixedDate).firstOrNull { it.isNotBlank() && runCatching { LocalDate.parse(it.trim()) }.isFailure }
                error = when {
                    title.isBlank() -> "A title is needed."
                    bad != null -> "\"$bad\" is not a date like 2026-10-15."
                    else -> null
                }
                if (error == null) {
                    onAdd(
                        BoardOps.NewTask(
                            title = title,
                            project = project,
                            priority = priority,
                            deadline = deadline.trim().ifBlank { null },
                            fixedDate = fixedDate.trim().ifBlank { null },
                            stepTitles = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                    )
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
