package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.material3.Button
import com.opslegal.tda.core.plan.Updates
import com.opslegal.tda.core.plan.BoardOps

import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.Icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.AssistantPage
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.Projects
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Where each project stands: done so far, planned end and deadline. At-risk projects first. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProgressScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var open by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    // The idea being written under Ideas: "" for a new one, or the name of the one being reshaped.
    var ideaOpen by remember { mutableStateOf<String?>(null) }
    var organizing by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    val week = remember(board) { vm.weekToReview() }
    var parking by remember { mutableStateOf(false) }
    var resuming by remember { mutableStateOf<String?>(null) }
    val ideas = remember(board) { board.projects.filter { Projects.isIdea(board, it) } }
    val rows = remember(board, today) {
        board.projects.map { it to Projects.stats(board, it) }.filter { it.second.total > 0 }
            .sortedWith(
                compareByDescending<Pair<Project, Projects.Stats>> { it.second.end.late }
                    .thenBy { it.second.finished }
                    .thenBy { it.second.end.deadline ?: LocalDate.MAX },
            )
    }

    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("projects") }
    val risk = rows.filter { it.second.end.late && !it.second.finished }
    val track = rows.filter { !it.second.end.late && !it.second.finished }
    val finished = rows.filter { it.second.finished }
    val monday = today.with(java.time.DayOfWeek.MONDAY)
    val doneWeek = board.tasks.sumOf { t -> t.steps.count { s -> s.done && s.date != null && LocalDate.parse(s.date).let { !it.isBefore(monday) && !it.isAfter(today) } } }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Box(Modifier.height(4.dp)) }
            // Where you stand, at a glance: three big tiles.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${track.size}", "on track", Modifier.weight(1f)) { tab = "projects" }
                    StatTile("${risk.size}", "at risk", Modifier.weight(1f), bad = risk.isNotEmpty()) { tab = "projects" }
                    StatTile("$doneWeek", "cells done this week", Modifier.weight(1f)) { reviewing = true }
                }
            }
            // This week so far: a score out of 100 per category, the full bar, and the review any day of the week.
            if (board.gbn && board.values.isNotEmpty()) item {
                val scores = remember(board, today) { com.opslegal.tda.core.plan.Routines.scores(board, today) }
                val levels = remember(board, today) { com.opslegal.tda.core.plan.Routines.week(board, today) }
                val low = com.opslegal.tda.core.plan.Gbn.buckets.filter { (scores[it] ?: 0) < com.opslegal.tda.core.plan.Routines.LOW }
                val top = com.opslegal.tda.core.plan.Gbn.buckets.filter { (scores[it] ?: 0) >= 100 }
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row { Text("This week so far", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("score /100", style = MaterialTheme.typography.labelSmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.opslegal.tda.core.plan.Gbn.buckets.forEach { b ->
                                val v = scores[b] ?: 0
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.fillMaxWidth().height(4.dp).background(bucketColor(b)))
                                    Text("$v", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
                                        color = if (v < com.opslegal.tda.core.plan.Routines.LOW) kindColor(TaskKind.DEADLINE) else MaterialTheme.colorScheme.onSurface)
                                    Text(com.opslegal.tda.core.plan.Gbn.names.getValue(b), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
                                }
                            }
                        }
                        GbnStrip(board.values, emptyMap(), levels, scores = scores, large = true)
                        Text(when {
                            low.isNotEmpty() -> "▼ ${low.joinToString(" and ") { com.opslegal.tda.core.plan.Gbn.names.getValue(it) }} under 60: the review suggests one small change."
                            top.isNotEmpty() -> "${top.joinToString(" and ") { com.opslegal.tda.core.plan.Gbn.names.getValue(it) }} at 100. A balanced week so far."
                            else -> "A balanced week so far."
                        }, style = MaterialTheme.typography.bodySmall)
                        var details by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Button(onClick = { reviewing = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                                Text("📊 Review my week", fontSize = 13.sp) }
                            OutlinedButton(onClick = { details = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                                Text("🔍 Score details", fontSize = 13.sp) }
                        }
                        if (details) ScoreDetails(board, scores, today) { details = false }
                    }
                }
            } else item {
                Card(onClick = { reviewing = true }, modifier = Modifier.fillMaxWidth().border(1.dp, Navy, RoundedCornerShape(12.dp))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📊", fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (week != null) "Your weekly review" else "How is my week going?", fontWeight = FontWeight.SemiBold)
                            Text("What's still open · your projects · your balance · 1 suggestion", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // One section at a time: three big tabs instead of one long list.
            stickyHeader {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(Triple("projects", "Projects", track.size + risk.size), Triple("mind", "On my mind", board.mind.size), Triple("ideas", "Ideas", ideas.size)).forEach { (k, l, n) ->
                        val on = tab == k
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (on) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { tab = k },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(l + if (n > 0) "  $n" else "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (tab == "mind") item { OnMyMindSection(vm, board.mind, onOrganize = { organizing = true }) }
            if (tab == "projects") {
                item {
                    HelpLabel("Riskiest first. Tap one to open it.", "Green: done; light green: planned; red: after the deadline; the black line is today. " +
                        "The assistant sees the same numbers when you ask “how am I doing?”.") { if (rows.isNotEmpty()) Legend() }
                }
                if (rows.isEmpty()) item { Text("📁 No project running yet.", Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                listOf("⚠ At risk" to risk, "▶ On track" to track, "✓ Finished" to finished).forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        item(key = "g-$title") {
                            Row(Modifier.padding(top = 8.dp)) {
                                Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                    color = if (list === risk) kindColor(TaskKind.DEADLINE) else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${list.size}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        items(list, key = { it.first.name }) { (project, stats) -> ProjectRow(vm, project, stats, today) { open = project.name } }
                    }
                }
                item { OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("＋ New project") } }
            }
            if (tab == "ideas") item {
                Column {
                    HelpLabel("Ideas and parked projects.", "Park a crazy idea here so it stops spinning in your head. Shape it a little now, and it is much more likely to happen. A parked project keeps its steps until you resume it.") {}
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { ideaOpen = "" }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("💡 New idea") }
                        OutlinedButton(onClick = { parking = true }, enabled = rows.any { !it.second.finished }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("⏸ Park a project") }
                    }
                    if (ideas.isEmpty() && ideaOpen != "") Text("💡 No idea parked yet.", Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (tab == "ideas" && ideaOpen == "") item(key = "idea-new") { IdeaPanel(vm, null, onClose = { ideaOpen = null }, onStart = {}) }
            if (tab == "ideas") items(ideas, key = { "idea-" + it.name }) { idea ->
                if (ideaOpen == idea.name) {
                    IdeaPanel(vm, idea, onClose = { ideaOpen = null }, onStart = { name -> ideaOpen = null; open = name })
                } else {
                    val parked = Projects.isParked(board, idea)
                    Card(onClick = { if (parked) resuming = idea.name else ideaOpen = idea.name }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text((if (parked) "⏸ " else "💡 ") + idea.name, fontWeight = FontWeight.SemiBold)
                            if (parked) Text("Parked · tap to resume it", style = MaterialTheme.typography.bodySmall)
                            idea.notes.lineSequence().firstOrNull { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            Text("Tap to shape it or start it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Box(Modifier.height(96.dp)) }
        }
        PageAssistantButton(vm, AssistantPage.PROGRESS)
    }

    open?.let { name -> ProjectDialog(vm, board, name, onDismiss = { open = null }) }
    if (organizing) OrganizeDialog(vm, onDone = { organizing = false })
    if (reviewing) (week ?: vm.weekSoFar()).let { (monday, f) -> WeekReviewDialog(vm, monday, f, onProject = { open = it }, onDone = { reviewing = false }) }
    if (parking) {
        SoftDialog(
            onDismissRequest = { parking = false },
            title = { Text("Park a project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Its steps leave the table and wait with your ideas. Its cells free up.", style = MaterialTheme.typography.bodySmall)
                    rows.filter { !it.second.finished }.forEach { (p, _) ->
                        OutlinedButton(onClick = { vm.parkProject(p.name); parking = false }, modifier = Modifier.fillMaxWidth()) { Text(p.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { parking = false }) { Text("Close") } },
        )
    }
    resuming?.let { name ->
        SoftDialog(
            onDismissRequest = { resuming = null },
            title = { Text("⏸ $name") },
            text = { Text("Resume puts its steps back in free cells of your table. Nothing already planned moves.") },
            confirmButton = { Button(onClick = { vm.resumeProject(name); resuming = null }) { Text("Resume") } },
            dismissButton = { TextButton(onClick = { resuming = null }) { Text("Close") } },
        )
    }
    if (creating) ProjectDialog(vm, board, null, onDismiss = { creating = false })
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, bad: Boolean = false, onClick: () -> Unit) {
    val red = kindColor(TaskKind.DEADLINE)
    Card(
        onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (bad) red else MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (bad) red else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A day, with its month when it is more than three weeks away ("Tue 22 Dec"). */
private fun dayFar(vm: MainViewModel, d: LocalDate, today: LocalDate) =
    vm.dayName(d) + if (d.isAfter(today.plusDays(21))) " " + d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) else ""

@Composable
private fun Legend() {
    val bar = projectBarColor()
    val late = kindColor(TaskKind.DEADLINE)
    val ink = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(bar to "done", bar.copy(alpha = 0.35f) to "planned", late to "after the deadline", ink to "today").forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(if (label == "today") 2.dp else 10.dp).height(10.dp)) { drawRect(c) }
                Text(" $label", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ProjectRow(vm: MainViewModel, project: Project, x: Projects.Stats, today: LocalDate, onClick: () -> Unit) {
    val end = x.end
    val late = kindColor(TaskKind.DEADLINE)
    val status = when {
        x.finished -> "Finished" + (x.finishedOn?.let { " " + dayFar(vm, it, today) } ?: "")
        end.late && end.end != null && end.deadline != null -> "Ends ${dayFar(vm, end.end!!, today)} · deadline ${dayFar(vm, end.deadline!!, today)}"
        else -> (end.end?.let { "Ends " + dayFar(vm, it, today) } ?: "Not planned yet") +
            (end.deadline?.let { " · deadline " + dayFar(vm, it, today) } ?: " · no deadline")
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (end.late && !x.finished) late else MaterialTheme.colorScheme.onSurfaceVariant)
                    x.next?.let { s -> Text("Next: ${s.title} · ${vm.dayName(LocalDate.parse(s.date))}", style = MaterialTheme.typography.bodySmall) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${x.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${x.done}/${x.total} steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Timeline(x, today)
        }
    }
}

/** From the first step (or today) to the latest of end, deadline and a week from now. */
@Composable
private fun Timeline(x: Projects.Stats, today: LocalDate) {
    val bar = projectBarColor()
    val late = kindColor(TaskKind.DEADLINE)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val from = listOfNotNull(x.start, today).min()
    // A finished project stops on the day its last step was done; only an unfinished one runs on to today.
    val planEnd = x.finishedOn ?: x.end.end ?: today
    val deadline = x.end.deadline
    val to = listOfNotNull(planEnd, deadline, today.plusDays(7)).max()
    val span = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1).toFloat()
    fun pos(d: LocalDate) = (ChronoUnit.DAYS.between(from, d) / span).coerceIn(0f, 1f)
    val okEnd = if (deadline != null && planEnd.isAfter(deadline)) deadline else planEnd
    val doneTo = pos(from) + (pos(okEnd) - pos(from)) * (if (x.total == 0) 0f else x.done.toFloat() / x.total)
    Canvas(Modifier.fillMaxWidth().height(12.dp).semantics { contentDescription = "${x.done} of ${x.total} steps done" }) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(h / 3)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(bar.copy(alpha = 0.35f), Offset(pos(from) * w, 0f), Size((pos(okEnd) - pos(from)) * w, h), r)
        if (deadline != null && planEnd.isAfter(deadline)) {
            drawRoundRect(late, Offset(pos(deadline) * w, 0f), Size((pos(planEnd) - pos(deadline)) * w, h), r)
        }
        drawRoundRect(bar, Offset.Zero, Size(doneTo * w, h), r)
        drawRect(ink, Offset(pos(today) * w - 1f, -2f), Size(2.dp.toPx(), h + 4f))
        if (deadline != null) drawRect(late, Offset(pos(deadline) * w - 1f, -2f), Size(2.dp.toPx(), h + 4f))
    }
}

/** The mic on Progress, Playbook and Settings: the assistant opens knowing which page the user is on. */
@Composable
internal fun PageAssistantButton(vm: MainViewModel, page: AssistantPage) {
    FloatingButtons(vm) {
        RoundAction(MicIcon, "Ask the assistant about this page", Navy, onClick = { vm.openAssistantFor(page) }, size = 56.dp)
    }
}

/**
 * The space under Ideas to write an idea: a name, the idea as it comes, and Clean up, which sorts it into a
 * main objective, sub-objectives and key details. The user corrects or adds, and can clean up again.
 */
@Composable
private fun IdeaPanel(vm: MainViewModel, idea: Project?, onClose: () -> Unit, onStart: (String) -> Unit) {
    var name by remember { mutableStateOf(idea?.name.orEmpty()) }
    var text by remember { mutableStateOf(idea?.notes.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpField(name, { name = it; error = null }, "Name", "Two or three words you'll recognise, e.g. Legal podcast.")
            HelpField(
                text, { text = it }, "The idea",
                "Write it as it comes: why, for whom, what it looks like when it works. Tap Clean up: the assistant sorts it into a main " +
                    "objective, sub-objectives and key details. Correct or add, then Clean up again.",
                singleLine = false, minLines = 6,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && text.isNotBlank(), onClick = {
                    busy = true; error = null; status = "Putting it in order..."
                    scope.launch {
                        try {
                            text = vm.cleanIdea(name, text)
                            status = "Correct or add anything, then Clean up again."
                        } catch (e: Exception) {
                            status = null
                            error = e.message ?: "It could not be cleaned up. Try again."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("✨ Clean up") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (idea != null) {
                    TextButton(onClick = { vm.deleteProject(idea.name); onClose() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Box(Modifier.weight(1f))
                if (idea != null) {
                    TextButton(enabled = !busy, onClick = {
                        error = vm.saveIdea(idea.name, name, text)
                        if (error == null) onStart(name.trim())
                    }) { Text("Start it") }
                }
                TextButton(onClick = onClose) { Text("Cancel") }
                TextButton(enabled = !busy, onClick = {
                    error = vm.saveIdea(idea?.name, name, text)
                    if (error == null) onClose()
                }) { Text(if (idea == null) "Park it" else "Save") }
            }
        }
    }
}

/** On my mind: write things down as they come; the assistant turns them into the table, so nothing stays a list. */
@Composable
private fun OnMyMindSection(vm: MainViewModel, items: List<com.opslegal.tda.core.model.MindItem>, onOrganize: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("On my mind", style = MaterialTheme.typography.titleMedium)
        Text(
            "Write things down as they come, one per line. The assistant turns them into tasks and project steps in your free cells, " +
                "by what matters to you, around your calendar. Nothing already planned moves.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(text, { text = it }, "e.g. Call the bank about the loan", Modifier.weight(1f))
            TextButton(enabled = text.isNotBlank(), onClick = { vm.addToMind(text); text = "" }) { Text("Add") }
        }
        items.forEach { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.removeFromMind(m.id) }) { Text("×") }
                }
            }
        }
        if (items.isNotEmpty()) Button(onClick = onOrganize) { Text("Organize (${items.size})") }
    }
}

/** The assistant's proposal, with the day each thing lands on, before anything moves. */
@Composable
private fun OrganizeDialog(vm: MainViewModel, onDone: () -> Unit) {
    var sorted by remember { mutableStateOf<List<com.opslegal.tda.core.agent.OnMyMind.Sorted>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try { sorted = vm.sortMind() } catch (e: Exception) { error = e.message ?: "It could not be organized. Try again." }
    }
    val list = sorted
    val days = remember(list) { list?.let { vm.previewMind(it) }.orEmpty() }
    val kinds = mapOf(
        com.opslegal.tda.core.agent.OnMyMind.Kind.TASK to "Task", com.opslegal.tda.core.agent.OnMyMind.Kind.STEP to "Step",
        com.opslegal.tda.core.agent.OnMyMind.Kind.QUICK to "Quick things", com.opslegal.tda.core.agent.OnMyMind.Kind.IDEA to "Idea",
        com.opslegal.tda.core.agent.OnMyMind.Kind.BUY to "To buy", com.opslegal.tda.core.agent.OnMyMind.Kind.DROP to "Drop",
    )
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDone,
        title = { Text(if (list == null) "Organizing…" else "Organized") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> { Text("Reading it with your projects, values and calendar.", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> {
                        val last = days.values.filterNotNull().maxOrNull()
                        Text(
                            "${list.size} things → " + kinds.keys.mapNotNull { k -> list.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${kinds.getValue(k).lowercase()}" } }.joinToString(" · ") +
                                (last?.let { ". The last one lands ${vm.dayName(it)}." } ?: "."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Only free cells are used, around your calendar. Work stays Monday to Friday.", style = MaterialTheme.typography.bodySmall)
                        list.forEach { s ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        kinds.getValue(s.kind) + (if (s.kind == com.opslegal.tda.core.agent.OnMyMind.Kind.STEP && s.project.isNotBlank()) " in ${s.project}" else "") +
                                            (days[s.text]?.let { " · " + vm.dayName(it) } ?: "") + (s.due?.let { " · due $it" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (list != null) Button(onClick = { vm.placeMind(list); onDone() }) { Text("Place them") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * The weekly review, as rows: a short title, a few words under it, and small actions next to it. Still open
 * (last month's messages and emails, red cells), projects, then one suggestion. Nothing long to read.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WeekReviewDialog(
    vm: MainViewModel, monday: LocalDate, f: com.opslegal.tda.core.agent.WeekReview.Facts,
    onProject: (String) -> Unit, onDone: () -> Unit,
) {
    var habit by remember { mutableStateOf<com.opslegal.tda.core.plan.Habits.Pattern?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.trackHabits() }
    habit?.let { p -> HabitDialog(vm, p, onDone = { habit = null }, onTalk = { onDone(); vm.askAssistant(it) }); return }
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var advice by remember { mutableStateOf<com.opslegal.tda.core.agent.WeekReview.Advice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var swept by remember { mutableStateOf(false) }
    var sweepError by remember { mutableStateOf<String?>(null) }
    var sweeping by remember { mutableStateOf(0) }
    var replying by remember { mutableStateOf<com.opslegal.tda.core.model.Update?>(null) }
    androidx.compose.runtime.LaunchedEffect(sweeping) {
        sweepError = null
        swept = false
        try { vm.sweepMonth(monday, force = sweeping > 0); swept = true } catch (e: Exception) { sweepError = e.message ?: "The last month could not be checked. Try again." }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try { advice = vm.weekAdvice(f) } catch (e: Exception) { error = e.message ?: "The review could not be written. Try again." }
    }
    val late = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)

    /** One row of the review: title, a few words, and its actions as small buttons. */
    @Composable
    fun ReviewRow(icon: String, title: String, sub: String, color: androidx.compose.ui.graphics.Color? = null, actions: List<Pair<String, () -> Unit>>) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = color ?: MaterialTheme.colorScheme.onSurface)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 24.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { (label, act) ->
                    OutlinedButton(
                        onClick = act, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider()
    }

    @Composable
    fun Header(text: String, right: String = "") {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (right.isNotBlank()) Text(right, style = MaterialTheme.typography.labelMedium)
        }
    }

    SoftDialog(
        onDismissRequest = onDone,
        title = { Text("Your week · ${vm.dayName(f.monday)} – ${vm.dayName(f.until)}") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                // 1. Still open: what waits on the user from the last month, each with its actions.
                val open = board.updates.filter { u ->
                    (u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && u.actions.isNotEmpty()) || (u.needsReply && !u.replied && u.meeting.isBlank())
                }.sortedByDescending { it.urgent }
                val monthAgo = today.minusDays(30).toString()
                val red = board.tasks.flatMap { t -> t.steps.filter { BoardOps.isMissed(it, today) && it.date!! >= monthAgo }.map { t to it } }
                Header("1 · Still open", if (swept) "${open.size + red.size}" else "")
                when {
                    sweepError != null -> Text(sweepError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    !swept -> { Text("Checking last month's chats and unread emails…", style = MaterialTheme.typography.bodySmall); androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    open.isEmpty() && red.isEmpty() -> Text("Nothing waits on you. ✓", style = MaterialTheme.typography.bodySmall)
                }
                open.forEach { u ->
                    val days = u.at.takeIf { it.isNotBlank() }?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDateTime.parse(it.take(19)).toLocalDate(), today) }.getOrNull() }
                    val icon = if (u.source in setOf("outlook", "gmail")) "✉" else "💬"
                    ReviewRow(
                        icon, u.title.ifBlank { u.summary.take(40) },
                        listOfNotNull(Updates.sender(u.from), days?.takeIf { it > 0 }?.let { "$it d" }, if (u.urgent) "urgent" else null).joinToString(" · "),
                        color = if (u.urgent) late else null,
                        actions = buildList {
                            if (u.needsReply && !u.replied) add("Reply" to { replying = u })
                            if (u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && u.actions.isNotEmpty()) add("Add task" to { vm.applyUpdate(u) })
                            add("Ask AI" to { onDone(); vm.investigateUpdate(u) })
                            add("✓ Done" to { vm.putAway(u, true, "replies") })
                        },
                    )
                }
                red.forEach { (t, st) ->
                    ReviewRow(
                        "🟥", com.opslegal.tda.core.plan.Planner.cellTitle(t, st), "not done · ${vm.dayName(LocalDate.parse(st.date))}",
                        actions = listOf(
                            "✓ Done" to { vm.edit { BoardOps.setStepDone(it, st.id, true) } },
                            "Again later" to { vm.edit { b -> com.opslegal.tda.core.plan.Planner.plan(BoardOps.pushStep(b, st.id, today), today).board } },
                        ),
                    )
                }
                if (swept) TextButton(onClick = { sweeping++ }) { Text("Check again") }

                // 2. Projects: where each one went this week.
                if (f.projectMoves.isNotEmpty()) {
                    Header("2 · Projects", "% · this week")
                    f.projectMoves.take(6).forEach { m ->
                        val risk = m.status.startsWith("at risk")
                        ReviewRow(
                            if (risk) "⚠" else if (m.stalled) "⏸" else "▶", "${m.name} · ${m.percent}% · +${m.doneThisWeek}",
                            if (m.stalled) "no step this week · ${m.status}" else m.status,
                            color = if (risk || m.stalled) late else null,
                            actions = listOf(
                                "Open" to { onDone(); onProject(m.name) },
                                "Ask AI" to { onDone(); vm.investigateProject(m.name, m.status, m.stalled) },
                            ),
                        )
                    }
                }

                // Habits to work on (Carnegie): what went well first, the pattern as a question, the cause in their words, one easy fix, tracked.
                val hb = vm.board.value
                val patterns = remember(hb) { com.opslegal.tda.core.plan.Habits.patterns(hb, java.time.LocalDate.now()) }
                val fixes = hb.habitFixes.filter { it.outcome != "dropped" }.takeLast(4)
                if (patterns.isNotEmpty() || fixes.isNotEmpty()) {
                    Header("Habits to work on", "")
                    Text("You finished ${f.done} cell${if (f.done == 1) "" else "s"} this week: that's real work. One or two things keep coming back; let's look at them together.",
                        style = MaterialTheme.typography.bodySmall)
                    fixes.forEach { h ->
                        when (h.outcome) {
                            "done" -> ReviewRow("✓", h.title, "It worked: ${h.fix}. Well done, that's a habit changing.", actions = emptyList())
                            "again" -> ReviewRow("↺", h.title, "It slipped again despite: ${h.fix}. No problem: let's try another way.", color = late,
                                actions = listOf("Try another way" to { habit = com.opslegal.tda.core.plan.Habits.Pattern(h.taskId, h.title, "pushed", 0, true) }))
                            else -> ReviewRow("⏳", h.title, "In progress: ${h.fix}.", actions = emptyList())
                        }
                    }
                    patterns.forEach { p ->
                        ReviewRow(if (p.kind == "stalled") "⏸" else "↷", p.title, p.question, color = if (p.important) late else null,
                            actions = listOf("Let's look" to { habit = p }))
                    }
                }
                // The balance, out of 100: underperforming categories get one small move; full ones are told to keep the rhythm.
                val sc = com.opslegal.tda.core.plan.Routines.scores(vm.board.value, java.time.LocalDate.now())
                if (vm.board.value.gbn) com.opslegal.tda.core.plan.Gbn.buckets.forEach { b ->
                    val v = sc[b] ?: 0
                    val name = com.opslegal.tda.core.plan.Gbn.names.getValue(b)
                    if (v < com.opslegal.tda.core.plan.Routines.LOW) ReviewRow("▼", "$name at $v/100 so far", "Under 60: one small cell or routine would bring it back.", color = late,
                        actions = listOf("Ask AI" to { onDone(); vm.askAssistant("My week is short on $name ($v/100). Look at my table and routines and propose one small, realistic change. Change nothing until I say.") }))
                    else if (v >= 100) ReviewRow("✓", "$name at 100/100", "Well served this week: keep the rhythm, no need to add more.", actions = emptyList())
                }
                // Why things moved: the reasons given at Push, to talk about now.
                if (f.reasons.isNotEmpty()) {
                    Header("Why things moved", "${f.reasons.size}")
                    f.reasons.take(5).forEach { r ->
                        ReviewRow("↷", r.substringBefore(": "), r.substringAfter(": ", ""), actions = listOf(
                            "Talk about it" to { onDone(); vm.askAssistant("This week I pushed “${r.substringBefore(": ")}” because: ${r.substringAfter(": ", "")}. Ask me one question about it, then suggest one small change. Change nothing until I say.") },
                        ))
                    }
                }
                // Routines: never checked, so the review asks; crowded moments get advice here, not on the routine page.
                val rb = vm.board.value
                val crowded = com.opslegal.tda.core.plan.Routines.crowded(rb)
                if (rb.routines.isNotEmpty() || rb.routineWishes.isNotEmpty()) Header("Routines", "${rb.routines.size} active")
                crowded.forEach { c ->
                    ReviewRow(c.moment.icon, "${c.words.replaceFirstChar { it.uppercase() }}: ${c.count} routines", "Did one slip? Spreading them over two moments often helps.",
                        actions = listOf(
                            "It went fine" to { vm.routineWentFine(c.key) },
                            "Spread them" to { onDone(); vm.askAssistant("On ${c.words} I have ${c.count} routines: ${com.opslegal.tda.core.plan.Routines.at(rb, c.day, c.moment).joinToString { it.title }}. Ask me which one slips, then propose another moment for it (no clock times). Change nothing until I say.") },
                        ))
                }
                rb.routineWishes.firstOrNull()?.let { w ->
                    val (d, m) = com.opslegal.tda.core.plan.Routines.freeSlot(rb)
                    ReviewRow("✦", "Wish: ${w.title}", "${com.opslegal.tda.core.plan.Routines.dayNames[d - 1]} ${m.label.lowercase()} is free: try it there?",
                        actions = listOf(
                            "Try it there" to { vm.saveRoutine(com.opslegal.tda.core.model.Routine(BoardOps.newId(), w.title, listOf(d), m, w.serve), fromWish = w.id) },
                            "Not yet" to { vm.edit { b -> b.copy(routineWishes = b.routineWishes.drop(1) + w) } },
                        ))
                }
                ReviewRow("💬", "Talk it through", "A short conversation: what got pushed and why, the empty cells, your routines, what helped. You answer, I learn how you work.",
                    actions = listOf("Start (3 questions)" to {
                        onDone()
                        vm.askAssistant(buildString {
                            append("Weekly talk. Be a warm coach, not a judge: ask me ONE question at a time and wait for my answer, 3 questions in all, then sum up in 2 lines what you learned and one small change for next week (save what you learn about me).\n")
                            append("My routines (never checked, assumed done; ask how they went): ${rb.routines.joinToString("; ") { "${it.title} ${it.days.size}x/week ${it.moment.label.lowercase()}" }.ifBlank { "none set" }}. ")
                            append("Wish list: ${rb.routineWishes.joinToString { it.title }.ifBlank { "empty" }}. ")
                            com.opslegal.tda.core.plan.Habits.facts(rb, java.time.LocalDate.now()).takeIf { it.isNotBlank() }?.let {
                                append("\nHabits (start with what went well; ask about each pattern as a question, kindly; praise any fix that worked; for one that slipped, try another way):\n$it")
                            }
                            if (crowded.isNotEmpty()) append("Crowded moments (3+ routines, ask if one slipped): ${crowded.joinToString { it.words }}. ")
                            append("\nFacts of my week: done ${f.done}/${f.planned}, moved ${f.moved}${if (f.movedTitles.isNotEmpty()) " (" + f.movedTitles.take(5).joinToString("; ") + ")" else ""}; red cells of the last 30 days: ${f.stillRed}; ")
                            append("why I pushed things: ${f.reasons.joinToString("; ").ifBlank { "no reason given" }}; my \"not now\" reasons: ${rb.notNowWhy.takeLast(5).joinToString("; ") { "${it.title}: ${it.what}" }.ifBlank { "none" }}.\n")
                            append("Start with the most useful question (e.g. why a task keeps moving, what filled the empty cells, what made the good days good).")
                        })
                    }))

                // 3. Learn from the week: the numbers in one row, then one suggestion.
                Header("3 · To improve", "${f.pct}%" + (f.previousPct?.let { " (was $it%)" } ?: ""))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    listOf("Done" to "${f.done}/${f.planned}", "Morning" to "${f.morningDone}/${f.morningAll}", "Afternoon" to "${f.afternoonDone}/${f.afternoonAll}", "Moved" to "${f.moved}")
                        .forEach { (label, value) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value, fontWeight = FontWeight.Bold)
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
                androidx.compose.material3.HorizontalDivider()
                val a = advice
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    a == null -> { Text("Writing your suggestion…", style = MaterialTheme.typography.bodySmall); androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> {
                        if (a.pattern.isNotBlank()) ReviewRow("🔎", "Pattern", a.pattern, actions = emptyList())
                        Text("💡 ${a.suggestion}", modifier = Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.tryAdvice(monday, f, a); onDone() }) { Text("Try it") }
                            OutlinedButton(onClick = { vm.declineAdvice(monday, f, a); onDone() }) { Text("Not for me") }
                        }
                        if (a.lastWeek.isNotBlank()) Text("✓ " + a.lastWeek, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
    // On top of the review: the answer is written here, and the row leaves the list once it is done.
    replying?.let { u -> ReplyDialog(u, vm, onDone = { replying = null }) }
}


/**
 * One habit, three short steps: is it still important (the flag, as a question), what gets in the way (their words, or
 * one tap), then the practice that usually works and its fix, applied in one tap and checked at the next review.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HabitDialog(vm: MainViewModel, p: com.opslegal.tda.core.plan.Habits.Pattern, onDone: () -> Unit, onTalk: (String) -> Unit) {
    var step by remember { mutableStateOf(0) }
    var cause by remember { mutableStateOf<com.opslegal.tda.core.plan.Habits.Cause?>(null) }
    var words by remember { mutableStateOf("") }
    SoftDialog(
        keepOpen = true, onDismissRequest = onDone,
        title = { Text(p.title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    0 -> {
                        Text(p.question)
                        Text("It happens to everyone, and it usually has a simple reason.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Yes, I want it done") }
                        OutlinedButton(onClick = { vm.applyHabit(p.taskId, "notimportant", ""); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Not anymore: let it go") }
                        Text("Letting go is a decision, not a failure: it frees a cell for what matters.", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("What gets in the way most?")
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            com.opslegal.tda.core.plan.Habits.causes.filter { it.key != "notimportant" }.forEach { c ->
                                TagChip(cause == c, { cause = c; step = 2 }, label = { Text(c.label) })
                            }
                        }
                        CompactField(words, { words = it }, "Or in your own words", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                        if (words.isNotBlank()) Button(onClick = {
                            onTalk("About “${p.title}”: ${p.question.substringAfter("” ").substringBefore(" Is")} In my words, what gets in the way: $words. " +
                                "Ask me one short question if needed, then propose the one fix that usually works for that, and apply it only when I say yes.")
                        }) { Text("Find the right fix with me") }
                    }
                    else -> {
                        val c = cause!!
                        Text("“${c.label}”", fontWeight = FontWeight.SemiBold)
                        Text(c.practice)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                            Text("→ ${c.fix}.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("I'll check with you at the next review how it went.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.applyHabit(p.taskId, c.key, words); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Do it") }
                        TextButton(onClick = { step = 1 }) { Text("Another reason") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Later") } },
    )
}

/** How the week's score is made: each value needs weight × 2 points; the cells and routines that brought them. */
@Composable
private fun ScoreDetails(board: com.opslegal.tda.core.model.Board, scores: Map<String, Int>, today: java.time.LocalDate, onDismiss: () -> Unit) {
    val lines = remember(board, today) { com.opslegal.tda.core.plan.Routines.breakdown(board, today) }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text("How the score is made") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Each thing you care about needs weight × 2 points a week. A cell gives it 1 to 3 points (what it serves); your routines count as done. " +
                    "Score = points received ÷ points needed. Under 60 is ▼.", style = MaterialTheme.typography.bodySmall)
                com.opslegal.tda.core.plan.Gbn.buckets.forEach { b ->
                    val list = lines.filter { it.bucket == b }
                    val need = list.sumOf { it.need }; val got = list.sumOf { minOf(it.have, it.need) }; val v = scores[b] ?: 0
                    Box(Modifier.fillMaxWidth().height(4.dp).background(bucketColor(b)))
                    Row {
                        Text(com.opslegal.tda.core.plan.Gbn.names.getValue(b), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("$v/100", fontWeight = FontWeight.Bold, color = if (v < com.opslegal.tda.core.plan.Routines.LOW) kindColor(TaskKind.DEADLINE) else MaterialTheme.colorScheme.onSurface)
                    }
                    Text("$got of $need points needed this week = $v%", style = MaterialTheme.typography.bodySmall)
                    list.forEach { l ->
                        Row {
                            Text("${l.name} · weight ${l.weight} → needs ${l.need}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${minOf(l.have, l.need)}/${l.need}", fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((l.have.toFloat() / l.need).coerceIn(0f, 1f)).height(6.dp).background(bucketColor(b)))
                        }
                        Text((if (l.from.isEmpty()) "Nothing this week yet." else l.from.take(6).joinToString(" · ") + if (l.from.size > 6) " …" else "") +
                            if (l.have > l.need) " · ${l.have - l.need} extra not counted" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
