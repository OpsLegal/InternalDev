package com.opslegal.tda.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.model.Routine
import com.opslegal.tda.core.model.RoutineWish
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Gbn
import com.opslegal.tda.core.plan.Routines
import com.opslegal.tda.core.plan.Tags
import kotlinx.coroutines.launch

/** Big two-column tiles: one per section, so a long page becomes a few thumb-sized choices. */
@Composable
internal fun Tiles(tiles: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, title) ->
                    Card(
                        onClick = { onOpen(title) },
                        modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(icon, fontSize = 26.sp)
                            Text(title, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A section title with its "?" (the explanation opens under it). */
@Composable
internal fun TitleHelp(title: String, help: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            HelpButton(open, { open = !open }, Modifier.size(28.dp))
        }
        if (open) HelpText(help)
    }
}

/** "‹ Playbook": back to the tiles. */
@Composable
internal fun BackToTiles(label: String, onBack: () -> Unit) {
    OutlinedButton(onClick = onBack, modifier = Modifier.padding(vertical = 6.dp)) { Text("‹ $label") }
}

/**
 * The first thing in Playbook: the "I am…" tags, "Tell us about you" (or more, later), and the values bar. A tag tap or a
 * bar tap changes the weights right away; More opens what each value means.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileCard(vm: MainViewModel, board: Board, onMore: () -> Unit) {
    var telling by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My profile", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onMore) { Text("More ›") }
            }
            Text("I am… (as many as fit you)", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tags.all(board).keys.forEach { t ->
                    val on = t in board.tags
                    TagChip(on, { vm.toggleTag(t) }, label = { Text(if (on) "✓ $t" else t) })
                }
                TagChip(false, { telling = true }, label = { Text("🎤 " + if (board.about.bio.isBlank()) "Tell us about you" else "Tell us more") })
            }
            Text(
                if (board.tags.isEmpty()) "No tag yet: everything counts the same (Neutral)." else "Each bar takes the highest of your tags." +
                    if (board.adjust.isNotEmpty()) " ${board.adjust.size} set by you." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (board.adjust.isNotEmpty()) TextButton(onClick = vm::clearAdjust) { Text("Back to my tags' weights") }
            if (board.gbn && board.values.isNotEmpty()) {
                GbnStrip(board.values, emptyMap(), onTap = { name, n -> vm.setWeight(name, n) })
                Text("Tap a tag to add or remove it, a bar to make it count more or less.", style = MaterialTheme.typography.bodySmall)
            } else Text("Tap a tag: the values bar (Ground · Build · Nourish) turns on and weighs your week.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (telling) TellUsDialog(vm, onDone = { telling = false })
}

/** A few lines about you become tags (and new ones of your own), shown before they apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TellUsDialog(vm: MainViewModel, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<MainViewModel.FoundTags?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bio = vm.board.value.about.bio
    SoftDialog(
        keepOpen = true, onDismissRequest = onDone,
        title = { Text(if (bio.isBlank()) "Tell us about you" else "Tell us more") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A few lines: what you do, who counts, what you love. E.g. “Founder of two companies, I invest in real estate, two kids, I run marathons.”",
                    style = MaterialTheme.typography.bodySmall)
                if (bio.isNotBlank()) Text("Already said: “${bio.take(140)}${if (bio.length > 140) "…" else ""}”", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompactField(text, { text = it }, "About you", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                found?.let { f ->
                    if (f.tags.isEmpty() && f.custom.isEmpty()) Text("No tag found. Tap the ones that fit you instead.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("I'd add:", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (f.tags + f.custom.keys.map { "✦ $it" }).forEach { TagChip(true, {}, label = { Text(it) }) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val f = found
            if (f != null && (f.tags.isNotEmpty() || f.custom.isNotEmpty())) TextButton(onClick = { vm.useTags(f); onDone() }) { Text("Use these tags") }
            else TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                val all = (if (bio.isBlank()) "" else "$bio\n") + text.trim()
                scope.launch { found = runCatching { vm.findTags(all) }.getOrNull() ?: MainViewModel.FoundTags(emptyList(), emptyMap()); busy = false }
            }) { Text("Find my tags") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * My routines: the week as 7 days × 5 moments (tap a moment to add a routine there), the active routines as rows, then
 * the wish list with what the calendar and repeated tasks suggest. No clock times, no warnings: the weekly review advises.
 */
