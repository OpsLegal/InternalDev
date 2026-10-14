package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Balance
import com.opslegal.tda.core.plan.Balance.r1
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Gbn
import com.opslegal.tda.core.plan.Planner
import java.time.LocalDate

private val Amber = Color(0xFFF2B33D)
private val LowRed = Color(0xFFF2A7A0)

/**
 * The week's barometer, in every page's header: three thin level lines with their %, a tick where you'd be today at
 * your pace, an amber dot when a value is beyond its aim. The arrow opens the details (each value) and closes them.
 */
@Composable
internal fun WeekBar(vm: MainViewModel) {
    val board by vm.board.collectAsStateWithLifecycle()
    if (!board.gbn || board.values.isEmpty()) return
    val today = LocalDate.now()
    val week = remember(board, today) { Balance.of(board, today) }
    val open by vm.barOpen.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.barOpen.value = !open }.semantics {
                contentDescription = "This week's balance: " + Gbn.buckets.joinToString { "${Gbn.names[it]} ${week.pillar[it]}%" } + ". " + (if (open) "Hide" else "Show") + " the details"
            },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Gbn.buckets.forEach { b ->
                val v = (week.pillar[b] ?: 0).coerceIn(0, 100)
                val over = week.lines.values.any { it.value.bucket.ifBlank { Gbn.GROUND } == b && it.over > 0 }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Gbn.names.getValue(b).uppercase(), fontSize = 10.sp, letterSpacing = 0.5.sp, color = Color.White.copy(alpha = 0.8f))
                        if (over) Text(" ●", fontSize = 9.sp, color = Amber)
                        Box(Modifier.weight(1f))
                        Text("$v%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (v < week.pace * 60) LowRed else Color.White)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).offset(y = 1.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f)))
                        Box(Modifier.fillMaxWidth(v / 100f).height(3.dp).offset(y = 1.dp).clip(RoundedCornerShape(2.dp)).background(bucketOnBar(b)))
                        Box(Modifier.offset(x = maxWidth * week.pace.toFloat()).width(1.5.dp).height(5.dp).background(Color.White.copy(alpha = 0.8f)))
                    }
                }
            }
            Text(if (open) "▴" else "▾", color = Color.White, fontSize = 14.sp)
        }
        if (open) Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            BalancePanel(board, week, today, onReview = { vm.reviewOpen.value = true }, onHow = { vm.howCounted.value = true })
        }
    }
}

/** One value: what it got of its aim, its costs, its routines, what is beyond the aim. */
@Composable
private fun ValueRow(name: String, l: Balance.Line, flow: Double?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row {
            Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (name == Balance.METHODIC) "${(l.pct * 100).toInt()}%" else "${r1(l.counted)} / ${r1(l.aim)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxWidth(l.pct.coerceIn(0.0, 1.0).toFloat()).height(3.dp).background(bucketOnBar(l.value.bucket)))
        }
        val notes = listOfNotNull(
            if (name == Balance.METHODIC) "built ${r1(minOf(l.done + l.routines, l.aim))} of ${r1(l.aim)} · flow ${flow?.let { "${(it * 100).toInt()}%" } ?: "—"}" else null,
            if (l.cost > 0) "−${r1(l.cost)} ${if (name == Balance.ORGANIZED) "open loops" else "delays"}" else null,
            if (l.routines > 0) "+${r1(l.routines)} routines" else null,
        )
        if (notes.isNotEmpty() || l.over > 0) Row {
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            if (l.over > 0) Text((if (notes.isNotEmpty()) " · " else "") + "+${r1(l.over)} beyond your aim", fontSize = 11.sp, color = Amber)
        }
    }
}

