package com.opslegal.tda.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.QuickThing
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Labels
import com.opslegal.tda.core.plan.Quick
import com.opslegal.tda.core.plan.Shopping
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/* ✏️ On my mind: one page. Write what's on your mind (the choices are pre-selected, always the same, each changeable),
 * then see it on a map (deadline × how much it matters, each one in its area's colour) or as small cards, filtered.
 * The rules fill the choices; they never decide alone. */

private val AreaColors = listOf(Color(0xFF2F6FDE), Color(0xFF2E9E5B), Color(0xFFD9622B), Color(0xFF8A4FD8), Color(0xFFC2185B), Color(0xFF1F8A8A))

internal fun areaColor(board: Board, id: String): Color {
    val i = Areas.all(board.settings).indexOfFirst { it.id == id }
    return AreaColors[(if (i < 0) 0 else i) % AreaColors.size]
}

/** A pencil: On my mind. */
internal val PencilIcon = androidx.compose.ui.graphics.vector.ImageVector.Builder("Pencil", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString("M4 20 L4.8 15.6 L15.6 4.8 L19.2 8.4 L8.4 19.2 Z M13.6 6.8 L17.2 10.4 M4 20 L8.4 19.2").toNodes(),
        fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    )
}.build()

/** In the header: the pencil opens On my mind. */
@Composable
internal fun MindButton(vm: MainViewModel) {
    IconButton(onClick = { vm.mindOpen.value = true }, modifier = Modifier.size(HeadIconSize)) { Icon(PencilIcon, "On my mind") }
}

/** The same choices everywhere: type, area, deadline, how much it matters, effort. */
private data class MindChoice(val kind: String = "quick", val area: String = "", val due: String = "", val impact: Int = 2, val effort: Int = 1)

private val URGENT = Regex("(?i)\\b(urgent|asap|important|today|aujourd)")

/** What the rules propose for a line (shown pre-selected; the user changes any of it). */
private fun propose(board: Board, text: String, today: LocalDate) = MindChoice(
    kind = Labels.kindOf(text), area = Areas.guess(board, text),
    due = Updates.dayInNote(text, today)?.toString().orEmpty(),
    impact = if (URGENT.containsMatchIn(text)) 3 else 2, effort = if (Labels.sizeOf(text) == "big") 3 else 1,
)

