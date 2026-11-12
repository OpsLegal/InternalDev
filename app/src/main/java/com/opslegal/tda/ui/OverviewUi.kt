package com.opslegal.tda.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.Projects
import com.opslegal.tda.core.plan.Things
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val DAYS = 56

/**
 * 🗺 Overview: the next 8 weeks on one screen. One lane per project (its area colour, its steps as dots, 🏁 its deadline,
 * grey stripes while paused) and one per thing (🔧 upkeep, ⚡ quick things, 📋 tasks). What needs you sits on top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewPage(board: Board, today: LocalDate, rows: List<Pair<Project, Projects.Stats>>, risk: List<String>, onProject: (String) -> Unit, onThing: (String) -> Unit) {
    val start = today.with(DayOfWeek.MONDAY)
    val end = start.plusDays(DAYS.toLong())
    val red = Color(0xFFD64545)
    val things = remember(board, today) {
        board.assets.map { a -> a to Things.items(board, a, start, end).filter { it.kind != "expense" } }
            .filter { it.second.any { i -> i.date >= start.toString() && i.date < end.toString() } }
    }
    val bad = remember(board, today) { board.assets.filter { Things.status(board, it, today) == "bad" } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (risk.isNotEmpty() || bad.isNotEmpty()) {
            Text("Needs you", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                risk.forEach { n -> TagChip(false, { onProject(n) }, label = { Text("⚠ $n", fontSize = 12.sp, color = red) }) }
                bad.forEach { a -> TagChip(false, { onThing(a.id) }, label = { Text("${Things.icon(a)} ${a.name} · late", fontSize = 12.sp, color = red) }) }
            }
        }
        // Week headers.
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(96.dp))
            Row(Modifier.weight(1f)) {
                (0 until 8).forEach { w ->
                    val d = start.plusWeeks(w.toLong())
                    Text("${d.dayOfMonth}/${d.monthValue}", fontSize = 9.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val live = rows.filter { !it.second.finished }
        if (live.isEmpty() && things.isEmpty()) Text("Nothing planned in the next 8 weeks.", style = MaterialTheme.typography.bodySmall)
        if (live.isNotEmpty()) Text("📁 Projects", style = MaterialTheme.typography.labelMedium)
        live.forEach { (p, x) ->
            val color = areaColor(board, com.opslegal.tda.core.plan.Areas.ofProject(board, p.name).id)
            val steps = board.tasks.filter { it.project.equals(p.name, ignoreCase = true) }.flatMap { it.steps }.filter { it.date != null }
            Lane(p.name + if (p.pause != null) " ⏸" else "", { onProject(p.name) }) { w, h, measurer ->
                fun x(d: LocalDate) = (ChronoUnit.DAYS.between(start, d).toFloat() / DAYS).coerceIn(0f, 1f) * w
                val from = x.start ?: today
                val to = x.end.end ?: x.end.deadline ?: today
                if (!to.isBefore(start) && from.isBefore(end)) {
                    if (p.pause != null) {
                        val l = x(from); val r = x(to).coerceAtLeast(l + 6f)
                        drawRoundRect(Color(0xFFBDBDBD), Offset(l, h * 0.3f), Size(r - l, h * 0.4f), CornerRadius(6f))
                        var s = l; while (s < r) { drawLine(Color.White, Offset(s, h * 0.7f), Offset((s + h * 0.4f).coerceAtMost(r), h * 0.3f), 2f); s += 9f }
                    } else drawRoundRect(color.copy(alpha = 0.45f), Offset(x(from), h * 0.3f), Size((x(to) - x(from)).coerceAtLeast(6f), h * 0.4f), CornerRadius(6f))
                }
                if (p.pause == null) steps.forEach { s ->
                    val d = LocalDate.parse(s.date); if (d.isBefore(start) || !d.isBefore(end)) return@forEach
                    val missed = s.outcome == Outcome.MISSED
                    if (s.done || missed) drawCircle(if (missed) red else color, 5f, Offset(x(d), h / 2))
                    else { drawCircle(Color.White, 5f, Offset(x(d), h / 2)); drawCircle(color, 5f, Offset(x(d), h / 2), style = Stroke(2f)) }
                }
                p.deadline?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(start) && it.isBefore(end) }?.let { d ->
                    val t = measurer.measure("🏁", TextStyle(fontSize = 11.sp)); drawText(t, topLeft = Offset(x(d) - t.size.width / 2f, 0f))
                }
                drawLine(red, Offset(x(today), 0f), Offset(x(today), h), 2f)
            }
        }
        if (things.isNotEmpty()) Text("🏷 Things", style = MaterialTheme.typography.labelMedium)
        things.forEach { (a, items) ->
            Lane("${Things.icon(a)} ${a.name}", { onThing(a.id) }) { w, h, measurer ->
                fun x(d: LocalDate) = (ChronoUnit.DAYS.between(start, d).toFloat() / DAYS).coerceIn(0f, 1f) * w
                items.filter { it.date >= start.toString() && it.date < end.toString() }.forEach { i ->
                    val t = measurer.measure(i.icon, TextStyle(fontSize = 12.sp))
                    drawText(t, topLeft = Offset(x(LocalDate.parse(i.date)) - t.size.width / 2f, (h - t.size.height) / 2))
                }
                drawLine(red, Offset(x(today), 0f), Offset(x(today), h), 2f)
            }
        }
        Text("● done · ○ planned · red missed · 🏁 deadline · grey stripes paused · red line today. Tap a name to open it.", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Lane(name: String, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float, Float, androidx.compose.ui.text.TextMeasurer) -> Unit) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(96.dp).clickable(onClick = onClick).padding(end = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        Canvas(Modifier.weight(1f).height(30.dp)) {
            (0..8).forEach { k -> val gx = size.width * k / 8f; drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), 1f) }
            draw(size.width, size.height, measurer)
        }
    }
}
