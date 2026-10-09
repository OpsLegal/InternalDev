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
    data class Project(val name: String?) : TableDialog
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
                onSaveLevels = { levels, learn -> vm.saveLevels(task.id, levels, if (task.isProject) step.title else task.title, learn) },
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
                dayName = vm::dayName) { why, moves ->
                act(task, { BoardOps.pushStep(it, step.id, today, why, moves) },
                    (if (missed) "Again later. The red cell stays as your record." else "Pushed.") + if (why.isNotBlank()) " I'll bring it up in your weekly review." else "")
            }
        }
        is TableDialog.Extend -> {
            val (task, step) = BoardOps.findStep(board, d.stepId) ?: return close()
            // Extend = longer, the same day (the usual case); another day is a split in two.
            val longer = Projects.longer(board, step.id, today)
            val day = longer?.day ?: today
            val slot = longer?.let { Projects.freeSlotNear(it.board, it.day, step.slot) }
            val victim = if (longer != null && slot == null) Projects.movableOn(longer.board, longer.day, longer.taskId, today) else null
            val split = Projects.extend(board, step.id, Projects.Extension.MORE_EFFORT, "", today)
            val dayText = if (day == today) "today" else vm.dayName(day)
            val sameDay = when {
                slot != null -> "Takes the free cell ${if (step.slot?.let { slot == it + 1 || slot == it - 1 } == true) "next to it" else "${slot + 1}"} $dayText."
                victim != null -> "${dayText.replaceFirstChar { it.uppercase() }} is full: “${Planner.cellTitle(victim.first, victim.second)}” moves to a later day."
                else -> "${dayText.replaceFirstChar { it.uppercase() }} is full and nothing can move: split it instead."
            }
            ExtendDialog(Planner.cellTitle(task, step), sameDay, longer != null && (slot != null || victim != null),
                split?.let { "Continues on ${vm.dayName(it.day)} or the next free day." } ?: "", close,
                onLonger = {
                    val note = if (longer?.becameProject == true) "“${task.title}” takes several cells, so it is now a project (green)." else "Longer: one more cell $dayText."
                    vm.apply({ b ->
                        val e = Projects.longer(b, step.id, today) ?: return@apply b
                        Projects.placeNear(e.board, e.stepToPlace, e.day, step.slot)
                            ?: Projects.movableOn(e.board, e.day, e.taskId, today)?.let { v -> Projects.makeRoom(e.board, v.second.id, e.stepToPlace, e.day) }
                            ?: e.board
                    }, longer?.board?.tasks?.firstOrNull { it.id == longer.taskId }?.project, note)
                    close()
                },
            ) { how, related ->
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
        TableDialog.Work -> {
            val tasks = board.tasks.count { !it.isProject && !it.isDone }
            SoftDialog(
                onDismissRequest = close,
                title = { Text("Task or project") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("A task is one cell. Several cells in an order: a project.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(NewTaskIcon, "Create a task", Slate, onClick = { onDialog(TableDialog.NewTask(null)) }, label = "New task")
                            RoundAction(NewProjectIcon, "Create a project", Bordeaux, onClick = { onDialog(TableDialog.Project(null)) }, label = "New project")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(Icons.Filled.Edit, "Modify a task", Navy, enabled = tasks > 0, onClick = { onDialog(TableDialog.PickTask) }, label = "Modify a task")
                            RoundAction(FolderIcon, "Modify a project", Navy, enabled = board.projects.isNotEmpty(), onClick = { onDialog(TableDialog.PickProject) }, label = "Modify a project")
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
                d.fromUpdate?.let { vm.itemTaskAdded(it) }
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
    onSaveLevels: (Map<String, Int>, Boolean) -> Unit = { _, _ -> },
    onRider: (String, Boolean) -> Unit = { _, _ -> },
) {
    val step = task.steps.first { it.id == stepId }
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
    onPush: (String, Set<String>) -> Unit,
) {
    var why by remember { mutableStateOf("") }
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
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    reasons.forEach { r -> TagChip(why == r, { why = if (why == r) "" else r }, label = { Text(r) }) }
                }
                CompactField(context, { context = it }, "A few words (optional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                Text("Kept for your weekly review: we'll talk about it then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onPush(listOf(why, context.trim()).filter { it.isNotBlank() }.joinToString(": "), moves) }) { Text(if (missed) "Again later" else "Push") } },
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
    var otherDay by remember { mutableStateOf(preDay?.toString().orEmpty()) }
    var askOther by remember { mutableStateOf(preDay != null && preDay != LocalDate.now() && preDay != LocalDate.now().plusDays(1)) }
    var title by remember { mutableStateOf(task?.title ?: prefill?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.description ?: prefill?.notes.orEmpty()) }
    // Already thought through: ✨ is only offered again once the explanation is changed.
    val preNotes = remember { prefill?.notes }
    var intention by remember { mutableStateOf(task?.intention ?: prefill?.intention.orEmpty()) }
    var levels by remember { mutableStateOf(task?.let { com.opslegal.tda.core.plan.Gbn.levelsOf(board, it) } ?: prefill?.serve.orEmpty()) }
    // What the assistant proposed, to tell a correction from its own proposal.
    var proposedLevels by remember { mutableStateOf(levels) }
    var project by remember { mutableStateOf(prefill?.project.orEmpty()) }
    var projectFocused by remember { mutableStateOf(false) }
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
                HelpField(title, { title = it; error = null }, "Name", "What the cell shows. Short.")
                HelpField(notes, { notes = it; error = null }, "Task explanation",
                    if (withAssistant) "In your own words: what, for whom, why, any day or deadline. ✨ rewrites it clearly and fills the rest. To change the task, edit this text and tap ✨ again."
                    else "What it is and why it matters. The assistant reads it when it plans.",
                    singleLine = false, minLines = 3)
                if (prefill != null && notes == preNotes) Text("✓ Prepared by the assistant from the message: check it and Add. Change the explanation to have it rethought.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (withAssistant && draft != null && (prefill == null || notes != preNotes)) {
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
                                if (task == null && day == null && t.day != null) { askOther = true; otherDay = t.day.toString(); picked = t.day }
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
                    title.isBlank() -> "A name is needed."
                    notes.isBlank() -> "Add a short explanation so the assistant understands this task."
                    intention.isBlank() && task == null -> "Say why it matters, in one sentence (the intention)" + if (withAssistant) ", or tap ✨." else "."
                    askOther && picked == null -> "Choose a day."
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
                            title = title.trim(), description = notes.trim(), project = project.trim(), kind = kind,
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
)
