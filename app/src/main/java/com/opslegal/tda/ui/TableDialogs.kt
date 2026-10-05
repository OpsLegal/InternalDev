package com.opslegal.tda.ui

import androidx.compose.material3.LinearProgressIndicator

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.DayLabel
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Projects
import java.time.LocalDate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opslegal.tda.core.model.Outcome

/** What the table shows on top of itself. */
internal sealed interface TableDialog {
    data class CellMenu(val stepId: String, val date: String) : TableDialog
    data class Chooser(val project: Boolean) : TableDialog
    data object PickProject : TableDialog
    data object PickTask : TableDialog
    data class NewTask(val date: String?) : TableDialog
    data class EditTask(val taskId: String) : TableDialog
    data class Project(val name: String?) : TableDialog
    data class Extend(val stepId: String) : TableDialog
    data class ConfirmCancel(val stepId: String, val all: Boolean) : TableDialog
    data class MakeRoom(val stepId: String, val how: Projects.Extension, val related: String) : TableDialog
    data class Risk(val project: String, val change: (Board) -> Board, val doneText: String?, val end: Projects.End) : TableDialog
}

/** Opens the right dialog for [dialog]; every change goes through the view model, with its impact shown. */
@Composable
internal fun TableDialogs(
    vm: MainViewModel,
    board: Board,
    dayLanguage: String,
    dialog: TableDialog?,
    onDialog: (TableDialog?) -> Unit,
    /** Talk to the assistant: by voice about a day (text null), or with a prepared message. */
    onTalk: (LocalDate?, String?) -> Unit,
) {
    val today = LocalDate.now()
    val close = { onDialog(null) }

    /** Applies a change; for a project, shows where it now ends, and asks first if it would miss the deadline. */
    /**
     * Applies a change to a cell. For a project step, warns first only when the change makes the project end
     * later than now AND too close to (or after) its deadline. Done and To do never delay anything: no warning.
     */
    fun act(task: Task, change: (Board) -> Board, doneText: String?, quiet: Boolean = false, check: Boolean = true) {
        if (!task.isProject || !check) { vm.apply(change, task.project.ifBlank { null }, doneText, quiet); close(); return }
        val before = vm.impactOf(task.project) { it }
        val end = vm.impactOf(task.project, change)
        val worse = when {
            end.end == null -> end.open > 0 && before.end != null
            before.end == null -> false
            else -> end.end!!.isAfter(before.end)
        }
        if (end.late && worse) onDialog(TableDialog.Risk(task.project, change, doneText, end))
        else { vm.apply(change, task.project, doneText, quiet); close() }
    }

    when (val d = dialog) {
        null -> Unit
        is TableDialog.CellMenu -> {
            val found = BoardOps.findStep(board, d.stepId) ?: return close()
            val (task, step) = found
            val missed = BoardOps.isMissed(step, today)
            CellMenu(
                board, task, step.id, d.date, today,
                onDismiss = close,
                onDone = { act(task, { BoardOps.setStepDone(it, step.id, true) }, null, quiet = true, check = false) },
                onReopen = { act(task, { BoardOps.reopenStep(it, step.id) }, null, quiet = true, check = false) },
                onPush = {
                    act(task, { BoardOps.pushStep(it, step.id, today) }, if (missed) "Again later. The red cell stays as your record." else "Pushed.")
                },
                onCancel = { onDialog(TableDialog.ConfirmCancel(step.id, all = false)) },
                onCancelAll = { onDialog(TableDialog.ConfirmCancel(step.id, all = true)) },
                onEdit = { onDialog(if (task.isProject) TableDialog.Project(task.project) else TableDialog.EditTask(task.id)) },
                onProject = { onDialog(TableDialog.Project(task.project)) },
                onTalk = {
                    close()
                    if (task.isProject) onTalk(null, "About the project ${task.project}, step \"${step.title}\" (${d.date}): ")
                    else onTalk(LocalDate.parse(d.date), null)
                },
                onExtend = { onDialog(TableDialog.Extend(step.id)) },
            )
        }
        is TableDialog.ConfirmCancel -> {
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            val others = task.steps.count { !it.closed && it.id != step.id }
            val name = if (task.isProject) step.title else task.title
            if (!d.all) {
                ConfirmChoices(
                    "Delete “$name”?", "Its cell is freed for something else.", close,
                    Choice(if (task.isProject) "Delete step" else "Delete task", Pewter, Icons.Filled.Close) {
                        act(task, { BoardOps.cancelStep(it, step.id, today) }, if (task.isProject) "Step deleted." else "Task deleted.")
                    },
                    Choice("Push to later", Slate, PushIcon) { act(task, { BoardOps.pushStep(it, step.id, today) }, "Pushed.") },
                )
            } else if (task.isProject) {
                ConfirmChoices(
                    "Delete the project “${task.project}”?", "${others + 1} ${if (others > 0) "cells are" else "cell is"} freed. Steps already done stay in its history.", close,
                    Choice("Delete the project", Pewter, Icons.Filled.Close) { act(task, { BoardOps.cancelTask(it, task.id, today) }, "Project deleted.") },
                    Choice("One week later", Slate, PushIcon) { vm.apply({ BoardOps.pushProjectWeek(it, task.project, today) }, task.project, "Moved one week later."); close() },
                )
            } else {
                ConfirmChoices(
                    "Delete all ${others + 1} cells of “${task.title}”?", "They are freed for something else.", close,
                    Choice("Delete them", Pewter, Icons.Filled.Close) { act(task, { BoardOps.cancelTask(it, task.id, today) }, null) },
                    Choice("Push to later", Slate, PushIcon) { act(task, { BoardOps.pushStep(it, step.id, today) }, "Pushed.") },
                )
            }
        }
        is TableDialog.Risk -> SoftDialog(
            onDismissRequest = close,
            title = { Text("Deadline at risk") },
            text = {
                val endDay = d.end.end
                val deadline = d.end.deadline
                Text(
                    when {
                        endDay == null -> "${d.project} would have a step with no free cell before its deadline (${deadline?.let(vm::dayName)})."
                        deadline != null && endDay.isAfter(deadline) -> "${d.project} would end ${vm.dayName(endDay)}, after its deadline (${vm.dayName(deadline)})."
                        else -> "${d.project} would end ${vm.dayName(endDay)}, on its deadline day (${deadline?.let(vm::dayName)}), with no margin left."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { vm.apply(d.change, d.project, d.doneText); close() }) { Text("Do it anyway") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { close(); onTalk(null, "${d.project} would end after its deadline (${d.end.deadline}) with this change. Propose options.") }) { Text("Ask the assistant") }
                    TextButton(onClick = close) { Text("Cancel") }
                }
            },
        )
        is TableDialog.Extend -> {
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            ExtendDialog(Planner.cellTitle(task, step), close) { how, related ->
                val ext = Projects.extend(board, step.id, how, related, today) ?: return@ExtendDialog close()
                val becameNote = if (ext.becameProject) "“${task.title}” needs several cells, so it is now a project (green)." else null
                if (!Projects.isFull(ext.board, ext.day)) {
                    val change = { b: Board -> Projects.extend(b, step.id, how, related, today)?.let { e -> BoardOps.placeStep(e.board, e.stepToPlace, e.day) ?: e.board } ?: b }
                    vm.apply(change, ext.board.tasks.first { it.id == ext.taskId }.project, becameNote)
                    close()
                } else onDialog(TableDialog.MakeRoom(step.id, how, related))
            }
        }
        is TableDialog.MakeRoom -> {
            val ext = Projects.extend(board, d.stepId, d.how, d.related, today) ?: return close()
            val project = ext.board.tasks.first { it.id == ext.taskId }.project
            val victim = Projects.movableOn(ext.board, ext.day, ext.taskId, today)
            val dayText = vm.dayName(ext.day)
            val redo = { b: Board -> Projects.extend(b, d.stepId, d.how, d.related, today) }
            SoftDialog(
                onDismissRequest = close,
                title = { Text("$dayText is full") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (victim != null) "Move “${Planner.cellTitle(victim.first, victim.second)}” to a later day to make room?"
                            else "Nothing on $dayText can move by itself (meetings, deadlines or cells you placed).",
                        )
                        if (victim != null) Text("It is the least important cell that day, given your priorities and values.", style = MaterialTheme.typography.bodySmall)
                        if (victim != null) Button(onClick = {
                            vm.apply({ b -> redo(b)?.let { e -> Projects.makeRoom(e.board, victim.second.id, e.stepToPlace, e.day) } ?: b }, project, "Made room on $dayText."); close()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Yes, move it") }
                        OutlinedButton(onClick = {
                            vm.apply({ b -> redo(b)?.let { e -> BoardOps.unschedule(e.board, e.stepToPlace, e.day.toString()) } ?: b }, project)
                            close()
                            onTalk(null, "I need one more cell on $dayText (${ext.day}) for the project $project. That day is full. Move the least important cell to another day, and tell me what you moved.")
                        }, modifier = Modifier.fillMaxWidth()) { Text("Ask the assistant") }
                        OutlinedButton(onClick = {
                            vm.apply({ b -> redo(b)?.let { e -> BoardOps.unschedule(e.board, e.stepToPlace, e.day.toString()) } ?: b }, project); close()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Next free day instead") }
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
        is TableDialog.Chooser -> {
            val count = if (d.project) board.projects.size else board.tasks.count { !it.isProject && !it.isDone }
            val word = if (d.project) "project" else "task"
            SoftDialog(
                onDismissRequest = close,
                title = { Text(if (d.project) "Project" else "Task") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(if (d.project) NewProjectIcon else NewTaskIcon, "Create a $word", if (d.project) Bordeaux else Slate,
                                onClick = { onDialog(if (d.project) TableDialog.Project(null) else TableDialog.NewTask(null)) }, label = "Create")
                            RoundAction(Icons.Filled.Edit, "Modify a $word", Navy, enabled = count > 0,
                                onClick = { onDialog(if (d.project) TableDialog.PickProject else TableDialog.PickTask) }, label = "Modify")
                        }
                        if (count == 0) Text("No $word to modify yet.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text("Close") } },
            )
        }
        TableDialog.PickProject -> PickList(
            "Modify a project",
            board.projects.map { p -> Triple(p.name, p.name, listOfNotNull(p.priority.name.lowercase(), p.deadline?.let { "due $it" }).joinToString(" · ")) },
            close,
        ) { onDialog(TableDialog.Project(it)) }
        TableDialog.PickTask -> PickList(
            "Modify a task",
            board.tasks.filter { !it.isProject && !it.isDone }.map { t -> Triple(t.id, t.title, t.steps.firstOrNull { !it.closed }?.date.orEmpty()) },
            close,
        ) { onDialog(TableDialog.EditTask(it)) }
        is TableDialog.NewTask -> TaskDialog(
            task = null, day = d.date,
            dayLabel = d.date?.let { DayLabel.of(LocalDate.parse(it), dayLanguage) },
            projects = board.projects.map { it.name }, values = board.values.map { it.name },
            onDismiss = close,
            onSave = { spec, chosen ->
                val day = d.date?.let(LocalDate::parse) ?: chosen
                if (day != null) vm.addTaskOn(spec, day) else vm.apply({ b -> BoardOps.add(b, spec, today).board })
                close()
            },
            onSpeak = { chosen -> close(); onTalk(d.date?.let(LocalDate::parse) ?: chosen, null) },
            draft = vm::draftTask,
            assistantFirst = vm.settings.value.hasApiKey,
        )
        is TableDialog.EditTask -> {
            val task = board.tasks.firstOrNull { it.id == d.taskId } ?: return close()
            val currentDay = task.steps.firstOrNull { !it.closed }?.date?.let(LocalDate::parse)
            TaskDialog(
                task = task, day = null, dayLabel = null,
                projects = board.projects.map { it.name }, values = board.values.map { it.name },
                onDismiss = close,
                onSave = { spec, chosen -> vm.updateTask(task.id, spec, chosen); close() },
                onDelete = { vm.edit { BoardOps.deleteTask(it, task.id) }; close() },
                draft = { words -> vm.draftTask(words, task, currentDay) },
                assistantFirst = vm.settings.value.hasApiKey,
                currentDay = currentDay,
                dayName = vm::dayName,
            )
        }
        is TableDialog.Project -> ProjectDialog(vm, board, d.name, close)
    }
}

internal class Choice(val label: String, val color: Color, val icon: ImageVector, val onClick: () -> Unit)

/** "Are you sure?" with big buttons: the destructive choice, a gentler one, and Keep. */
@Composable
internal fun ConfirmChoices(title: String, detail: String, onKeep: () -> Unit, vararg choices: Choice) {
    SoftDialog(
        onDismissRequest = onKeep,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    choices.forEach { c -> RoundAction(c.icon, c.label, c.color, c.onClick, label = c.label.substringBefore(" the ")) }
                    RoundAction(UndoIcon, "Keep", Navy, onKeep, label = "Keep")
                }
            }
        },
        confirmButton = {},
    )
}

/** What you can do with one cell: big buttons, easy to hit with one thumb. A project cell works on its project. */
@Composable
private fun CellMenu(
    board: Board,
    task: Task,
    stepId: String,
    date: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    onReopen: () -> Unit,
    onPush: () -> Unit,
    onCancel: () -> Unit,
    onCancelAll: () -> Unit,
    onEdit: () -> Unit,
    onProject: () -> Unit,
    onTalk: () -> Unit,
    onExtend: () -> Unit,
) {
    val step = task.steps.first { it.id == stepId }
    val open = !step.closed
    val others = task.steps.count { !it.closed && it.id != stepId }
    val project = BoardOps.findProject(board, task.project)
    val kind = task.kindOf(step)
    val effort = task.effortOf(step)
    val kept = task.steps.filter { it.outcome == null }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                if (project != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Project ", style = MaterialTheme.typography.bodySmall)
                        Text(
                            project.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline,
                            color = kindColor(TaskKind.TASK, inProject = true),
                            modifier = Modifier.clickable(onClick = onProject),
                        )
                        Text(" · step ${kept.indexOfFirst { it.id == stepId } + 1} of ${kept.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (project != null) step.title else task.title,
                    color = kindColor(kind, inProject = project != null),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val description = step.description.ifBlank { project?.notes ?: task.description }
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    "meeting".takeIf { kind == TaskKind.MEETING },
                    "deadline".takeIf { kind == TaskKind.DEADLINE },
                    "heavy".takeIf { effort == Effort.HEAVY },
                    "light".takeIf { effort == Effort.LIGHT },
                    (project?.deadline ?: task.deadline)?.let { "due $it" },
                    "done".takeIf { step.done },
                    step.outcome?.name?.lowercase(),
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                val missed = BoardOps.isMissed(step, today)
                val record = step.outcome == Outcome.MISSED
                if (missed || record) {
                    Text(
                        if (record) "Not done on this day. Its work went on to another day; this red cell stays as your record."
                        else "Not done on its day. Done since? Tap Done. Still to do? Again later puts it in a free cell; this red cell stays as your record.",
                        style = MaterialTheme.typography.bodySmall, color = MissedInk,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MissedRed).padding(8.dp),
                    )
                }
                // "Delete", not "Cancel": cancel reads like closing this window.
                val deleteLabel = if (project != null) "Delete step" else "Delete task"
                val pushLabel = if (missed) "Again later" else "Push"
                val actions = buildList {
                    if (record) {
                        add(Triple("Talk", MicIcon, onTalk))
                        return@buildList
                    }
                    if (open) {
                        add(Triple("Done", Icons.Filled.Check, onDone))
                        add(Triple(pushLabel, PushIcon, onPush))
                        add(Triple(deleteLabel, Icons.Filled.Close, onCancel))
                    } else add(Triple("To do", UndoIcon, onReopen))
                    add(Triple("Edit", Icons.Filled.Edit, onEdit))
                    add(Triple("Talk", MicIcon, onTalk))
                    add(Triple("Extend", ExtendIcon, onExtend))
                }
                actions.chunked(3).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        line.forEach { (label, icon, action) ->
                            val (color, content) = when (label) {
                                "Done" -> DoneYellow to DoneInk
                                pushLabel, "Extend", "To do" -> Slate to Color.White
                                deleteLabel -> Pewter to Color.White
                                else -> Navy to Color.White
                            }
                            RoundAction(icon, label, color, action, contentColor = content, label = label)
                        }
                        repeat(3 - line.size) { Box(Modifier.width(64.dp)) }
                    }
                }
                if (open && others > 0 && !record) {
                    TextButton(onClick = onCancelAll, modifier = Modifier.fillMaxWidth()) {
                        Text(if (project != null) "Delete the whole project ($others more ${if (others > 1) "cells" else "cell"})" else "Delete all ${others + 1} cells of this task")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Extend: this task needs one more cell, either more effort or a related task that comes first. */
@Composable
private fun ExtendDialog(title: String, onDismiss: () -> Unit, onExtend: (Projects.Extension, String) -> Unit) {
    var related by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This task needs one more cell:", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(MoreEffortIcon, "Extend effort: one more cell of the same work", Slate,
                        onClick = { onExtend(Projects.Extension.MORE_EFFORT, "") }, label = "Extend effort")
                    RoundAction(RelatedIcon, "Related task: something needed to finish this task", Navy,
                        onClick = { related = true }, label = "Related task")
                }
                if (related) {
                    HelpField(text, { text = it; error = null }, "Related task",
                        "Something needed to finish this task. It takes this cell, and this task moves to the next day.")
                    Button(onClick = { if (text.isBlank()) error = "Say in a few words what the related task is." else onExtend(Projects.Extension.RELATED_TASK, text.trim()) },
                        modifier = Modifier.fillMaxWidth()) { Text("Add it") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A searchable list to choose what to modify. Items: key, text, detail. */
@Composable
private fun PickList(title: String, items: List<Triple<String, String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactField(query, { query = it }, "Search")
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val shown = items.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
                    if (shown.isEmpty()) Text("Nothing matches.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    shown.forEach { (key, text, detail) ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(key) }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(text)
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Creates or edits a one-cell task: title, notes, type, effort, values and, optionally, the project it becomes
 * a step of. Priority and deadline belong to projects.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDialog(
    task: Task?,
    day: String?,
    dayLabel: String?,
    projects: List<String>,
    values: List<String>,
    onDismiss: () -> Unit,
    onSave: (BoardOps.NewTask, LocalDate?) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSpeak: ((LocalDate?) -> Unit)? = null,
    /** Assistant mode for a new task: the AI fills the form from the user's words. */
    draft: (suspend (String) -> MainViewModel.DraftTask)? = null,
    assistantFirst: Boolean = false,
    /** Editing: the cell's day now; the form shows it and lets the user (or the assistant) change it. */
    currentDay: LocalDate? = null,
    dayName: (LocalDate) -> String = { it.toString() },
) {
    val today = LocalDate.now()
    var withAssistant by remember { mutableStateOf(draft != null && assistantFirst) }
    var editDay by remember { mutableStateOf(currentDay?.toString().orEmpty()) }
    var proposal by remember { mutableStateOf<String?>(null) }
    var describe by remember { mutableStateOf("") }
    var drafted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var otherDay by remember { mutableStateOf("") }
    var askOther by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.description.orEmpty()) }
    var project by remember { mutableStateOf("") }
    var projectFocused by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(task?.kind ?: TaskKind.TASK) }
    var effort by remember { mutableStateOf(task?.effort ?: Effort.NORMAL) }
    var effortTouched by remember { mutableStateOf(task?.effortByUser == true) }
    var serves by remember { mutableStateOf(task?.values.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (task == null) (dayLabel?.let { "New task · $it" } ?: "New task") else "Edit task", modifier = Modifier.weight(1f))
                if (task == null && onSpeak != null) {
                    IconButton(onClick = { onSpeak(day?.let(LocalDate::parse) ?: picked) }) { Icon(MicIcon, contentDescription = "Say it to the assistant instead") }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft != null) {
                    Tags(listOf(true to "Assistant mode", false to "Manual mode"), withAssistant, { withAssistant = it })
                }
                if (task == null && day == null) {
                    // When: the first free cell, today, tomorrow, or from a chosen day on.
                    val whenChoice = when {
                        askOther -> "from"
                        picked == null -> "next"
                        picked == today -> "today"
                        picked == today.plusDays(1) -> "tomorrow"
                        else -> "from"
                    }
                    val fromLabel = picked?.takeIf { askOther }?.let { "From " + it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + it.dayOfMonth } ?: "From a day"
                    Tags(
                        listOf("next" to "Next free", "today" to "Today", "tomorrow" to "Tomorrow", "from" to fromLabel), whenChoice,
                        { c ->
                            askOther = c == "from"
                            picked = when (c) {
                                "today" -> today
                                "tomorrow" -> today.plusDays(1)
                                "from" -> runCatching { LocalDate.parse(otherDay) }.getOrNull()
                                else -> null
                            }
                        },
                    )
                    if (askOther) DateField("From this day on (first free cell)", otherDay, { otherDay = it; picked = runCatching { LocalDate.parse(it) }.getOrNull() })
                }
                if (withAssistant && !drafted) {
                    if (task != null) {
                        Text("Now: ${currentDay?.let(dayName) ?: "not placed yet"}", style = MaterialTheme.typography.bodySmall, color = projectBarColor())
                    }
                    HelpField(describe, { describe = it }, if (task == null) "Describe the task" else "What should change?",
                        if (task == null) "In your own words: what, for whom, any day or deadline. The assistant fills the form; you check it, then Add."
                        else "E.g. \"move it to next week\", \"it's a call, not a task\", \"lighter\". The assistant proposes the changes; you check them, then Save.",
                        singleLine = false, minLines = 3)
                    Button(enabled = !busy && describe.isNotBlank(), onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val t = draft!!(describe.trim())
                                if (task != null) {
                                    // A change: say what changes, then show the form with it, for the user to accept.
                                    proposal = buildList {
                                        if (t.title != title) add("title “${t.title}”")
                                        if (t.day != null && t.day.toString() != editDay) add("day ${currentDay?.let(dayName) ?: "–"} → ${dayName(t.day)}")
                                        if (t.kind != kind) add("type ${t.kind.name.lowercase()}")
                                        if (t.effort != effort) add("effort ${t.effort.name.lowercase()}")
                                        if (t.notes != notes) add("notes")
                                    }.let { list -> (if (list.isEmpty()) "No change proposed." else "Proposed: " + list.joinToString("; ") + ".") + t.reason.let { if (it.isBlank()) "" else " $it" } }
                                    if (t.day != null) editDay = t.day.toString()
                                }
                                title = t.title; notes = t.notes; kind = t.kind; effort = t.effort
                                if (t.project.isNotEmpty()) project = t.project
                                if (task == null && day == null && t.day != null) { askOther = true; otherDay = t.day.toString(); picked = t.day }
                                drafted = true
                            } catch (e: Exception) {
                                error = e.message ?: "The task could not be prepared. Try again, or use Manual mode."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(if (task == null) "Prepare the task" else "Propose changes") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    return@Column
                }
                proposal?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                if (withAssistant) Text(if (task == null) "Check it, then Add." else "Change anything you want, then Save to accept.", style = MaterialTheme.typography.bodySmall)
                if (task != null) DateField("Day", editDay, { editDay = it })
                HelpField(title, { title = it }, "Title", "What the cell shows. Keep it short; it can stay discreet.")
                HelpField(notes, { notes = it }, "Notes",
                    "What it is, why it matters, any context. Needed so the assistant understands the task. The cell only shows the title.",
                    singleLine = false, minLines = 2)
                HelpLabel("Type", "Blue: a one-cell task. Green: a step of a project. Black: a meeting or call. Red: a delivery, filing or deadline due that day.") {
                    Tags(
                        listOf(TaskKind.TASK to "Task", TaskKind.MEETING to "Meeting", TaskKind.DEADLINE to "Deadline"), kind, { kind = it },
                        color = { k -> kindColor(k) },
                    )
                }
                HelpLabel("Effort", "How heavy it feels to you. At most 2 heavy tasks a day, each with an easy first step. You can leave it: a task you push twice becomes heavy by itself.") {
                    Tags(listOf(Effort.LIGHT to "Light", Effort.NORMAL to "Normal", Effort.HEAVY to "Heavy"), effort, { effort = it; effortTouched = true })
                }
                if (values.isNotEmpty()) {
                    HelpLabel("Serves", "What this task is good for. Tasks serving what matters most to you get the earlier cells.") {
                        ValueChips(values, serves) { serves = it }
                    }
                }
                HelpField(project, { project = it }, "Add to a project (optional)",
                    "Leave empty for a one-cell task. Pick a project to add this as its next step (it turns green). A new name creates the project.",
                    onFocus = { projectFocused = it })
                if (projectFocused) {
                    val q = project.trim()
                    val matches = projects.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                    Column(Modifier.padding(end = 32.dp)) {
                        if (q.isNotEmpty() && projects.none { it.equals(q, ignoreCase = true) }) {
                            Text("Create “$q”", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth().clickable { project = q; projectFocused = false }.padding(10.dp))
                        }
                        matches.take(8).forEach { name ->
                            Text(name, modifier = Modifier.fillMaxWidth().clickable { project = name; projectFocused = false }.padding(10.dp))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Tap again to delete the task" else "Delete this task", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = when {
                    withAssistant && !drafted -> if (task == null) "Prepare the task first, or switch to Manual mode." else "Propose changes first, or switch to Manual mode."
                    title.isBlank() -> "A title is needed."
                    notes.isBlank() -> "Add a short note so the assistant understands this task."
                    askOther && picked == null -> "Choose a day."
                    else -> null
                }
                if (error == null) onSave(
                    BoardOps.NewTask(
                        title = title.trim(), description = notes.trim(), project = project.trim(), kind = kind,
                        effort = effort, effortByUser = effortTouched, values = serves,
                        priority = task?.priority ?: Priority.NORMAL, deadline = task?.deadline,
                    ),
                    if (task != null) runCatching { LocalDate.parse(editDay) }.getOrNull()?.takeIf { it != currentDay } else picked,
                )
            }) { Text(if (task == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
