package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opslegal.tda.core.plan.Holidays
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * My days off: the next 12 months, top to bottom. Tap a day to add or remove it; press and slide to select (or clear)
 * several at once. Holidays of the region show in grey. Saved only with Save.
 */
@Composable
internal fun DaysOffDialog(start: List<String>, region: String, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val picked = remember { mutableStateMapOf<LocalDate, Boolean>().apply { start.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.forEach { put(it, true) } } }
    val bounds = remember { HashMap<LocalDate, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf<LocalDate?>(null) }
    var adding by remember { mutableStateOf(true) }
    var before by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    fun dayAt(p: Offset): LocalDate? { val q = p + origin; return bounds.entries.firstOrNull { it.value.contains(q) }?.key }
    fun sel() = picked.filterValues { it }.keys
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                PageHeader("My days off") { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } }
                Text("Tap a day, or press and slide to select several. No work is planned on them, and the assistant plans around them.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                        .onGloballyPositioned { origin = it.positionInRoot() }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { p -> dayAt(p)?.let { d -> anchor = d; adding = picked[d] != true; before = sel().toSet(); picked[d] = adding } },
                                onDrag = { change, _ ->
                                    val a = anchor ?: return@detectDragGesturesAfterLongPress
                                    val d = dayAt(change.position) ?: return@detectDragGesturesAfterLongPress
                                    val (lo, hi) = if (d < a) d to a else a to d
                                    picked.clear(); before.forEach { picked[it] = true }
                                    generateSequence(lo) { it.plusDays(1) }.takeWhile { !it.isAfter(hi) }.forEach { picked[it] = adding }
                                },
                                onDragEnd = { anchor = null },
                                onDragCancel = { anchor = null },
                            )
                        },
                ) {
                    (0L until 12L).map { YearMonth.from(today).plusMonths(it) }.forEach { m ->
                        Text(m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.year, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
                        val first = m.atDay(1)
                        val cells = List(first.dayOfWeek.value - 1) { null } + (1..m.lengthOfMonth()).map { m.atDay(it) }
                        cells.chunked(7).forEach { week ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 1.5.dp)) {
                                (0 until 7).forEach { i ->
                                    val d = week.getOrNull(i)
                                    if (d == null) Spacer(Modifier.weight(1f)) else {
                                        val on = picked[d] == true
                                        val holiday = Holidays.isOff(d, region)
                                        val past = d.isBefore(today)
                                        Box(
                                            Modifier.weight(1f).aspectRatio(1.1f).clip(RoundedCornerShape(8.dp))
                                                .background(when { on -> Navy; holiday -> MaterialTheme.colorScheme.surfaceVariant; else -> MaterialTheme.colorScheme.surface })
                                                .border(if (d == today) 2.dp else 1.dp, if (d == today) DoneYellow else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                                .onGloballyPositioned { bounds[d] = it.boundsInRoot() }
                                                .clickable(enabled = !past) { picked[d] = !on },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else null,
                                                color = when { on -> Color.White; past -> MaterialTheme.colorScheme.outline; holiday -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.padding(24.dp))
                }
                Row(Modifier.fillMaxWidth().background(barColor()).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = sel().count { !it.isBefore(today) }
                    Text(if (n == 0) "No day off chosen" else "$n day${if (n > 1) "s" else ""} off", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picked.clear() }) { Text("Clear", color = Color.White) }
                    Button(onClick = { onSave(sel().filter { !it.isBefore(today) }.sorted().map { it.toString() }) }) { Text("Save") }
                }
            }
        }
    }
}