@Composable
internal fun RoutinesSection(vm: MainViewModel, board: Board) {
    var editing by remember { mutableStateOf<Routine?>(null) }
    var slot by remember { mutableStateOf<Pair<Int, Moment>?>(null) }
    var fromWish by remember { mutableStateOf<String?>(null) }
    var wish by remember { mutableStateOf("") }
    val hints = remember(board.routines, board.routineNo, board.tasks.size) { vm.routineHints() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TitleHelp("My routines", "What you do regularly to stay balanced: sport, a family dinner, a walk. Tap a moment of the week to add one. Routines are never checked: they count as done in your week's balance. With none, each attribute counts as average. No clock times: doing sport that day is what counts.")
        // The grid: day letters on top, one row per moment.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.width(30.dp))
            Routines.days.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
        Moment.values().forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.icon, Modifier.width(30.dp), textAlign = TextAlign.Center, fontSize = 18.sp)
                (1..7).forEach { d ->
                    val here = Routines.at(board, d, m)
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (here.isEmpty()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "${Routines.dayNames[d - 1]} ${m.label}") {
                                if (here.isEmpty()) { editing = Routine(BoardOps.newId(), "", listOf(d), m); fromWish = null } else slot = d to m
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (here.isEmpty()) Text("+", color = MaterialTheme.colorScheme.outline)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            here.take(2).forEach { Text(it.title, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (here.size > 2) Text("+${here.size - 2}", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Text(Moment.values().joinToString("   ") { "${it.icon} ${it.label}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHead("Active", "${board.routines.size}")
        if (board.routines.isEmpty()) Text("None yet: tap a moment above.", style = MaterialTheme.typography.bodySmall)
        board.routines.forEach { r ->
            RowLine(r.moment.icon, r.title, "${if (r.days.size == 7) "Every day" else r.days.joinToString(" ") { Routines.days[it - 1] }} · ${r.moment.label.lowercase()} · ${serveWords(r.serve)}") {
                TextButton(onClick = { editing = r; fromWish = null }) { Text("Edit") }
            }
        }
        SectionHead("Wish list", "routines you'd like to start")
        hints.forEach { h ->
            RowLine(if (h.from == "calendar") "📅" else "🔁", h.title, h.note) {
                TextButton(onClick = { vm.useHint(h) }) { Text("Make it a routine") }
                TextButton(onClick = { vm.declineHint(h.title) }) { Text("No") }
            }
        }
        board.routineWishes.forEach { w ->
            RowLine("✦", w.title, "") {
                TextButton(onClick = { val (d, m) = Routines.freeSlot(board); editing = Routine(BoardOps.newId(), w.title, listOf(d), m, w.serve); fromWish = w.id }) { Text("Start") }
                TextButton(onClick = { vm.removeWish(w.id) }) { Text("✕") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(wish, { wish = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("E.g. Yoga, piano, Sunday hike") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (wish.isNotBlank()) { vm.addWish(wish); wish = "" } }))
            OutlinedButton(onClick = { if (wish.isNotBlank()) { vm.addWish(wish); wish = "" } }) { Text("＋ Wish") }
        }
    }
    slot?.let { (d, m) ->
        val here = Routines.at(board, d, m)
        SoftDialog(
            onDismissRequest = { slot = null },
            title = { Text("${m.icon} ${Routines.dayNames[d - 1]} · ${m.label.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    here.forEach { r ->
                        Card(onClick = { slot = null; editing = r; fromWish = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(r.title, fontWeight = FontWeight.SemiBold)
                                Text("${r.days.joinToString(" ") { Routines.days[it - 1] }} · ${serveWords(r.serve)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { slot = null; editing = Routine(BoardOps.newId(), "", listOf(d), m); fromWish = null }) { Text("＋ Add here") } },
            dismissButton = { TextButton(onClick = { slot = null }) { Text("Close") } },
        )
    }
    editing?.let { r ->
        RoutineDialog(board, r, isNew = board.routines.none { it.id == r.id },
            onSave = { vm.saveRoutine(it, fromWish); editing = null },
            onDelete = { vm.deleteRoutine(r.id); editing = null },
            onDismiss = { editing = null })
    }
}

internal fun serveWords(serve: Map<String, Int>) = serve.entries.joinToString(" ") { "${it.key} " + "●".repeat(it.value) }

@Composable
internal fun SectionHead(title: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A table-style row: icon, short title, one line under it, small actions on the right. */
@Composable
internal fun RowLine(icon: String, title: String, sub: String, actions: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, Modifier.width(26.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp))
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoutineDialog(board: Board, start: Routine, isNew: Boolean, onSave: (Routine) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(start.title) }
    var days by remember { mutableStateOf(start.days) }
    var moment by remember { mutableStateOf(start.moment) }
    var serve by remember { mutableStateOf(start.serve) }
    SoftDialog(
        keepOpen = true, onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New routine" else "Routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpField(title, { title = it }, "Name", "E.g. Gym, Sunday family dinner, Run with Paul.")
                Text("When in the day", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Moment.values().forEach { m ->
                        val on = m == moment
                        Column(
                            Modifier.weight(1f).heightIn(min = 68.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Navy else MaterialTheme.colorScheme.surface)
                                .border(1.dp, if (on) Navy else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = m.label) { moment = m }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text(m.icon, fontSize = 20.sp)
                            Text(m.label.replace("Early morning", "Early"), fontSize = 10.sp, maxLines = 1,
                                color = if (on) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text(moment.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Which days", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Routines.days.forEachIndexed { i, l ->
                        val d = i + 1; val on = d in days
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(50))
                                .background(if (on) Navy else MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                .clickable { days = if (on) days - d else (days + d).sorted() },
                            contentAlignment = Alignment.Center,
                        ) { Text(l, fontSize = 12.sp, color = if (on) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface) }
                    }
                }
                if (board.gbn && board.values.isNotEmpty()) {
                    Text("What it serves", style = MaterialTheme.typography.labelMedium)
                    GbnStrip(board.values, Gbn.share(board, serve), serve, onTap = { n, l -> serve = serve.tapped(n, l) })
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Remove this routine", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && days.isNotEmpty(), onClick = { onSave(start.copy(title = title.trim(), days = days, moment = moment, serve = serve)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