private fun areaOf(board: Board, q: QuickThing) = q.area.ifBlank { Areas.guess(board, q.title) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(vm: MainViewModel, board: Board, c: MindChoice, withKind: Boolean, onChange: (MindChoice, String) -> Unit) {
    val today = LocalDate.now()
    val context = LocalContext.current
    @Composable fun line(label: String, chips: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(66.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
    Column {
        if (withKind) line("Type") {
            listOf("quick" to "⚡ Quick", "task" to "📋 Task", "buy" to "🛒 To buy").forEach { (k, l) -> TagChip(c.kind == k, { onChange(c.copy(kind = k), "kind") }, label = { Text(l) }) }
        }
        if (c.kind == "buy") return@Column
        line("Area") {
            Areas.all(board.settings).forEach { a ->
                TagChip(c.area == a.id, { onChange(c.copy(area = a.id), "area") }, label = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(areaColor(board, a.id))); Text(" " + a.name)
                })
            }
        }
        val week = today.plusDays(7).toString()
        val dueSel = when { c.due.isBlank() -> ""; c.due == today.toString() -> "today"; c.due <= week -> "week"; else -> "pick" }
        line("Deadline") {
            TagChip(dueSel == "", { onChange(c.copy(due = ""), "due") }, label = { Text("None") })
            TagChip(dueSel == "today", { onChange(c.copy(due = today.toString()), "due") }, label = { Text("Today") })
            TagChip(dueSel == "week", { onChange(c.copy(due = today.plusDays(5).toString()), "due") }, label = { Text("This week") })
            TagChip(dueSel == "pick", {
                val d = runCatching { LocalDate.parse(c.due) }.getOrNull() ?: today.plusDays(7)
                android.app.DatePickerDialog(context, { _, y, m, day -> onChange(c.copy(due = LocalDate.of(y, m + 1, day).toString()), "due") }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }, label = { Text(if (dueSel == "pick") "📅 " + vm.dayName(LocalDate.parse(c.due)) else "📅 Pick") })
        }
        line("Matters") { listOf(1 to "A little", 2 to "Normal", 3 to "A lot").forEach { (n, l) -> TagChip(c.impact == n, { onChange(c.copy(impact = n), "impact") }, label = { Text(l) }) } }
        line("Effort") { listOf(1 to "Easy", 2 to "Some", 3 to "Demanding").forEach { (n, l) -> TagChip(c.effort == n, { onChange(c.copy(effort = n), "effort") }, label = { Text(l) }) } }
    }
}

/** A small dropdown filter of the list. */
@Composable
private fun <T> MiniSelect(cur: T, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.fillMaxWidth().height(34.dp)) {
            Text(options.firstOrNull { it.first == cur }?.second.orEmpty() + " ▾", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }) { options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MindSheet(vm: MainViewModel) {
    val open by vm.mindOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    var text by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf(MindChoice()) }
    var touched by remember { mutableStateOf(setOf<String>()) }
    var tab by rememberSaveable { mutableStateOf("map") }
    var areasOn by rememberSaveable { mutableStateOf(listOf<String>()) }
    var dueF by rememberSaveable { mutableStateOf("any") }
    var impF by rememberSaveable { mutableStateOf(0) }
    var effF by rememberSaveable { mutableStateOf(0) }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { sel = null; vm.mindOpen.value = false }
    val picks = remember(board) { Quick.todayIds(board, today).toSet() }
    val all = remember(board) { board.quick.filter { !it.done } }
    val week = today.plusDays(7).toString()
    fun shown(forMap: Boolean) = all.filter { q ->
        (areasOn.isEmpty() || areaOf(board, q) in areasOn) && (forMap || (
            (when (dueF) { "soon" -> q.due.isNotBlank() && q.due <= week; "later" -> q.due > week; "none" -> q.due.isBlank(); else -> true }) &&
                (impF == 0 || q.impact == impF) && (effF == 0 || q.effort == effF)))
    }
    // The rules fill what the user hasn't changed yet; what they changed stays as they set it.
    fun write(v: String) {
        text = v
        val p = propose(board, v, today)
        draft = MindChoice(
            if ("kind" in touched) draft.kind else p.kind, if ("area" in touched) draft.area else p.area,
            if ("due" in touched) draft.due else p.due, if ("impact" in touched) draft.impact else p.impact, if ("effort" in touched) draft.effort else p.effort,
        )
    }
    fun add() {
        val t = text.trim(); if (t.isEmpty()) return
        when (draft.kind) {
            "buy" -> { vm.addToBuy(listOf(t), work = false); vm.notify("🛒 On your To buy list.") }
            "task" -> { vm.addTask(BoardOps.NewTask(t, deadline = draft.due.ifBlank { null }, area = draft.area)); vm.notify("📋 “$t” is in your table.") }
            else -> vm.addQuick(listOf(t), null, draft.due, draft.impact, draft.effort, draft.area)
        }
        text = ""; touched = emptySet(); draft = MindChoice()
    }
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✏️ On my mind", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("✕", fontSize = 20.sp) }
                }
                // Add: type or say it; ＋ adds with the choices below.
                Row(verticalAlignment = Alignment.Top) {
                    CompactField(text, { write(it) }, "What's on your mind? A call, milk, an idea…", Modifier.weight(1f))
                    Box(Modifier.padding(start = 6.dp, top = 7.dp).size(36.dp).clip(CircleShape).background(if (text.isBlank()) Pewter.copy(alpha = 0.4f) else Pewter)
                        .clickable(enabled = text.isNotBlank(), onClickLabel = "Add") { add() }, contentAlignment = Alignment.Center) { Text("＋", color = Color.White, fontSize = 20.sp) }
                    DictateButton(Modifier.padding(start = 6.dp, top = 7.dp)) { said -> write(listOf(text.trim(), said.trim()).filter { it.isNotBlank() }.joinToString(" ")) }
                }
                if (text.isNotBlank()) {
                    Text("${com.opslegal.tda.core.agent.Me.name(board)} pre-selected these: change any of them.", style = MaterialTheme.typography.bodySmall)
                    Choices(vm, board, draft, withKind = true) { c, k -> draft = c; touched = touched + k }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { add() }, modifier = Modifier.weight(1f)) { Text("Add") }
                        TextButton(onClick = { text = ""; touched = emptySet(); draft = MindChoice() }) { Text("Cancel") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(tab == "map", { tab = "map" }, label = { Text("🗺 Map") }, modifier = Modifier.weight(1f))
                    TagChip(tab == "list", { tab = "list" }, label = { Text("☰ List") }, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(areasOn.isEmpty(), { areasOn = emptyList() }, label = { Text("All") })
                    Areas.all(board.settings).forEach { a ->
                        TagChip(a.id in areasOn, { areasOn = if (a.id in areasOn) areasOn - a.id else areasOn + a.id }, label = {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(areaColor(board, a.id))); Text(" " + a.name)
                        })
                    }
                }
                val pick: (String) -> Unit = { id -> sel = if (sel == id) null else id }
                if (tab == "map") {
                    val h = if (landscape) (cfg.screenHeightDp - 40).coerceAtLeast(200) else (cfg.screenHeightDp * 0.48f).toInt().coerceAtLeast(240)
                    MindMap(board, shown(true), picks, sel, h, today, pick)
                    Text("Colour = area · ▂▄▆ = effort · ⚡ = picked for today · tap one to change it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniSelect(dueF, listOf("any" to "📅 Any deadline", "soon" to "📅 This week", "later" to "📅 Later", "none" to "📅 None"), { dueF = it }, Modifier.weight(1f))
                        MiniSelect(impF, listOf(0 to "● Matters: any", 1 to "● A little", 2 to "● Normal", 3 to "● A lot"), { impF = it }, Modifier.weight(1f))
                        MiniSelect(effF, listOf(0 to "💪 Effort: any", 1 to "💪 Easy", 2 to "💪 Some", 3 to "💪 Demanding"), { effF = it }, Modifier.weight(1f))
                    }
                    val list = shown(false).sortedByDescending { Quick.score(it, today) }
                    if (list.isEmpty()) Text("Nothing here with these filters.", style = MaterialTheme.typography.bodySmall)
                    list.chunked(if (landscape) 3 else 2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { q -> MindCard(vm, board, q, q.id in picks, q.id == sel, Modifier.weight(1f)) { pick(q.id) } }
                            repeat((if (landscape) 3 else 2) - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                // The one tapped: the same choices, plus today / not today, bigger, delete.
                all.firstOrNull { it.id == sel }?.let { q ->
                    val c = areaColor(board, areaOf(board, q))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(2.dp, c, RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { sel = null }) { Text("✕") }
                        }
                        Choices(vm, board, MindChoice("quick", areaOf(board, q), q.due, q.impact, q.effort), withKind = false) { n, _ ->
                            vm.editQuick(q.id) { it.copy(area = n.area, due = n.due, impact = n.impact, effort = n.effort) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // One toggle that keeps its place: highlighted when picked for today.
                            TagChip(q.id in picks, { if (q.id in picks) vm.swapQuick(q.id) else vm.quickToday(q.id) }, label = { Text("⚡ Today") })
                            OutlinedButton(onClick = { vm.quickToTask(q.id); sel = null }) { Text("📋 It's bigger: a task") }
                            TextButton(onClick = { vm.removeQuick(q.id); sel = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${all.size} on your mind · ${picks.size} picked for today", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { vm.pickQuickAgain() }) { Text("✨ Pick today's for me") }
                    TextButton(onClick = { vm.buyStatus.value = null; vm.cartOpen.value = true }) { Text("🛒 To buy (${Shopping.open(board).size})") }
                }
            }
        }
    }
}

@Composable
private fun MindCard(vm: MainViewModel, board: Board, q: QuickThing, picked: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = areaColor(board, areaOf(board, q))
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).background(c.copy(alpha = 0.10f)).border(if (selected) 2.5.dp else 1.5.dp, if (selected) MaterialTheme.colorScheme.onSurface else c, shape)
        .clickable(onClick = onClick).padding(8.dp)) {
        Text((if (picked) "⚡ " else "") + q.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Text(" " + (Areas.all(board.settings).firstOrNull { it.id == areaOf(board, q) }?.name ?: "No area"), fontSize = 11.sp)
        }
        Text(if (q.due.isNotBlank()) "📅 " + vm.dayName(LocalDate.parse(q.due)) else "No deadline", fontSize = 11.sp)
        Text("●".repeat(q.impact) + "○".repeat(3 - q.impact) + " matters · " + listOf("", "easy", "some", "demanding")[q.effort.coerceIn(1, 3)], fontSize = 11.sp)
        if (q.check) Text("❔ check", fontSize = 11.sp, color = Color(0xFF8A6500))
    }
}

/** The map: deadline from left (soon) to right (later or none), how much it matters from bottom to top. */
@Composable
private fun MindMap(board: Board, items: List<QuickThing>, picks: Set<String>, sel: String?, heightDp: Int, today: LocalDate, onPick: (String) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxWidth().height(heightDp.dp)) {
        val w = maxWidth.value; val h = maxHeight.value
        val left = 30f; val bottom = h - 26f; val top = 22f; val right = w - 6f
        data class Placed(val q: QuickThing, val x: Float, val y: Float, val w: Float, val label: String)
        val hgt = 26f
        val boxes = remember(items, w, h) {
            fun cx(q: QuickThing): Float {
                val dd = if (q.due.isBlank()) 35 else ChronoUnit.DAYS.between(today, LocalDate.parse(q.due)).toInt().coerceIn(0, 35)
                return left + 4 + dd / 35f * (right - left - 130f).coerceAtLeast(10f)
            }
            fun cy(q: QuickThing) = bottom - 16 - (q.impact.coerceIn(1, 3) - 1) / 2f * (bottom - top - 40)
            val out = mutableListOf<Placed>()
            items.sortedWith(compareByDescending<QuickThing> { it.impact }.thenBy { cx(it) }).forEach { q ->
                val label = q.title.split(Regex("\\s+")).take(4).joinToString(" ").take(22)
                val bw = minOf(170f, 34f + label.length * 6.4f)
                var x = minOf(cx(q), right - bw); val y0 = cy(q) - hgt / 2; var y = y0
                var k = 1
                while (k < 14 && out.any { x < it.x + it.w + 3 && x + bw + 3 > it.x && y < it.y + hgt + 3 && y + hgt + 3 > it.y }) {
                    y = y0 + (if (k % 2 == 1) -1 else 1) * ((k + 1) / 2) * (hgt + 4)
                    if (y < top || y + hgt > bottom) { y = y0; x = minOf(x + 24, right - bw) }
                    k++
                }
                out += Placed(q, x, y.coerceIn(top, bottom - hgt), bw, label)
            }
            out
        }
        Canvas(Modifier.fillMaxSize()) {
            val px = density
            drawRect(Color(0xFFE5534B).copy(alpha = 0.06f), Offset(left * px, 6 * px), Size((right - left) / 4 * px, (bottom - 6) * px))
            drawLine(outline, Offset(left * px, bottom * px), Offset(right * px, bottom * px), 1 * px)
            drawLine(outline, Offset(left * px, 6 * px), Offset(left * px, bottom * px), 1 * px)
        }
        Text("↑ matters more", fontSize = 10.sp, color = muted, modifier = Modifier.offset(4.dp, 2.dp))
        Text("deadline soon", fontSize = 10.sp, color = muted, modifier = Modifier.offset((left + 2).dp, (h - 20).dp))
        Text("later · none →", fontSize = 10.sp, color = muted, modifier = Modifier.offset((right - 82).dp, (h - 20).dp))
        boxes.forEach { b ->
            val c = areaColor(board, areaOf(board, b.q))
            val on = b.q.id in picks; val isSel = b.q.id == sel
            val shape = RoundedCornerShape(6.dp)
            Row(
                Modifier.offset(b.x.dp, b.y.dp).size(b.w.dp, hgt.dp).clip(shape).background(c.copy(alpha = 0.24f))
                    .border(if (isSel) 3.dp else if (on) 2.2.dp else 1.5.dp, if (on || isSel) ink else c, shape)
                    .clickable(onClickLabel = b.q.title) { onPick(b.q.id) }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (on) "⚡ " else "") + b.label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    (0..2).forEach { i -> Box(Modifier.width(3.dp).height((4 + i * 3.5f).dp).clip(RoundedCornerShape(1.dp)).background(if (i < b.q.effort) c else outline)) }
                }
            }
        }
        if (items.isEmpty()) Text("Nothing on your mind here. Write it above: it shows up on the map.", style = MaterialTheme.typography.bodySmall, color = muted,
            modifier = Modifier.align(Alignment.Center).padding(24.dp))
    }
}
