package com.opslegal.tda.ui

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.Priority
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Values
import com.opslegal.tda.voice.VoiceState
import java.time.LocalDate

private const val DAYS_BACK = 14L
private const val DAYS_AHEAD = 60

/** The main screen: the 6-column table (day + 5 equal task cells). */
@Composable
fun TableScreen(vm: MainViewModel, modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val rows = remember(board, settings.dayLanguage, today) {
        Planner.rows(board, today.minusDays(DAYS_BACK), DAYS_BACK.toInt() + DAYS_AHEAD, settings.dayLanguage)
    }
    // First launch, one card at a time: the welcome, then "tell me about you" (until values exist or it is skipped).
    val showWelcome = !board.about.welcomed && board.tasks.isEmpty()
    val showProfile = showWelcome || (board.about.profile == null && board.values.isEmpty() && board.about.bio.isBlank())
    // The first-launch card, when shown, sits just above today's line, so the table opens on both.
    val before = rows.indexOfFirst { it.date >= today.toString() }.coerceAtLeast(0)
    val todayItem = before + if (showProfile) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = before)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<TableDialog?>(null) }
    // "Make it a project" asked from an idea: the project form, filled from that task.
    val convert by vm.convertTask.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(convert) { convert?.let { dialog = TableDialog.Project(null, fromTask = it); vm.convertTask.value = null } }
    // A result of the assistant's search: its cell's menu.
    val openCell by vm.openCell.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openCell) { openCell?.let { (id, d) -> dialog = TableDialog.CellMenu(id, d); vm.openCell.value = null } }
    // Find: the day's line comes into view (just below the header), then its cell opens if it has one.
    val jump by vm.jumpDay.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(jump, rows) {
        val d = jump ?: return@LaunchedEffect
        val i = rows.indexOfFirst { it.date == d }
        if (i >= 0) listState.animateScrollToItem(i + if (showProfile && i >= before) 1 else 0)
        vm.jumpDay.value = null
    }
    val openProject by vm.openProject.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openProject) { openProject?.let { dialog = TableDialog.Project(it); vm.openProject.value = null } }
    // Moving a cell by hand: the lifted step, where each empty cell is, and where the finger is.
    val moving by vm.moving.collectAsStateWithLifecycle()
    val drops = remember(moving, board, rows) { moving?.let { id -> rows.associate { r -> r.date to com.opslegal.tda.core.plan.Moves.canDrop(board, id, LocalDate.parse(r.date), today) } } }
    val dropRects = remember { androidx.compose.runtime.mutableStateMapOf<String, Rect>() }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun dropAt(p: Offset): Pair<String, Int>? = dropRects.entries.firstOrNull { (k, r) -> r.contains(p) && drops?.get(k.substringBefore('|'))?.ok == true }
        ?.key?.let { it.substringBefore('|') to it.substringAfter('|').toInt() }
    val hover = dragPos?.let { dropAt(it) }
    // Held near the top or bottom while carrying a cell: the table keeps scrolling, so any day can be reached.
    androidx.compose.runtime.LaunchedEffect(dragPos != null) {
        while (dragPos != null) {
            val p = dragPos ?: break; val b = listBounds
            if (b != null) { if (p.y < b.top + 48f) listState.scrollBy(-14f) else if (p.y > b.bottom - 90f) listState.scrollBy(14f) }
            kotlinx.coroutines.delay(16)
        }
    }
    val mic = rememberMicAction(vm)
    val gaps = remember(board, today) { Values.gaps(board, today) }
    val talkAboutMe = rememberWithMic { vm.listenAbout() }
    val events by vm.calendarEvents.collectAsStateWithLifecycle()
    var event by remember { mutableStateOf<com.opslegal.tda.core.agent.CalendarEvent?>(null) }
    var calendarDay by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(settings.calendarAccess) { vm.loadCalendar() }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Fixed top: the title, Today, the bell and any message stay in sight while the days scroll.
            PageHeader(todayTitle(today, settings.dayLanguage == "fr", short = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 390), below = if (board.gbn && board.values.isNotEmpty()) ({ WeekBar(vm) }) else null) {
                OutlinedButton(
                    onClick = { scope.launch { listState.animateScrollToItem(todayItem) } },
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.White),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                ) { Text("Today") }
                HeadIcons(vm)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                if (gaps.isNotEmpty()) {
                    Text(
                        "This week: " + gaps.joinToString(" · ") { "${it.value.name} ${it.count}/${it.min}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                val liftedStep = moving?.let { BoardOps.findStep(board, it) }
                if (liftedStep != null) Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Moving “${Planner.cellTitle(liftedStep.first, liftedStep.second)}”", fontWeight = FontWeight.SemiBold)
                            Text("Drag it, or tap a highlighted empty cell." + if (liftedStep.first.isProject) " A project step stays between its steps before and after." else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.moving.value = null }) { Text("Cancel") }
                    }
                } else notice?.let { n ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .then(if (n.warn) Modifier.border(1.dp, kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE), RoundedCornerShape(12.dp)) else Modifier),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (n.undo) TextButton(onClick = { vm.undoMove() }) { Text("Undo") }
                            TextButton(onClick = vm::dismissNotice) { Text("OK") }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).onGloballyPositioned { listBounds = it.boundsInRoot() },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                rows.forEachIndexed { i, row ->
                    if (i == before && showProfile) item(key = "profile") {
                        if (showWelcome) WelcomeCard(board.checks.times, onDone = vm::markWelcomed, onGetStarted = { vm.getStarted.value = 0 })
                        else AboutYouCard(vm, onVoice = talkAboutMe, onSkip = { vm.chooseProfile("none") })
                    }
                    item(key = row.date) {
                    // Complete (yellow day): its cells are done and the next day's events are reviewed.
                    val next = rows.getOrNull(i + 1)
                    val complete = row.allDone && (next == null || com.opslegal.tda.core.plan.CalendarCells.reviewed(board, events, next.date))
                    // Under the day's name: its calendar events (count), to review the day before.
                    val calendar = if (row.date >= today.toString()) com.opslegal.tda.core.plan.CalendarCells.ofDay(board, events, row.date) else null
                    DayLine(
                        row = row,
                        complete = complete,
                        calendar = calendar?.let { (list, open) -> list.size to open },
                        onCalendar = { calendarDay = row.date },
                        isToday = row.date == today.toString(),
                        isPast = row.date < today.toString(),
                        onCell = { cell ->
                            when (moving) {
                                null -> dialog = TableDialog.CellMenu(cell.stepId, row.date)
                                cell.stepId -> vm.moving.value = null // tapped again: put down, unchanged
                                else -> Unit
                            }
                        },
                        onEmpty = { dialog = TableDialog.DayAdd(row.date) },
                        movingId = moving,
                        drop = drops?.get(row.date),
                        hoverSlot = hover?.takeIf { it.first == row.date }?.second,
                        onLift = { cell ->
                            val f = BoardOps.findStep(board, cell.stepId)
                            val no = f?.let { (t, st) -> com.opslegal.tda.core.plan.Moves.cantMove(t, st, today) } ?: "This cell can't move."
                            if (no != null) { android.widget.Toast.makeText(context, no, android.widget.Toast.LENGTH_SHORT).show(); false }
                            else { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.moving.value = cell.stepId; true }
                        },
                        onDrag = { p -> dragPos = p },
                        onRelease = { p ->
                            dragPos = null
                            // Dropped on a highlighted cell: moved. Elsewhere: it stays lifted; tap the cell where it goes.
                            dropAt(p)?.let { (d, s) -> val id = moving; vm.moving.value = null; if (id != null) dialog = TableDialog.Drop(id, d, s) }
                        },
                        onEmptyTap = { s ->
                            val id = moving
                            val dr = drops?.get(row.date)
                            if (id != null && dr?.ok == true) { vm.moving.value = null; dialog = TableDialog.Drop(id, row.date, s) }
                            else android.widget.Toast.makeText(context, "Not there: ${dr?.why?.ifBlank { null } ?: "it can't go on this day"}.", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onEmptyBounds = { s, r -> val k = "${row.date}|$s"; if (r == null) dropRects.remove(k) else dropRects[k] = r },
                    )
                    }
                }
                // Room to scroll the last rows above the buttons.
                item { Box(Modifier.height(260.dp)) }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 88.dp, bottom = 12.dp)) {
            VoiceDock(vm, onMic = { mic(null) })
        }
        // One column within reach of the thumb, the mic (used most) at the bottom; drag it anywhere.
        FloatingButtons(vm) {
            RoundAction(PlusIcon, "Create a new task, project, expense or document", Slate, onClick = { dialog = TableDialog.Work })
            val listening = voice is VoiceState.Listening
            RoundAction(
                MicIcon,
                if (listening) "I'm done" else "Talk to the assistant",
                if (listening) Recording else Navy,
                onClick = { mic(null) },
            )
        }
    }

    UpdatesSheet(vm, onAddTask = { u, thenReply ->
        val add = u.actions.firstOrNull { it.type == "add" }
        // The card's note fills the form: a project it names, a day it says, and its words in the description.
        val note = u.comment.trim()
        dialog = TableDialog.NewTask(
            null,
            Prefill(
                title = add?.title?.ifBlank { null } ?: u.title.ifBlank { u.summary.take(40) },
                notes = (add?.description?.ifBlank { null } ?: "${u.summary}\n(From ${u.from})") + if (note.isNotBlank()) "\n📝 $note" else "",
                project = com.opslegal.tda.core.plan.Updates.projectInNote(board, note) ?: add?.project?.ifBlank { null } ?: u.project,
                kind = add?.kind, effort = add?.effort, intention = add?.intention.orEmpty(), serve = add?.serve.orEmpty(), where = add?.where.orEmpty(),
                day = com.opslegal.tda.core.plan.Updates.dayInNote(note, LocalDate.now()) ?: add?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                // No add proposed: only the message is known, so ✨ is offered to work it out (never shown as prepared).
                prepared = add != null,
            ),
            fromUpdate = u.id, thenReply = thenReply,
        )
    })
    when {
        event != null -> CalendarEventDialog(vm, event!!, onDone = { event = null })
        calendarDay != null -> CalendarDayDialog(vm, calendarDay!!, events, onEvent = { event = it }, onDone = { calendarDay = null })
    }
    TableDialogs(vm, board, settings.dayLanguage, dialog, onDialog = { dialog = it }, onTalk = { day, text ->
        if (text == null) mic(day) else vm.askAssistant(text)
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayLine(
    row: DayRow,
    complete: Boolean,
    /** The day's calendar events: (how many, how many still to review); null for past days. */
    calendar: Pair<Int, Int>?,
    onCalendar: () -> Unit,
    isToday: Boolean,
    isPast: Boolean,
    onCell: (Cell) -> Unit,
    onEmpty: () -> Unit,
    /** Moving a cell by hand: the lifted step, and whether it can go on this day. */
    movingId: String? = null,
    drop: com.opslegal.tda.core.plan.Moves.Drop? = null,
    hoverSlot: Int? = null,
    onLift: (Cell) -> Boolean = { false },
    onDrag: (Offset) -> Unit = {},
    onRelease: (Offset) -> Unit = {},
    onEmptyTap: (Int) -> Unit = {},
    onEmptyBounds: (Int, Rect?) -> Unit = { _, _ -> },
) {
    val outline = MaterialTheme.colorScheme.outline
    val dropBlue = Color(0xFF2F6FDE)
    // The gesture outlives these changing (lifting a cell must not restart the finger's gesture): always read the latest.
    val movingNow by androidx.compose.runtime.rememberUpdatedState(movingId)
    val tap by androidx.compose.runtime.rememberUpdatedState(onCell)
    val lift by androidx.compose.runtime.rememberUpdatedState(onLift)
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    val bar = projectBarColor()
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(2.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.width(44.dp).fillMaxSize().clip(RoundedCornerShape(6.dp))
                .background(if (complete) DoneYellow else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    row.label,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (complete) DoneInk else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                calendar?.let { (count, open) -> CalendarBadge(count, open, onCalendar) }
            }
        }
        row.cells.forEachIndexed { slot, cell ->
            val shape = RoundedCornerShape(6.dp)
            val lifted = cell != null && cell.stepId == movingId
            val target = cell == null && movingId != null && !isPast
            // Where the cell sits on screen, to follow the finger while it carries it.
            val where = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
            if (target) androidx.compose.runtime.DisposableEffect(row.date, slot) { onDispose { onEmptyBounds(slot, null) } }
            // Not done on its (past) day, or the record left when its work went on: red, never hidden.
            val missed = cell != null && !cell.done && (cell.outcome == Outcome.MISSED || (isPast && cell.outcome == null))
            val background = when {
                target && drop?.ok == true -> dropBlue.copy(alpha = if (hoverSlot == slot) 0.45f else 0.16f)
                cell == null -> MaterialTheme.colorScheme.background
                cell.done -> DoneYellow
                missed -> MissedRed
                cell.outcome != null -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.weight(1f).fillMaxSize()
                    .then(if (target && drop?.ok != true) Modifier.alpha(0.35f) else Modifier)
                    .onGloballyPositioned { c -> where[0] = c; if (target) onEmptyBounds(slot, c.boundsInRoot()) }
                    .clip(shape).background(background)
                    .border(if (lifted) 3.dp else if (target && drop?.ok == true) 2.dp else 1.dp, if (lifted) MaterialTheme.colorScheme.primary else if (target && drop?.ok == true) dropBlue else outline, shape)
                    // Every project cell has a thin green bar on its left, whatever its colour.
                    .then(if (cell?.inProject == true && (cell.outcome == null || cell.outcome == Outcome.MISSED)) Modifier.drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                    .then(
                        // A tap opens the cell; held 2 seconds without moving, it lifts and follows the finger.
                        if (cell != null) Modifier
                            .semantics { onClick(label = "Open") { onCell(cell); true } }
                            .pointerInput(cell.stepId) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val slop = viewConfiguration.touchSlop
                                    var moved = false
                                    val up = withTimeoutOrNull(2000L) {
                                        var released: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                        while (released == null && !moved) {
                                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (!c.pressed) released = c
                                            else if ((c.position - down.position).getDistance() > slop) moved = true
                                        }
                                        released
                                    }
                                    if (moved) return@awaitEachGesture // a scroll
                                    if (up != null) { up.consume(); tap(cell); return@awaitEachGesture }
                                    if (movingNow != null || !lift(cell)) return@awaitEachGesture
                                    // Lifted: the finger carries it; the table does not scroll under it.
                                    while (true) {
                                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        val root = where[0]?.takeIf { it.isAttached }?.localToRoot(c.position) ?: break
                                        c.consume()
                                        if (!c.pressed) { release(root); break }
                                        drag(root)
                                    }
                                }
                            }
                        else if (!isPast) Modifier.clickable(onClickLabel = "Add a task on this day", onClick = { if (movingId != null) onEmptyTap(slot) else onEmpty() })
                        else Modifier,
                    )
                    .padding(start = 4.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (cell != null) {
                    Text(
                        (if (cell.outcome == Outcome.PUSHED) "↷ " else "") + cell.title + (if (cell.extras > 0) " +${cell.extras}" else ""),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontWeight = if (cell.priority >= Priority.HIGH && cell.outcome == null) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            missed -> MissedInk
                            cell.outcome != null -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> kindColor(cell.kind, onYellow = cell.done, inProject = cell.inProject)
                        },
                    )
                }
            }
        }
    }
}

