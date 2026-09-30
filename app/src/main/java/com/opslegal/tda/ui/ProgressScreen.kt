package com.opslegal.tda.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
@Composable
fun ProgressScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var open by remember { mutableStateOf<String?>(null) }
    val rows = remember(board, today) {
        board.projects.map { it to Projects.stats(board, it) }.filter { it.second.total > 0 }
            .sortedWith(
                compareByDescending<Pair<Project, Projects.Stats>> { it.second.end.late }
                    .thenBy { it.second.finished }
                    .thenBy { it.second.end.deadline ?: LocalDate.MAX },
            )
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Where each project stands: done so far, planned end, and its deadline.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Legend() }
            if (rows.isEmpty()) {
                item { Text("No project yet. Create one with the folder button on the table.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(rows, key = { it.first.name }) { (project, stats) ->
                ProjectRow(vm, project, stats, today) { open = project.name }
            }
            item {
                Text(
                    "Tap a project to modify it. The assistant sees the same numbers when you ask “how am I doing?”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Box(Modifier.height(96.dp)) }
        }
        PageAssistantButton(Modifier.align(Alignment.BottomEnd)) { vm.openAssistantFor(AssistantPage.PROGRESS) }
    }

    open?.let { name -> ProjectDialog(vm, board, name, onDismiss = { open = null }) }
}

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
        x.finished -> "Finished"
        end.late && end.end != null && end.deadline != null -> "At risk: ends ${vm.dayName(end.end!!)} · deadline ${vm.dayName(end.deadline!!)}"
        else -> (end.end?.let { "Ends " + vm.dayName(it) } ?: "Not planned yet") +
            (end.deadline?.let { " · deadline " + vm.dayName(it) } ?: " · no deadline")
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (end.late && !x.finished) late else MaterialTheme.colorScheme.onSurfaceVariant)
                    x.next?.let { s -> Text("Next: ${s.title} · ${vm.dayName(LocalDate.parse(s.date))}", style = MaterialTheme.typography.bodySmall) }
                }
                Text("${x.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    val planEnd = x.end.end ?: today
    val deadline = x.end.deadline
    val to = listOfNotNull(x.end.end, deadline, today.plusDays(7)).max()
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
internal fun PageAssistantButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    RoundAction(MicIcon, "Ask the assistant about this page", Navy, onClick = onClick, modifier = modifier.padding(12.dp), size = 56.dp)
}
