package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opslegal.tda.core.model.TaskKind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Search
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun shortDay(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth}"

/**
 * When: nothing chosen = the next free cell. ✨ is the day suggested (said in a note or the words typed, or by the
 * assistant); then Today, Tomorrow, or a day picked. The day shows at the moment of the choice, so nothing needs
 * pushing around after.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenChips(value: LocalDate?, onChange: (LocalDate?) -> Unit, suggested: LocalDate? = null, label: String = "When", board: Board? = null) {
    val today = LocalDate.now(); val tomorrow = today.plusDays(1)
    val s = suggested?.takeIf { !it.isBefore(today) }
    // A day set from outside (the assistant's draft) that is no chip shows as a picked date.
    val custom = value != null && value != today && value != tomorrow && value != s
    var pickMode by remember { mutableStateOf(false) }
    val picking = pickMode || custom
    var text by remember(value) { mutableStateOf(if (custom) value.toString() else "") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagChip(value == null && !picking, { pickMode = false; onChange(null) }, label = { Text("Next free") })
            if (s != null && s != today && s != tomorrow) TagChip(!picking && value == s, { pickMode = false; onChange(s) }, label = { Text("✨ ${shortDay(s)}") })
            TagChip(!picking && value == today, { pickMode = false; onChange(today) }, label = { Text((if (s == today) "✨ " else "") + "Today") })
            TagChip(!picking && value == tomorrow, { pickMode = false; onChange(tomorrow) }, label = { Text((if (s == tomorrow) "✨ " else "") + "Tomorrow") })
            TagChip(picking, { pickMode = true; onChange(runCatching { LocalDate.parse(text) }.getOrNull()) }, label = { Text(if (custom && value != null) "📅 ${shortDay(value)}" else "📅 Pick a date") })
        }
        var other by remember { mutableStateOf(false) }
        if (picking && board != null && !other && (value == null || pickMode)) DayPicker(board, value) { d -> text = d.toString(); pickMode = false; onChange(d) }
        if (picking && board != null && !other) TextButton(onClick = { other = true }) { Text("📅 Another date…") }
        if (picking && (board == null || other)) DateField("Day", text, { text = it; pickMode = true; onChange(runCatching { LocalDate.parse(it) }.getOrNull()) })
    }
}

/**
 * Picking a day while seeing the table: the coming days with their cells (mini, in the table's colours) and how many
 * are free. One tap picks; a full day can't be picked.
 */
@Composable
private fun DayPicker(board: Board, selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val days = remember(board) {
        val byDay = board.tasks.flatMap { t -> t.steps.filter { it.date != null && it.slot != null }.map { Triple(it.date!!, it.slot!!, t to it) } }.groupBy { it.first }
        (0L until 42L).map { today.plusDays(it) }.filter { d -> com.opslegal.tda.core.plan.Areas.shown(d, board.settings) || byDay.containsKey(d.toString()) }
            .map { d -> d to (0 until com.opslegal.tda.core.model.SLOTS_PER_DAY).map { s -> byDay[d.toString()]?.firstOrNull { it.second == s }?.third } }
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().heightIn(max = 270.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState())) {
        days.forEach { (d, cells) ->
            val free = cells.count { it == null }
            val on = d == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 42.dp)
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                    .then(if (free > 0) Modifier.clickable { onPick(d) } else Modifier).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(shortDay(d), fontSize = 12.sp, fontWeight = if (d == today) FontWeight.Bold else null, modifier = Modifier.width(44.dp),
                    color = if (free > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                cells.forEach { c ->
                    val shape = RoundedCornerShape(4.dp)
                    if (c == null) Box(Modifier.weight(1f).height(32.dp).border(1.dp, outline, shape))
                    else {
                        val (t, st) = c
                        val missed = st.outcome == com.opslegal.tda.core.model.Outcome.MISSED
                        Box(Modifier.weight(1f).height(32.dp).clip(shape)
                            .background(if (st.done) DoneYellow else if (missed) kindColor(TaskKind.DEADLINE).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, if (missed) kindColor(TaskKind.DEADLINE) else outline, shape).padding(2.dp)) {
                            Text(if (t.isProject) st.title else t.title, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = kindColor(t.kindOf(st), onYellow = st.done, inProject = t.isProject))
                        }
                    }
                }
                Text(if (free > 0) "$free free" else "full", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
            }
            HorizontalDivider(color = outline)
        }
    }
}

/**
 * Project, typed: a few letters show the matching projects at once, on the phone (case, accents and word order don't
 * matter). "＋ New project" comes last; the new project is only created when the form is saved.
 */
@Composable
internal fun ProjectPicker(board: Board, value: String, onChange: (String) -> Unit, label: String = "Part of a project? (optional)") {
    var focused by remember { mutableStateOf(false) }
    // Running projects first; a parked idea can take a task too.
    val names = remember(board.projects) {
        board.projects.sortedBy { com.opslegal.tda.core.plan.Projects.isIdea(board, it) }.map { it.name }
    }
    Column {
        CompactField(value, onChange, label, Modifier.fillMaxWidth(), onFocus = { focused = it })
        val q = value.trim()
        val exact = names.any { Search.fold(it) == Search.fold(q) }
        if (focused && !exact) {
            val found = Search.ranked(q, names).take(if (q.isEmpty()) 5 else 6)
            Column(Modifier.padding(end = 8.dp)) {
                found.forEach { n -> Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable { onChange(n); focused = false }.padding(10.dp)) }
                if (q.isNotEmpty()) Text("＋ New project “$q”", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable { focused = false }.padding(10.dp))
            }
        }
    }
}