/** The detailed view: each value by category, one opportunity, what catching up gives back, the review and the method. */
@Composable
internal fun BalancePanel(board: Board, week: Balance.Week, today: LocalDate, onReview: () -> Unit, onHow: () -> Unit) {
    val catchUp = remember(board, today) { Balance.catchUp(board, today, week) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Gbn.buckets.forEach { b ->
            val list = week.lines.entries.filter { it.value.value.bucket.ifBlank { Gbn.GROUND } == b }
            if (list.isEmpty()) return@forEach
            Row(Modifier.padding(top = 4.dp)) {
                Text(Gbn.names.getValue(b).uppercase(), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                Text("${week.pillar[b]}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            list.forEach { (k, l) -> ValueRow(k, l, week.flow) }
        }
        val opp = week.opportunity
        Text(if (opp != null) "★ Biggest opportunity: $opp" + (if (week.lines[opp]?.pct == 0.0) ", nothing yet this week." else ".") else "★ Every value at 80% or more. A good week.",
            color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (catchUp != null && catchUp.gain > 0)
            Text("↺ ${catchUp.count} cell${if (catchUp.count > 1) "s" else ""} still open: finish ${if (catchUp.count > 1) "them" else "it"} and get +${catchUp.gain} points back.",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Week ${week.week}% · ${week.doneCount} done of a ${week.capacity}-cell week (${week.how})", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onReview, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("🌙 Daily review") }
            OutlinedButton(onClick = onHow, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("How it's counted") }
        }
    }
}

/** "How the week is counted": the method in plain words, then each value. */
@Composable
internal fun HowCountedDialog(vm: MainViewModel) {
    val show by vm.howCounted.collectAsStateWithLifecycle()
    if (!show) return
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val w = remember(board, today) { Balance.of(board, today) }
    AlertDialog(
        onDismissRequest = { vm.howCounted.value = false },
        title = { Text("How the week is counted") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your week has ${w.capacity} cells (${w.how}). Each value gets a share of them by its weight: its aim. Only done cells count: " +
                    "one cell, split ⅔ / ⅓ when it serves two values. What is beyond an aim is shown, never counted, and never hides a push.")
                Text("Organized loses ½ cell per red cell and ¼ per push after the first; done gives it all back. " +
                    "Reputation, Money, Invest lose ¼ per push from the first, ½ when red; done gives half back. Moving a cell earlier costs nothing.")
                Text("A routine ticked in the daily review adds ¼ cell (at most ¼ of the aim). Methodic = ½ what you built for it + ½ your flow" +
                    w.flowParts.joinToString(", ", " (", ")") { "${it.first} ${(it.second * 100).toInt()}%" } + ".")
                w.lines.forEach { (k, l) ->
                    Column {
                        Row { Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${r1(l.counted)} / ${r1(l.aim)}") }
                        (l.from + l.why).take(8).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.howCounted.value = false }) { Text("Close") } },
    )
}

/** The daily review, at the time the user chose: today's routines, tomorrow's events, today's open cells. Two minutes. */
@Composable
internal fun DailyReviewDialog(vm: MainViewModel) {
    val show by vm.reviewOpen.collectAsStateWithLifecycle()
    if (!show) return
    val board by vm.board.collectAsStateWithLifecycle()
    val events by vm.calendarEvents.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val ticks = board.routineDone[today.toString()].orEmpty()
    val routines = board.routines.filter { today.dayOfWeek.value in it.days }
    val tomorrow = events.filter { it.start.take(10) == today.plusDays(1).toString() }
    val open = board.tasks.flatMap { t -> t.steps.filter { it.date == today.toString() && !it.closed }.map { t to it } }
    var time by remember { mutableStateOf(board.reviewTime) }
    AlertDialog(
        onDismissRequest = { vm.reviewOpen.value = false },
        title = { Text("🌙 Daily review") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (routines.isNotEmpty()) {
                    Text("Your routines today", style = MaterialTheme.typography.labelMedium)
                    routines.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.tickRoutine(r.id, r.id !in ticks) }) {
                            Checkbox(checked = r.id in ticks, onCheckedChange = { vm.tickRoutine(r.id, it) })
                            Text(r.title)
                        }
                    }
                }
                Text("Tomorrow", style = MaterialTheme.typography.labelMedium)
                if (tomorrow.isEmpty()) Text("Nothing in your calendar.", style = MaterialTheme.typography.bodySmall)
                else tomorrow.forEach { e -> Text("📅 ${e.start.drop(11).take(5).ifBlank { "All day" }} ${e.title}", style = MaterialTheme.typography.bodyMedium) }
                Text("Still open today", style = MaterialTheme.typography.labelMedium)
                if (open.isEmpty()) Text("Nothing left. Well done.", style = MaterialTheme.typography.bodySmall)
                open.forEach { (t, s) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Planner.cellTitle(t, s), modifier = Modifier.weight(1f))
                        Button(onClick = { vm.setDone(s.id, true) }) { Text("✓ Done") }
                        TextButton(onClick = { vm.edit { b -> Planner.plan(BoardOps.pushStep(b, s.id, today), today).board } }) { Text("Again later") }
                    }
                }
                OutlinedTextField(time, { v -> time = v.filter { it.isDigit() || it == ':' }.take(5) }, label = { Text("Every day at (HH:mm)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { vm.finishReview(time) }) { Text("Done") }
        },
    )
}
