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
                // Light: planned this week, the hope before doing. Red only when even the plan falls short of the pace.
                val p = (week.plannedPct[b] ?: 0).coerceIn(v, 100)
                val low = week.pace > 0 && p < minOf(60.0, week.pace * 100 + 20)
                val over = week.lines.values.any { it.value.bucket.ifBlank { Gbn.GROUND } == b && it.over > 0 }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Gbn.names.getValue(b).uppercase(), fontSize = 10.sp, letterSpacing = 0.5.sp, color = Color.White.copy(alpha = 0.8f))
                        if (over) Text(" ●", fontSize = 9.sp, color = Amber)
                        Box(Modifier.weight(1f))
                        Text("$v%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (low) LowRed else Color.White)
                        if (p > v) Text("+${p - v}", fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 2.dp))
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).offset(y = 1.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f)))
                        Box(Modifier.fillMaxWidth(p / 100f).height(3.dp).offset(y = 1.dp).clip(RoundedCornerShape(2.dp)).background(bucketOnBar(b).copy(alpha = 0.38f)))
                        Box(Modifier.fillMaxWidth(v / 100f).height(3.dp).offset(y = 1.dp).clip(RoundedCornerShape(2.dp)).background(bucketOnBar(b)))
                        Box(Modifier.offset(x = maxWidth * week.pace.toFloat()).width(1.5.dp).height(5.dp).background(Color.White.copy(alpha = 0.8f)))
                    }
                }
            }
            Text(if (open) "▴" else "▾", color = Color.White, fontSize = 14.sp)
        }
        if (open) Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            BalancePanel(board, week, today, onReview = { vm.reviewOpen.value = true }, onHow = { vm.howCounted.value = true }, onPlanOne = { v ->
                val m = board.values.firstOrNull { it.name == v }?.meaning.orEmpty()
                vm.askAssistant("My week is short on $v ($m). Propose 2 small, realistic cells that serve it, on free cells this week, and say which day. Change nothing until I say.")
            })
        }
    }
}

/** One value: what it got of its aim, its costs, its routines, what is beyond the aim. */
/** One value in its pillar card: its name, "done / aim" (✓ when reached), one bar (solid done, light planned, the pace tick). Tap: plan a cell for it. */
@Composable
private fun ValueCell(name: String, l: Balance.Line, pace: Double, onPlan: () -> Unit) {
    val c = bucketOnBar(l.value.bucket)
    val got = if (name == Balance.METHODIC) l.pct * l.aim else l.counted
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onPlan).padding(vertical = 3.dp, horizontal = 1.dp)) {
        Row {
            Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (l.pct >= 1.0) "✓" else "${r1(got)}/${r1(l.aim)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 3.dp).height(10.dp)) {
            val w = maxWidth
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.12f)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(maxOf(l.pct, l.ifDone / l.aim).coerceIn(0.0, 1.0).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = 0.4f)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(l.pct.coerceIn(0.0, 1.0).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
            if (pace > 0) Box(Modifier.align(Alignment.CenterStart).padding(start = (w * pace.coerceIn(0.0, 1.0).toFloat() - 1.dp).coerceAtLeast(0.dp)).width(2.dp).height(10.dp).background(Amber))
        }
    }
}

/**
 * The detailed view, one screen: a plain line for the week, three pillar cards side by side (one bar and one "done / aim"
 * per value), what pulls the week down in plain words, then the next move with its button. Tap a value to plan a cell for it.
 */
@Composable
internal fun BalancePanel(board: Board, week: Balance.Week, today: LocalDate, onReview: () -> Unit, onHow: () -> Unit, onPlanOne: (String) -> Unit) {
    val catchUp = remember(board, today) { Balance.catchUp(board, today, week) }
    val soft = Color.White.copy(alpha = 0.8f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${week.doneCount} of ${week.capacity} cells done this week", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Legend(Color.White, "done"); Legend(Color.White.copy(alpha = 0.4f), "planned"); Legend(Amber, "pace", tick = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Gbn.buckets.forEach { b ->
                val list = week.lines.entries.filter { it.value.value.bucket.ifBlank { Gbn.GROUND } == b }
                val c = bucketOnBar(b)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(c))
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(Gbn.names.getValue(b).uppercase(), fontSize = 10.sp, color = c, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${week.pillar[b]}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        list.forEach { (k, l) -> ValueCell(k, l, week.pace) { onPlanOne(k) } }
                    }
                }
            }
        }
        // What pulls the week down, in plain words: loose ends (Organized) and cells done late.
        val loose = week.lines[Balance.ORGANIZED]?.cost ?: 0.0
        val late = week.lines.filter { (k, l) -> k != Balance.ORGANIZED && l.cost > 0 }.keys
        val down = listOfNotNull(
            loose.takeIf { it >= 0.5 }?.let { val n = Math.round(it).toInt().coerceAtLeast(1); "$n loose end${if (n > 1) "s" else ""}" },
            late.takeIf { it.isNotEmpty() }?.let { "late on ${it.joinToString(", ")}" },
        ).joinToString(" · ")
        if (down.isNotEmpty()) Text("⚠ Pulling you down: $down", color = Amber, fontSize = 12.sp)
        val opp = week.opportunity
        val one = opp?.let { Balance.oneCell(week, it) } ?: 0
        if (opp != null && one > 0) NextMove("Next best move", "1 cell of $opp → +$one% ${Gbn.names[week.lines.getValue(opp).value.bucket.ifBlank { Gbn.GROUND }]}", "＋ Plan it", solid = true) { onPlanOne(opp) }
        else NextMove("A good week", "Every value at 80% or more.", null, false) {}
        if (catchUp != null && catchUp.gain > 0)
            NextMove("Catch up", "${catchUp.count} cell${if (catchUp.count > 1) "s" else ""} still open → +${catchUp.gain} points", "See them", solid = false, onClick = onReview)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap a value to plan a cell for it.", color = soft, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("🌙 Review", color = soft, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onReview).padding(horizontal = 6.dp, vertical = 4.dp))
            Text("How it's counted", color = soft, fontSize = 11.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline, modifier = Modifier.clickable(onClick = onHow).padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, tick: Boolean = false) {
    Box(Modifier.padding(start = 6.dp, end = 3.dp).width(if (tick) 2.dp else 10.dp).height(if (tick) 10.dp else 6.dp).clip(RoundedCornerShape(2.dp)).background(color))
    Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
}

@Composable
private fun NextMove(title: String, line: String, action: String?, solid: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(line, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
        if (action != null) {
            if (solid) androidx.compose.material3.Button(onClick = onClick, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1F2A44))) { Text(action, fontWeight = FontWeight.SemiBold) }
            else OutlinedButton(onClick = onClick, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(action) }
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