/** The project name as saved: an existing one written another way ("beneva") becomes its real name ("Bénéva"). */
internal fun projectName(board: Board, typed: String): String {
    val q = typed.trim(); if (q.isEmpty()) return ""
    return board.projects.firstOrNull { Search.fold(it.name) == Search.fold(q) }?.name ?: q
}

/** Find, in the header: a magnifier where the bell was. */
@Composable
internal fun FindButton(vm: MainViewModel) {
    IconButton(onClick = { vm.findOpen.value = true }, modifier = androidx.compose.ui.Modifier.size(HeadIconSize)) { Icon(Icons.Filled.Search, "Find in your table and calendar") }
}

/** Jimmy's face: one count for what arrived and his suggestions; red when either needs the user now. */
@Composable
internal fun JimmyButton(vm: MainViewModel) {
    val flags by vm.flags.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    val red = inbox.any { it.urgent } || Updates.reviewDue(board, now) || flags.any { it.red }
    val n = inbox.size + flags.size
    Box {
        IconButton(onClick = { vm.hubOpen.value = true }, modifier = androidx.compose.ui.Modifier.size(HeadIconSize)) {
            Avatar(board.persona, 30.dp, Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape))
        }
        if (n > 0) CountBadge(n, red, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
    }
}

/** Jimmy's panel: two round buttons, what arrived (notifications) and his suggestions, each with its count and colour. */
@Composable
internal fun HubSheet(vm: MainViewModel) {
    val open by vm.hubOpen.collectAsStateWithLifecycle()
    if (!open) return
    val flags by vm.flags.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    val red = inbox.any { it.urgent } || Updates.reviewDue(board, now)
    val fred = flags.any { it.red }
    val close = { vm.hubOpen.value = false }
    val name = if (com.opslegal.tda.core.agent.Me.named(board)) com.opslegal.tda.core.agent.Me.name(board) else "Your assistant"
    SoftDialog(
        onDismissRequest = close,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Avatar(board.persona, 30.dp); Text(name) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        HubButton("🔔", "Notifications", inbox.size, red, if (inbox.isEmpty()) "Nothing waiting" else "${inbox.size} waiting${if (red) " · now" else ""}") {
                            close(); vm.updatesOpen.value = true
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        HubButton("💡", "Suggestions", flags.size, fred, if (flags.isEmpty()) "No idea now" else "${flags.size} idea${if (flags.size > 1) "s" else ""}") {
                            close(); vm.ideasOpen.value = true
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        HubButton("🧹", "Filtered", board.filtered.size, false, if (board.filtered.isEmpty()) "Nothing" else "${board.filtered.size} without alert") {
                            close(); vm.filteredOpen.value = true
                        }
                    }
                }
                Text("Notifications: what needs you. Suggestions: my ideas for your week. Filtered: ads, suspicious and ignored, never lost.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                // The two looks, past and ahead: twin rounded squares, same size, each its own colour.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LookTile("✨ Last 30 days", "projects, tasks, bills from your mail", listOf(Color(0xFF1F2A44), Color(0xFF33415E)), Modifier.weight(1f)) { close(); vm.startFirstLook() }
                    LookTile("🔭 Next 60 days", "duplicates and improvements", listOf(Color(0xFF1F5A5E), Color(0xFF2F7F80)), Modifier.weight(1f)) { close(); vm.startLookAhead() }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        // Check now: new emails, messages and calendar; the suggestions follow the table by themselves.
        dismissButton = {
            val checking by vm.checking.collectAsStateWithLifecycle()
            TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text(if (checking) "Checking…" else "Check now") }
        },
    )
}

@Composable
internal fun LookTile(label: String, sub: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 76.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(colors)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HubButton(icon: String, label: String, count: Int, red: Boolean, sub: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 130.dp)) {
        Box {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(if (red) Color(0xFF5C2A33) else Color(0xFF1F2A44)).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Text(icon, style = MaterialTheme.typography.headlineMedium) }
            if (count > 0) CountBadge(count, red, Modifier.align(Alignment.TopEnd), big = true)
        }
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * Find: one box over the table, the phone's calendar and the rest, live as you type and all on the phone (never a
 * request to the assistant). Coming up first, soonest first; a day typed ("friday", "oct 20") jumps to its line.
 */
@Composable
internal fun FindSheet(vm: MainViewModel) {
    val open by vm.findOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val events by vm.calendarEvents.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf(vm.findQuery) }
    val hits = remember(q, board, events) { Search.find(board, events, q, LocalDate.now()) }
    val close = { vm.findQuery = q; vm.findOpen.value = false }
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SoftDialog(
        onDismissRequest = close,
        title = { Text("Find") },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactField(q, { q = it }, "Search your table and calendar", Modifier.fillMaxWidth().focusRequester(focus))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when {
                        q.isBlank() -> Text("A task, a project, a person, a calendar event, or a day (“friday”, “oct 20”).", style = MaterialTheme.typography.bodySmall)
                        hits.isEmpty() -> Text("Nothing for “$q”.", style = MaterialTheme.typography.bodySmall)
                    }
                    var group = ""
                    hits.forEach { h ->
                        if (h.group != group) { group = h.group; Text(group, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                        Column(Modifier.fillMaxWidth().clickable { close(); vm.openFound(h.open, h.date) }.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val d = h.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                                Text(d?.let { shortDay(it) } ?: h.icon, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    color = if (d != null) MaterialTheme.colorScheme.primary else Color.Unspecified, modifier = Modifier.widthIn(min = 48.dp))
                                Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(h.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (h.sub.isNotBlank()) Text(h.sub.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