/** Pick any number of the user's values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ValueChips(all: List<String>, selected: List<String>, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        all.forEach { name ->
            val on = selected.any { it.equals(name, ignoreCase = true) }
            TagChip(
                selected = on,
                onClick = { onChange(if (on) selected.filterNot { it.equals(name, ignoreCase = true) } else selected + name) },
                label = { Text(name) },
            )
        }
    }
}

/** First launch: one tap to start with values that fit the user's work. No questionnaire. */
/** The day's calendar: dark with the count = events to review; yellow = all reviewed; light 0 = none. */
@Composable
private fun CalendarBadge(count: Int, open: Int, onClick: () -> Unit) {
    val (bg, fg) = when {
        count == 0 -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        open > 0 -> MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface
        else -> DoneYellow to DoneInk
    }
    Text(
        "📅 $count", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(bg)
            .border(1.dp, if (count == 0) MaterialTheme.colorScheme.outlineVariant else bg, RoundedCornerShape(9.dp))
            .clickable(onClickLabel = if (count == 0) "No calendar events" else "$count calendar events, $open to review", onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** A day's calendar: tap an event for in my day or not; one tap leaves the rest out. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalendarDayDialog(
    vm: MainViewModel,
    day: String,
    events: List<com.opslegal.tda.core.agent.CalendarEvent>,
    onEvent: (com.opslegal.tda.core.agent.CalendarEvent) -> Unit,
    onDone: () -> Unit,
) {
    val board by vm.board.collectAsStateWithLifecycle()
    val (list, open) = com.opslegal.tda.core.plan.CalendarCells.ofDay(board, events, day)
    SoftDialog(
        onDismissRequest = onDone,
        title = { Text("📅 " + vm.dayName(LocalDate.parse(day))) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) Text("Nothing in your calendar this day.")
                else Text("Tap an event: in my day (it takes a cell) or not in my day (it stays in your calendar).", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { e ->
                        val added = com.opslegal.tda.core.plan.CalendarCells.isAdded(board, e)
                        val out = com.opslegal.tda.core.plan.CalendarCells.isLeftOut(board, e)
                        val start = com.opslegal.tda.core.plan.CalendarCells.startTime(e)
                        val (bg, fg) = when {
                            added -> MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface
                            out -> DoneYellow to DoneInk
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            listOf(start, e.title.ifBlank { "Busy" }).filter { it.isNotBlank() }.joinToString(" ") + if (added) " ✓ in my day" else "",
                            fontSize = 13.sp, color = fg,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(bg)
                                .border(1.dp, if (added || out) bg else MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp))
                                .clickable { onEvent(e) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                if (open > 1 || (open > 0 && open < list.size)) {
                    OutlinedButton(onClick = {
                        vm.leaveOutCalendarEvents(list.filter { board.calendarChoices[com.opslegal.tda.core.plan.CalendarCells.key(it)] == null })
                    }) { Text("Leave the rest out ($open)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

/** Add to my day, or not: each choice shows what it does before the tap. */
@Composable
private fun CalendarEventDialog(vm: MainViewModel, e: com.opslegal.tda.core.agent.CalendarEvent, onDone: () -> Unit) {
    val board by vm.board.collectAsStateWithLifecycle()
    val inDay = com.opslegal.tda.core.plan.CalendarCells.isAdded(board, e)
    val preview = remember(e) { vm.previewCalendar(e) }
    val day = com.opslegal.tda.core.plan.CalendarCells.date(e)?.let(vm::dayName).orEmpty()
    SoftDialog(
        onDismissRequest = onDone,
        title = { Text("📅 ${e.title.ifBlank { "Busy" }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$day, ${com.opslegal.tda.core.plan.CalendarCells.whenText(e)} · from your calendar", style = MaterialTheme.typography.bodySmall)
                if (inDay) {
                    Text("It is in your day ✓")
                    OutlinedButton(onClick = { vm.takeOutCalendarEvent(e); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Take it out of my day") }
                    Text("Its cells free up for work; the event stays in your calendar.", style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                androidx.compose.material3.Button(onClick = { vm.addCalendarEvent(e); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Add to my day") }
                Text(
                    "Takes " + when (preview.cells) { 5 -> "the whole day"; 1 -> "1 cell"; else -> "${preview.cells} cells" } +
                        " (black). $day: ${preview.left} cell${if (preview.left == 1) "" else "s"} left for work." +
                        if (preview.moved.isNotEmpty()) " Moves: ${preview.moved.joinToString("; ")}." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { vm.leaveOutCalendarEvent(e); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Not in my day") }
                Text("Lunch, someone else's event, a reminder… It stays in your calendar and turns yellow (reviewed), without taking a cell.", style = MaterialTheme.typography.bodySmall)
                Text("Reviewing $day's events is part of completing the day before.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

/** Today, in words, for the table's header: "Fri, Oct 9" (« ven. 9 oct. » with French day letters). */
internal fun todayTitle(today: java.time.LocalDate, french: Boolean, short: Boolean = false): String =
    // A narrow phone: "Wed 14" (the table shows the days), so the header's buttons keep their room.
    if (short) today.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", if (french) java.util.Locale.CANADA_FRENCH else java.util.Locale.ENGLISH)).replaceFirstChar { it.uppercase() }
    else if (french) today.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.CANADA_FRENCH)).replaceFirstChar { it.uppercase() }
    else today.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH))
