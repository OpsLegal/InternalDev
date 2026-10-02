package com.opslegal.tda.ui

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    val showProfile = board.about.profile == null && board.values.isEmpty()
    // The first-launch card, when shown, sits just above today's line, so the table opens on both.
    val before = rows.indexOfFirst { it.date >= today.toString() }.coerceAtLeast(0)
    val todayItem = before + if (showProfile) 1 else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = before)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<TableDialog?>(null) }
    val mic = rememberMicAction(vm)
    val gaps = remember(board, today) { Values.gaps(board, today) }
    val talkAboutMe = rememberWithMic { vm.listenAbout() }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Fixed top: the title, Today, the bell and any message stay in sight while the days scroll.
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("My 5 a day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    CartButton(vm)
                    OutlinedButton(onClick = { scope.launch { listState.animateScrollToItem(todayItem) } }) { Text("Today") }
                    header()
                }
                if (gaps.isNotEmpty()) {
                    Text(
                        "This week: " + gaps.joinToString(" · ") { "${it.value.name} ${it.count}/${it.min}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                notice?.let { n ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .then(if (n.warn) Modifier.border(1.dp, kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE), RoundedCornerShape(12.dp)) else Modifier),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::dismissNotice) { Text("OK") }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                rows.forEachIndexed { i, row ->
                    if (i == before && showProfile) item(key = "profile") { ProfileCard(onPick = vm::chooseProfile, onVoice = talkAboutMe) }
                    item(key = row.date) {
                    DayLine(
                        row = row,
                        isToday = row.date == today.toString(),
                        isPast = row.date < today.toString(),
                        onCell = { cell -> dialog = TableDialog.CellMenu(cell.stepId, row.date) },
                        onEmpty = { dialog = TableDialog.NewTask(row.date) },
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
            RoundAction(FolderIcon, "Project", Bordeaux, onClick = { dialog = TableDialog.Chooser(project = true) })
            RoundAction(TaskBoxIcon, "Task", Slate, onClick = { dialog = TableDialog.Chooser(project = false) })
            val listening = voice is VoiceState.Listening
            RoundAction(
                MicIcon,
                if (listening) "I'm done" else "Talk to the assistant",
                if (listening) Recording else Navy,
                onClick = { mic(null) },
            )
        }
    }

    UpdatesSheet(vm)
    CartSheet(vm)
    TableDialogs(vm, board, settings.dayLanguage, dialog, onDialog = { dialog = it }, onTalk = { day, text ->
        if (text == null) mic(day) else vm.askAssistant(text)
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayLine(
    row: DayRow,
    isToday: Boolean,
    isPast: Boolean,
    onCell: (Cell) -> Unit,
    onEmpty: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val bar = projectBarColor()
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(2.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.width(44.dp).fillMaxSize().clip(RoundedCornerShape(6.dp))
                .background(if (row.allDone) DoneYellow else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.label,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (row.allDone) DoneInk else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
        }
        row.cells.forEach { cell ->
            val shape = RoundedCornerShape(6.dp)
            val background = when {
                cell == null -> MaterialTheme.colorScheme.background
                cell.done -> DoneYellow
                cell.outcome != null -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.weight(1f).fillMaxSize().clip(shape).background(background).border(1.dp, outline, shape)
                    // Every project cell has a thin green bar on its left, whatever its colour.
                    .then(if (cell?.inProject == true && cell.outcome == null) Modifier.drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                    .then(
                        if (cell != null) Modifier.combinedClickable(onClick = { onCell(cell) }, onLongClick = { onCell(cell) })
                        else if (!isPast) Modifier.clickable(onClickLabel = "Add a task on this day", onClick = onEmpty)
                        else Modifier,
                    )
                    .padding(start = 4.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (cell != null) {
                    Text(
                        (if (cell.outcome == Outcome.PUSHED) "↷ " else "") + cell.title,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontWeight = if (cell.priority >= Priority.HIGH && cell.outcome == null) FontWeight.SemiBold else FontWeight.Normal,
                        fontStyle = if (isPast && !cell.done && cell.outcome == null) FontStyle.Italic else null,
                        color = when {
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(onPick: (String) -> Unit, onVoice: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What describes you best?", style = MaterialTheme.typography.titleSmall)
            Text(
                "One tap sets what matters to you, so the assistant can choose well. Change it anytime in Playbook.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Values.profiles.forEach { (id, profile) -> OutlinedButton(onClick = { onPick(id) }) { Text(profile.first) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onVoice) {
                    Icon(MicIcon, contentDescription = null)
                    Text("  Or tell me in 60 seconds")
                }
                TextButton(onClick = { onPick("none") }) { Text("Skip") }
            }
        }
    }
}
