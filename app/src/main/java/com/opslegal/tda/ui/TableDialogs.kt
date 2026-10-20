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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
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
    /** A cell moved by hand onto an empty cell (day and position). */
    data class Drop(val stepId: String, val date: String, val slot: Int) : TableDialog
    /** Done with extras still open: were they done too? */
    data class RidersDone(val stepId: String) : TableDialog
    data class Chooser(val project: Boolean) : TableDialog
    /** One round button for both: create or modify a task or a project. */
    data object Work : TableDialog
    data object PickProject : TableDialog
    data object PickTask : TableDialog
    /** [prefill]: everything the assistant already worked out for an item of the bell ([fromUpdate]). */
    data class NewTask(val date: String?, val prefill: Prefill? = null, val fromUpdate: String? = null, val thenReply: Boolean = false) : TableDialog
    data class EditTask(val taskId: String) : TableDialog
    /** [fromTask]: "Make it a project": the form opens filled from that task. */
    data class Project(val name: String?, val fromTask: String? = null, val start: String? = null) : TableDialog
    /** An empty cell: "Add to Wed 14 a:" task, project or expense. */
    data class DayAdd(val date: String) : TableDialog
    data class Docs(val stepId: String) : TableDialog
    /** ＋ Expense: of a cell ([stepId]), or of a day ([date]). */
    data class Expense(val stepId: String?, val date: String?) : TableDialog
    data object DocumentTask : TableDialog
    data object MindNote : TableDialog
    /** Several projects at once: a list (typed or said), then created as they are, or with ✨ steps for each. */
    data object SeveralProjects : TableDialog
    /** Connect tasks: one-cell tasks that belong together become one project. */
    data class Connect(val stepId: String) : TableDialog
    data class Extend(val stepId: String) : TableDialog
    /** Push: one tap for why (or a few words), kept for the weekly review. */
    data class Push(val stepId: String) : TableDialog
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
        is TableDialog.Drop -> {
            // The same path as every change: a project shows where it now ends; a deadline at risk asks first. Undo is offered.
            val (task, _) = BoardOps.findStep(board, d.stepId) ?: return close()
            val day = LocalDate.parse(d.date)
            vm.rememberForUndo()
            act(task, { b -> com.opslegal.tda.core.plan.Moves.drop(b, d.stepId, day, d.slot, today) ?: b }, "Moved to ${vm.dayName(day)}.")
        }
        is TableDialog.CellMenu -> {
            val found = BoardOps.findStep(board, d.stepId) ?: return close()
            val (task, step) = found
            val missed = BoardOps.isMissed(step, today)
            CellMenu(
                board, task, step.id, d.date, today,
                onDismiss = close,
                onDone = {
                    if (step.riders.any { !it.done }) onDialog(TableDialog.RidersDone(step.id))
                    else act(task, { BoardOps.setStepDone(it, step.id, true) }, null, quiet = true, check = false)
                },
                onRider = { id, done -> vm.setRiderDone(step.id, id, done) },
                onReopen = { act(task, { BoardOps.reopenStep(it, step.id) }, null, quiet = true, check = false) },
                onPush = { onDialog(TableDialog.Push(step.id)) },
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
                onConvert = { onDialog(TableDialog.Project(null, fromTask = task.id)) },
                onDocs = { onDialog(TableDialog.Docs(step.id)) },
                onExpense = { onDialog(TableDialog.Expense(step.id, null)) },
                onExpenses = { close(); vm.expensesOpen.value = true },
                onConnect = { onDialog(TableDialog.Connect(step.id)) },
                onSaveLevels = { levels, learn -> vm.saveLevels(task.id, levels, if (task.isProject) step.title else task.title, learn) },
                onNote = { action, note -> vm.cellNote(step.id, action, note) },
                onPushTo = { day, note ->
                    vm.cellNote(step.id, if (missed) "again later" else "pushed", note)
                    act(task, { b -> Projects.pinStep(BoardOps.pushStep(b, step.id, today, note), step.id, day, today) },
                        "Moved to ${vm.dayName(day)}." + if (missed) " The red cell stays as your record." else "")
                },
                onMove = { close(); vm.moving.value = step.id },
                onPrepare = { close(); vm.prepareCell(step.id) },
                onDiscuss = { note ->
                    close()
                    vm.discussCell(if (task.isProject) step.title else task.title, task.project, d.date, missed, note)
                },
            )
        }
        is TableDialog.RidersDone -> {
            val (_, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            val open = step.riders.filter { !it.done }
            ConfirmChoices(
                "The extras too?", "Done during this cell: " + open.joinToString("; ") { it.title } + ".", close,
                Choice("All done", DoneYellow, Icons.Filled.Check) { vm.doneWithRiders(step.id, all = true); close() },
                Choice("Keep them for later", Slate, PushIcon) { vm.doneWithRiders(step.id, all = false); close() },
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
                    Choice("Push to later", Slate, PushIcon) { onDialog(TableDialog.Push(step.id)) },
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
                    Choice("Push to later", Slate, PushIcon) { onDialog(TableDialog.Push(step.id)) },
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
        is TableDialog.Push -> {
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            val missed = BoardOps.isMissed(step, today)
            PushDialog(Planner.cellTitle(task, step), missed, close,
                impact = if (task.isProject) ({ moves -> Projects.pushImpact(board, step.id, today, moves) }) else null,
                dayName = vm::dayName, board = board) { why, moves, day ->
                // A day picked on the table: the cell goes there; else the next free cell.
                act(task, { b -> BoardOps.pushStep(b, step.id, today, why, moves).let { if (day != null) Projects.pinStep(it, step.id, day, today) else it } },
                    (if (day != null) "Moved to ${vm.dayName(day)}." else if (missed) "Again later. The red cell stays as your record." else "Pushed.") +
                        (if (missed && day != null) " The red cell stays as your record." else "") + if (why.isNotBlank()) " I'll bring it up in your weekly review." else "")
            }
        }
        is TableDialog.Extend -> {
            // Extend = the same task, one more cell the same day. A full day: the lowest-impact cell is offered to push.
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            val plan = remember(d.stepId) { Projects.sameDay(board, step.id, today) } ?: return close()
            val dayText = if (plan.day == today) "today" else vm.dayName(plan.day)
            val title = Planner.cellTitle(task, step)
            if (plan.slot != null) {
                androidx.compose.runtime.LaunchedEffect(d.stepId) { vm.extendSameDay(step.id, "“$title” takes one more cell $dayText."); close() }
            } else SoftDialog(
                onDismissRequest = close,
                title = { Text("${dayText.replaceFirstChar { it.uppercase() }} is full") },
                text = {
                    Text(plan.victim?.let { (vt, vs) -> "To give “$title” one more cell, push the cell with the lowest impact: “${Planner.cellTitle(vt, vs)}”. It goes to the next free cell." }
                        ?: "Every cell $dayText is fixed or done. Make it a project instead: its next part goes to another day.")
                },
                confirmButton = {
                    if (plan.victim != null) Button(onClick = { vm.extendSameDay(step.id, "“$title” takes one more cell $dayText. “${Planner.cellTitle(plan.victim!!.first, plan.victim!!.second)}” moved later."); close() }) { Text("Push it and extend") }
                    else if (!task.isProject) Button(onClick = { onDialog(TableDialog.Project(null, fromTask = task.id)) }) { Text("Make it a project") }
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
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
        TableDialog.Work -> {
            val tasks = board.tasks.count { !it.isProject && !it.isDone }
            SoftDialog(
                onDismissRequest = close,
                title = { Text("Create a new:") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(NewTaskIcon, "Create a task", Slate, onClick = { onDialog(TableDialog.NewTask(null)) }, label = "Task")
                            RoundAction(NewProjectIcon, "Create a project", Bordeaux, onClick = { onDialog(TableDialog.Project(null)) }, label = "Project")
                            RoundAction(BulbIcon, "Park an idea", DoneYellow, contentColor = Navy, onClick = { close(); vm.openProgress("ideas") }, label = "Idea")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(NoteIcon, "Something on my mind", Pewter, onClick = { onDialog(TableDialog.MindNote) }, label = "On my mind")
                            RoundAction(MoneyIcon, "Add an expense", Navy, onClick = { onDialog(TableDialog.Expense(null, LocalDate.now().toString())) }, label = "Expense")
                            RoundAction(DocIcon, "A document to fill", Pewter, onClick = { onDialog(TableDialog.DocumentTask) }, label = "Document")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(NewProjectIcon, "Several projects at once", Bordeaux, onClick = { onDialog(TableDialog.SeveralProjects) }, label = "Several projects")
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(enabled = tasks > 0, onClick = { onDialog(TableDialog.PickTask) }) { Text("Modify a task") }
                            TextButton(enabled = board.projects.isNotEmpty(), onClick = { onDialog(TableDialog.PickProject) }) { Text("Modify a project") }
                        }
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
            board = board, task = null, day = d.date, prefill = d.prefill,
            dayLabel = d.date?.let { DayLabel.of(LocalDate.parse(it), dayLanguage) },
            projects = board.projects.map { it.name },
            onLearn = vm::learnLevels,
            onRide = { host, name, words, place -> vm.addRide(host, name, words, place); close() },
            maxDetourKm = board.settings.maxDetourKm,
            onDismiss = close,
            onSave = { spec, chosen ->
                val day = d.date?.let(LocalDate::parse) ?: chosen
                if (day != null) vm.addTaskOn(spec, day) else vm.apply({ b -> BoardOps.add(b, spec, today).board })
                d.fromUpdate?.let { vm.itemTaskAdded(it, spec.title, spec.project) }
                close()
                if (d.thenReply && d.fromUpdate != null) { vm.replyNext.value = d.fromUpdate; vm.updatesOpen.value = true }
            },
            onSpeak = { chosen -> close(); onTalk(d.date?.let(LocalDate::parse) ?: chosen, null) },
            draft = { name, words -> vm.draftTask(name, words) },
            assistantFirst = vm.settings.value.hasApiKey,
        )
        is TableDialog.EditTask -> {
            val task = board.tasks.firstOrNull { it.id == d.taskId } ?: return close()
            val currentDay = task.steps.firstOrNull { !it.closed }?.date?.let(LocalDate::parse)
            TaskDialog(
                board = board, task = task, day = null, dayLabel = null,
                projects = board.projects.map { it.name },
                onLearn = vm::learnLevels,
                onDismiss = close,
                onSave = { spec, chosen -> vm.updateTask(task.id, spec, chosen); close() },
                onDelete = { vm.edit { BoardOps.deleteTask(it, task.id) }; close() },
                draft = { name, words -> vm.draftTask(name, words, task, currentDay) },
                assistantFirst = vm.settings.value.hasApiKey,
                currentDay = currentDay,
                dayName = vm::dayName,
            )
        }
        is TableDialog.Project -> ProjectDialog(vm, board, d.name, close, fromTask = d.fromTask, start = d.start)
        is TableDialog.DayAdd -> SoftDialog(
            onDismissRequest = close,
            title = { Text("Add to ${vm.dayName(LocalDate.parse(d.date))} a:") },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(NewTaskIcon, "A task that day", Slate, onClick = { onDialog(TableDialog.NewTask(d.date)) }, label = "Task")
                    RoundAction(NewProjectIcon, "A project starting that day", Bordeaux, onClick = { onDialog(TableDialog.Project(null, start = d.date)) }, label = "Project")
                    RoundAction(MoneyIcon, "An expense of that day", Navy, onClick = { onDialog(TableDialog.Expense(null, d.date)) }, label = "Expense")
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("Close") } },
        )
        is TableDialog.Docs -> {
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            DocsDialog(vm, board, task, step, close)
        }
        is TableDialog.Expense -> {
            val found = d.stepId?.let { BoardOps.findStep(board, it) }
            ExpenseDialog(vm, board, found?.first, found?.second, null, d.date?.let(LocalDate::parse), close)
        }
        TableDialog.DocumentTask -> DocumentTaskDialog(vm, board, close)
        TableDialog.SeveralProjects -> SeveralProjectsDialog(vm, close)
        // On my mind, from the +: one line or several; they wait in Progress until the user organizes them. Nothing is planned yet.
        TableDialog.MindNote -> {
            var text by remember { mutableStateOf("") }
            SoftDialog(
                keepOpen = true, onDismissRequest = close,
                title = { Text("On my mind") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            CompactField(text, { text = it }, "What's on your mind? One per line", Modifier.weight(1f), singleLine = false, minLines = 3)
                            DictateButton(Modifier.padding(start = 6.dp, top = 7.dp)) { said -> text = listOf(text.trim(), said.trim()).filter { it.isNotBlank() }.joinToString("\n") }
                        }
                        Text("They wait in Progress › On my mind until you organize them into your table.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { Button(enabled = text.isNotBlank(), onClick = { vm.addToMind(text); vm.notify("Kept in On my mind. Tap Organize there when you want them in your table."); close() }) { Text("Keep it") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
            )
        }
        is TableDialog.Connect -> {
            val (task, _) = BoardOps.findStep(board, d.stepId) ?: return close()
            ConnectDialog(board, task, close) { ids, name, intention -> vm.connectTasks(ids, name, intention); close() }
        }
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
    onConvert: () -> Unit = {},
    onDocs: () -> Unit = {},
    onExpense: () -> Unit = {},
    onExpenses: () -> Unit = {},
    onConnect: () -> Unit = {},
    onSaveLevels: (Map<String, Int>, Boolean) -> Unit = { _, _ -> },
    onRider: (String, Boolean) -> Unit = { _, _ -> },
    /** The note typed here goes with the tap (action, note). */
    onNote: (String, String) -> Unit = { _, _ -> },
    /** "Friday" in the note: Push / Again later puts the cell on that day directly. */
    onPushTo: (LocalDate, String) -> Unit = { _, _ -> },
    onDiscuss: (String) -> Unit = {},
    /** ✨ Prepare: the brief in the format of its kind of action, from the user's own sources. */
    onPrepare: () -> Unit = {},
    /** Move: the cell lifts; tap the empty cell where it goes (the same as holding it 2 seconds). */
    onMove: () -> Unit = {},
) {
    val step = task.steps.first { it.id == stepId }
    var note by remember { mutableStateOf("") }
    val saved = remember(task, board.projects, board.serveLessons) { com.opslegal.tda.core.plan.Gbn.levelsOf(board, task) }
    var levels by remember(saved) { mutableStateOf(saved) }
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
                val intention = task.intention.ifBlank { project?.intention.orEmpty() }
                if (intention.isNotBlank()) Text("Intention: $intention", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // What it serves, as shares of the cell, and what doing it adds to the week (bigger where the week is missing it).
                if (board.gbn && board.values.isNotEmpty()) {
                    val serves = com.opslegal.tda.core.plan.Balance.servesText(board, task)
                    val gain = remember(board) { com.opslegal.tda.core.plan.Balance.gainOf(board, task, com.opslegal.tda.core.plan.Balance.of(board, java.time.LocalDate.now())) }
                    if (serves.isNotBlank()) Text("Serves: $serves" + if (!step.closed) (if (gain > 0.05) " · doing it fills a gap this week" else " · already full this week") else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.where.isNotBlank()) Text("📍 ${task.where}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (step.riders.isNotEmpty()) {
                    // Quick things done during this cell, each ticked off on its own.
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Also during this", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        step.riders.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onRider(r.id, !r.done) }) {
                                androidx.compose.material3.Checkbox(checked = r.done, onCheckedChange = { onRider(r.id, it) })
                                Text(r.title, style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                                    color = if (r.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
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
                // A note for the assistant: details for the action tapped next, or for Discuss.
                if (!record) CompactField(note, { note = it }, "📝 Your note for ${com.opslegal.tda.core.agent.Me.name(board)} (optional)",
                    Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                // "Delete", not "Cancel": cancel reads like closing this window.
                val deleteLabel = if (project != null) "Delete step" else "Delete task"
                val pushLabel = if (missed) "Again later" else "Push"
                val noteDay = com.opslegal.tda.core.plan.Updates.dayInNote(note, today)
                val actions = buildList {
                    if (record) {
                        add(Triple("Talk", MicIcon, onTalk))
                        return@buildList
                    }
                    if (open) {
                        add(Triple("Done", Icons.Filled.Check, { onNote("done", note); onDone() }))
                        add(Triple(pushLabel, PushIcon, { if (noteDay != null) onPushTo(noteDay, note) else { onNote(if (missed) "again later" else "pushed", note); onPush() } }))
                        add(Triple(deleteLabel, Icons.Filled.Close, { onNote("deleted", note); onCancel() }))
                    } else add(Triple("To do", UndoIcon, { onNote("to do again", note); onReopen() }))
                    add(Triple("Edit", Icons.Filled.Edit, onEdit))
                    add(Triple("Talk", MicIcon, onTalk))
                    if (open) add(Triple("Extend", ExtendIcon, onExtend))
                    // A one-cell task can grow into a project, or join others that belong with it.
                    if (open && project == null && !task.errands) {
                        add(Triple("Make it a project", NewProjectIcon, onConvert))
                        add(Triple("Connect tasks", FolderIcon, onConnect))
                    }
                    // Documents and expenses, kept with the task or its project.
                    val nd = com.opslegal.tda.core.plan.Paperwork.docs(board, task).size
                    add(Triple(if (nd > 0) "Documents ($nd)" else "Documents", ClipIcon, onDocs))
                    add(Triple("＋ Expense", MoneyIcon, onExpense))
                    // Discuss: a conversation about this item, to find the best course of action.
                    if (open) add(Triple("✨ Prepare", Icons.Filled.Star, onPrepare))
                    add(Triple("Discuss", ChatIcon, { onDiscuss(note) }))
                    if (open && com.opslegal.tda.core.plan.Moves.cantMove(task, step, today) == null) add(Triple("Move", MoveIcon, onMove))
                }
                val spent = com.opslegal.tda.core.plan.Paperwork.expenses(board, task)
                if (spent.isNotEmpty()) TextButton(onClick = onExpenses) {
                    Text("💲 ${spent.size} expense${if (spent.size > 1) "s" else ""} · ${com.opslegal.tda.core.plan.Paperwork.money(spent.sumOf { it.amount })} ›")
                }
                actions.chunked(3).forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        line.forEach { (label, icon, action) ->
                            val (color, content) = when (label) {
                                "Done" -> DoneYellow to DoneInk
                                pushLabel, "Extend", "To do", "Make it a project" -> Slate to Color.White
                                "Connect tasks" -> Pewter to Color.White
                                "＋ Expense" -> Slate to Color.White
                                deleteLabel -> Pewter to Color.White
                                else -> Navy to Color.White
                            }
                            RoundAction(icon, label, color, action, contentColor = content, label = label)
                        }
                        repeat(3 - line.size) { Box(Modifier.width(64.dp)) }
                    }
                }
                if (board.gbn) {
                    // Under the actions: what this task serves, in one line, and its value for the user. Tap to correct.
                    GbnStrip(board.values, com.opslegal.tda.core.plan.Gbn.share(board, levels), levels, small = true, onTap = { n, l -> levels = levels.tapped(n, l) })
                    val own = task.serve.isNotEmpty() || (project?.serve?.isNotEmpty() == true)
                    Text(
                        "Value for you: ${valueWords(com.opslegal.tda.core.plan.Gbn.valueFor(board, levels))}" +
                            if (levels.isEmpty()) " · not rated yet: tap a bar" else "",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    if (levels != saved) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { levels = saved }) { Text("Cancel") }
                            Button(onClick = { onSaveLevels(levels, true) }) { Text("Save") }
                        }
                        Text("The assistant learns from your change for tasks like this one.", style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    } else if (levels.isNotEmpty()) {
                        Text(if (own) "Tap a bar to raise or lower it." else "Proposed by the assistant. Tap a bar to correct it.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
private fun ExtendDialog(
    title: String, sameDay: String, canSameDay: Boolean, splitInfo: String, onDismiss: () -> Unit,
    onLonger: () -> Unit, onExtend: (Projects.Extension, String) -> Unit,
) {
    var related by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(MoreEffortIcon, "Longer: one more cell the same day", Navy, enabled = canSameDay, onClick = onLonger, label = "Longer, same day")
                    RoundAction(RelatedIcon, "Split: continue another day", Slate,
                        onClick = { onExtend(Projects.Extension.MORE_EFFORT, "") }, label = "Split in two")
                }
                Text("⏱ Longer: $sameDay", style = MaterialTheme.typography.bodySmall)
                if (splitInfo.isNotBlank()) Text("✂ Split: $splitInfo", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { related = !related }) { Text("Something else is needed first…") }
                if (related) {
                    HelpField(text, { text = it; error = null }, "What is needed first",
                        "Something needed to finish this task. It takes this cell, and this task moves to the next day.")
                    Button(onClick = { if (text.isBlank()) error = "Say in a few words what is needed." else onExtend(Projects.Extension.RELATED_TASK, text.trim()) },
                        modifier = Modifier.fillMaxWidth()) { Text("Add it") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Why it moves: one tap (or a few words), so the weekly review can talk about it. Push works without a reason too. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PushDialog(
    title: String, missed: Boolean, onDismiss: () -> Unit,
    /** In a project: what the push does to it, given the later fixed steps the user lets move after it. */
    impact: ((Set<String>) -> Projects.PushImpact?)? = null,
    dayName: (LocalDate) -> String = { it.toString() },
    board: Board? = null,
    onPush: (String, Set<String>, LocalDate?) -> Unit,
) {
    var why by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val first = remember { impact?.invoke(emptySet()) }
    var keep by remember { mutableStateOf(emptySet<String>()) }
    val moves = first?.conflicts.orEmpty().map { it.stepId }.toSet() - keep
    val now = remember(moves) { if (first == null) null else impact?.invoke(moves) }
    var context by remember { mutableStateOf("") }
    val reasons = listOf("Something unplanned came up", "Bigger than it looks", "Waiting for someone", "Low energy today", "Time off and pleasure")
    SoftDialog(keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(if (missed) "Again later: why?" else "Push: why?", maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // The consequence, before the push: the project's new end, and fixed steps that would now come first.
                if (first != null && now != null) Column(
                    Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val e = now.end
                    Text("📁 Project now ends ${e.end?.let(dayName) ?: "later (no free cell yet)"}" + (e.deadline?.let { " · deadline ${dayName(it)}" } ?: "") + if (e.late) " ⚠" else "",
                        style = MaterialTheme.typography.bodySmall, color = if (e.late) kindColor(TaskKind.DEADLINE) else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (e.late) FontWeight.Bold else FontWeight.Normal)
                    first.conflicts.forEach { c ->
                        HorizontalDivider()
                        Text("${c.title} is ${dayName(c.date)}, before this one (${first.day?.let(dayName) ?: "later"}). " +
                            if (c.meeting) "It is a meeting: if you move it, tell the people in it." else "Move it after?", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagChip(c.stepId !in keep, { keep = keep - c.stepId }, label = { Text("Move it too") })
                            TagChip(c.stepId in keep, { keep = keep + c.stepId }, label = { Text("Keep it") })
                        }
                    }
                }
                // Where to: the next free cell, or a day chosen while seeing the table.
                WhenChips(day, { day = it?.takeIf { d -> d.isAfter(LocalDate.now()) || missed } }, label = "To", board = board)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    reasons.forEach { r -> TagChip(why == r, { why = if (why == r) "" else r }, label = { Text(r) }) }
                }
                CompactField(context, { context = it }, "A few words (optional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                Text("Kept for your weekly review: we'll talk about it then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onPush(listOf(why, context.trim()).filter { it.isNotBlank() }.joinToString(": "), moves, day) }) {
            Text(day?.let { "Move to ${dayName(it)}" } ?: if (missed) "Again later" else "Push") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
 * Creates or edits a one-cell task. Name first, then the task explanation: in Assistant mode the ✨ button rewrites
 * it in clear words and fills the rest (intention, what it serves, type, effort, project, day); after an edit of the
 * explanation, the same button applies the change. Everything below stays correctable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDialog(
    board: Board,
    task: Task?,
    day: String?,
    dayLabel: String?,
    projects: List<String>,
    onDismiss: () -> Unit,
    onSave: (BoardOps.NewTask, LocalDate?) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSpeak: ((LocalDate?) -> Unit)? = null,
    /** Assistant mode: the AI writes the explanation and fills the form from the name and the explanation. */
    draft: (suspend (String, String) -> MainViewModel.DraftTask)? = null,
    assistantFirst: Boolean = false,
    /** The user changed what the assistant proposed it serves: a lesson for similar tasks. */
    onLearn: (String, Map<String, Int>) -> Unit = { _, _ -> },
    /** Ride along: added to a planned cell instead of getting one (host step, name, explanation, place). */
    onRide: ((String, String, String, String) -> Unit)? = null,
    maxDetourKm: Int = 10,
    /** Editing: the cell's day now; the form shows it and lets the user (or the assistant) change it. */
    currentDay: LocalDate? = null,
    dayName: (LocalDate) -> String = { it.toString() },
    /** A new task already thought through (e.g. from an email): the form opens filled, ready to save. */
    prefill: Prefill? = null,
) {
    val today = LocalDate.now()
    val values = board.values.map { it.name }
    var withAssistant by remember { mutableStateOf(draft != null && assistantFirst) }
    var editDay by remember { mutableStateOf(currentDay?.toString().orEmpty()) }
    var proposal by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preDay = prefill?.day?.takeIf { !it.isBefore(LocalDate.now()) }
    var picked by remember { mutableStateOf(preDay) }
    // ✨ The day suggested: from the message (or the note on it), the assistant's draft, or the words typed.
    var suggested by remember { mutableStateOf(preDay) }
    var title by remember { mutableStateOf(task?.title ?: prefill?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.description ?: prefill?.notes.orEmpty()) }
    // Already thought through: ✨ is only offered again once the explanation is changed.
    val preNotes = remember { prefill?.notes }
    var intention by remember { mutableStateOf(task?.intention ?: prefill?.intention.orEmpty()) }
    var levels by remember { mutableStateOf(task?.let { com.opslegal.tda.core.plan.Gbn.levelsOf(board, it) } ?: prefill?.serve.orEmpty()) }
    // What the assistant proposed, to tell a correction from its own proposal.
    var proposedLevels by remember { mutableStateOf(levels) }
    var project by remember { mutableStateOf(prefill?.project.orEmpty()) }
    var kind by remember { mutableStateOf(task?.kind ?: prefill?.kind ?: TaskKind.TASK) }
    var effort by remember { mutableStateOf(task?.effort ?: prefill?.effort ?: Effort.NORMAL) }
    var effortTouched by remember { mutableStateOf(task?.effortByUser == true) }
    var serves by remember { mutableStateOf(task?.values.orEmpty()) }
    var where by remember { mutableStateOf(task?.where ?: prefill?.where.orEmpty()) }
    var area by remember { mutableStateOf(task?.area.orEmpty()) }
    var rideDraft by remember { mutableStateOf<MainViewModel.DraftTask?>(null) }
    var rideChoice by remember { mutableStateOf<Boolean?>(null) }
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
                HelpField(title, { title = it; error = null }, "Name", "What the cell shows. Short.", onDictate = { title = it.trim().replaceFirstChar { c -> c.uppercase() }.take(60); error = null })
                HelpField(notes, { notes = it; error = null }, "Task explanation",
                    if (withAssistant) "In your own words: what, for whom, why, any day or deadline. ✨ rewrites it clearly and fills the rest. To change the task, edit this text and tap ✨ again. Or tap 🎤 and say it."
                    else "What it is and why it matters. The assistant reads it when it plans.",
                    singleLine = false, minLines = 3,
                    onDictate = { said -> notes = listOf(notes.trim(), said.trim()).filter { it.isNotBlank() }.joinToString(" "); if (title.isBlank()) title = said.trim().split(Regex("\\s+")).take(5).joinToString(" ").replaceFirstChar { c -> c.uppercase() }; error = null })
                if (prefill != null && prefill.prepared && notes == preNotes) Text("✓ Prepared by the assistant from the message: check it and Add. Change the explanation to have it rethought.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (withAssistant && draft != null && (prefill == null || !prefill.prepared || notes != preNotes)) {
                    Button(enabled = !busy && (notes.isNotBlank() || title.isNotBlank()), onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val t = draft(title.trim(), notes.trim().ifBlank { title.trim() })
                                if (task != null) {
                                    proposal = buildList {
                                        if (t.day != null && t.day.toString() != editDay) add("day ${currentDay?.let(dayName) ?: "–"} → ${dayName(t.day)}")
                                        if (t.kind != kind) add("type ${t.kind.name.lowercase()}")
                                        if (t.effort != effort) add("effort ${t.effort.name.lowercase()}")
                                    }.let { list -> (if (list.isEmpty()) "" else "Also: " + list.joinToString("; ") + ". ") + t.reason }.ifBlank { null }
                                    if (t.day != null) editDay = t.day.toString()
                                }
                                title = t.title; notes = t.notes; kind = t.kind; effort = t.effort
                                if (t.intention.isNotBlank()) intention = t.intention
                                if (board.gbn) { levels = t.serve; proposedLevels = t.serve }
                                if (t.project.isNotEmpty()) project = t.project
                                if (task == null && day == null && t.day != null) { suggested = t.day; picked = t.day }
                                if (t.where.isNotBlank()) where = t.where
                                rideDraft = t.takeIf { task == null }; rideChoice = null
                            } catch (e: Exception) {
                                error = e.message ?: "The task could not be written. Try again, or use Manual mode."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(if (task == null) "✨ Write it" else "✨ Apply my changes") }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (where.isNotBlank() || task?.where?.isNotBlank() == true) {
                    HelpField(where, { where = it }, "Where", "The store, address or area. Errands and visits are joined only when they are at most $maxDetourKm km apart (Settings).")
                }
                rideDraft?.let { r ->
                    val host = r.ride
                    val km = r.rideKm?.let { if (it < 0.5) " · same place" else " · %.1f km from %s".format(it, host?.where?.ifBlank { "it" } ?: "it") }.orEmpty()
                    val hostDay = host?.date?.let { runCatching { dayName(LocalDate.parse(it)) }.getOrNull() }.orEmpty()
                    when {
                        r.needsPrep -> Text("It needs preparation first, so it gets its own cell.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        host != null && r.rideFar -> Text("Not joined with “${host.title}” ($hostDay)$km, more than your $maxDetourKm km. It gets its own trip.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        host != null && onRide != null -> Column(
                            Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("💡 Can be done during “${host.title}” ($hostDay), no extra cell$km.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (rideChoice == true) Button(onClick = { rideChoice = true }) { Text("Add to it ✓") } else OutlinedButton(onClick = { rideChoice = true }) { Text("Add to it") }
                                if (rideChoice == false) Button(onClick = { rideChoice = false }) { Text("Keep separate ✓") } else OutlinedButton(onClick = { rideChoice = false }) { Text("Keep separate") }
                            }
                        }
                        else -> Unit
                    }
                }
                proposal?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
                HelpField(intention, { intention = it }, "Intention", "Why it matters to you, in one sentence. Correct it if the assistant got it wrong.")
                if (board.gbn) {
                    HelpLabel("What it serves", "Tap a bar to raise or lower it, as many as apply. Ground keeps life running, Build creates value, Nourish gives you energy.") {
                        GbnStrip(board.values, com.opslegal.tda.core.plan.Gbn.share(board, levels), levels, onTap = { n, l -> levels = levels.tapped(n, l) })
                    }
                    Text("Value for you: ${valueWords(com.opslegal.tda.core.plan.Gbn.valueFor(board, levels))}", style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else if (values.isNotEmpty()) {
                    HelpLabel("Serves", "What this task is good for. Tasks serving what matters most to you get the earlier cells.") {
                        ValueChips(values, serves) { serves = it }
                    }
                }
                if (task == null && day == null) {
                    // When: Next free · ✨ the suggested day · Today · Tomorrow · Pick a date. A day in the words typed is suggested.
                    val said = remember(title, notes) { com.opslegal.tda.core.plan.Updates.dayInNote("$title $notes", today) }
                    WhenChips(picked, { picked = it }, suggested ?: said, board = board)
                }
                if (task != null) DateField("Day", editDay, { editDay = it })
                HelpLabel("Type", "Blue: a one-cell task. Green: a step of a project. Black: a meeting or call. Red: a delivery, filing or deadline due that day.") {
                    Tags(
                        listOf(TaskKind.TASK to "Task", TaskKind.MEETING to "Meeting", TaskKind.DEADLINE to "Deadline"), kind, { kind = it },
                        color = { k -> kindColor(k) },
                    )
                }
                HelpLabel("Effort", "How heavy it feels to you. At most 2 heavy tasks a day, each with an easy first step. You can leave it: a task you push twice becomes heavy by itself.") {
                    Tags(listOf(Effort.LIGHT to "Light", Effort.NORMAL to "Normal", Effort.HEAVY to "Heavy"), effort, { effort = it; effortTouched = true })
                }
                // My week: which area this task is planned in (only once the user has set areas). A project's steps follow the project.
                // Until the user picks one, it follows the words typed (ACT → Couche-Tard).
                if (board.settings.areas.isNotEmpty() && project.isBlank()) AreaChips(board, area.ifBlank {
                    com.opslegal.tda.core.plan.Areas.of(board, Task(id = "", title = title, description = notes, project = project)).id
                }) { area = it }
                // A few letters show the matching projects, on the phone; a new name is created only when the task is saved.
                ProjectPicker(board, project, { project = it }, "Add to a project (optional)")
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
                    title.isBlank() -> "A name is needed."
                    notes.isBlank() -> "Add a short explanation so the assistant understands this task."
                    intention.isBlank() && task == null -> "Say why it matters, in one sentence (the intention)" + if (withAssistant) ", or tap ✨." else "."
                    else -> null
                }
                val ridingOn = rideDraft?.ride?.takeIf { rideChoice == true && onRide != null && rideDraft?.rideFar != true }
                if (ridingOn != null && title.isNotBlank()) {
                    onRide!!(ridingOn.stepId, title.trim(), notes.trim(), where.trim())
                    return@TextButton
                }
                if (error == null) {
                    if (board.gbn && levels.isNotEmpty() && levels != proposedLevels) onLearn(title.trim(), levels)
                    onSave(
                        BoardOps.NewTask(
                            title = title.trim(), description = notes.trim(), project = projectName(board, project), kind = kind,
                            effort = effort, effortByUser = effortTouched, values = serves,
                            priority = task?.priority ?: Priority.NORMAL, deadline = task?.deadline,
                            intention = intention.trim(), serve = if (board.gbn) levels else task?.serve.orEmpty(), where = where.trim(),
                            area = if (board.settings.areas.isNotEmpty() && project.isBlank()) area else "",
                        ),
                        if (task != null) runCatching { LocalDate.parse(editDay) }.getOrNull()?.takeIf { it != currentDay } else picked,
                    )
                }
            }) { Text(if (task == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A task the assistant already worked out (from a message or an email): the form opens with all of it. */
internal data class Prefill(
    val title: String, val notes: String, val project: String = "",
    val kind: TaskKind? = null, val effort: Effort? = null, val intention: String = "",
    val serve: Map<String, Int> = emptyMap(), val where: String = "", val day: LocalDate? = null,
    /** The assistant already worked it out (an add it proposed): the form is ready to Add, nothing is thought again. */
    val prepared: Boolean = true,
)

/** Connect tasks (rare): tap the tasks that belong with this one, in the order they should happen; they become one project. */
@Composable
private fun ConnectDialog(board: Board, task: Task, onDismiss: () -> Unit, onConnect: (List<String>, String, String) -> Unit) {
    val others = remember { board.tasks.filter { it.id != task.id && !it.isProject && !it.errands && it.steps.any { s -> !s.closed } } }
    var order by remember { mutableStateOf(listOf(task.id)) }
    var name by remember { mutableStateOf(task.title) }
    var intention by remember { mutableStateOf(task.intention) }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text("Connect tasks") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap the tasks that belong with “${task.title}”, in the order they should happen. Together they become one project.", style = MaterialTheme.typography.bodySmall)
                if (others.isEmpty()) Text("No other open task to connect.", style = MaterialTheme.typography.bodySmall)
                others.forEach { t ->
                    val n = order.indexOf(t.id)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable { order = if (n > 0) order - t.id else order + t.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (n > 0) "${n + 1}" else "○", Modifier.width(26.dp), fontWeight = FontWeight.Bold, color = if (n > 0) projectBarColor() else MaterialTheme.colorScheme.outline)
                        Text(t.title + (t.steps.firstOrNull { !it.closed }?.date?.let { "  · $it" } ?: ""))
                    }
                }
                CompactField(name, { name = it }, "Project name", Modifier.fillMaxWidth())
                CompactField(intention, { intention = it }, "Intention: why these belong together", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
            }
        },
        confirmButton = { Button(enabled = order.size > 1 && name.isNotBlank(), onClick = { onConnect(order, name, intention) }) { Text(if (order.size > 1) "Connect ${order.size} tasks" else "Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
