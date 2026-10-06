package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.ReplyWriter
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.plan.Updates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime

private val sourceNames = mapOf(
    "outlook" to "Outlook", "gmail" to "Gmail", "email" to "Email", "teams" to "Teams", "onedrive" to "OneDrive",
    "drive" to "Drive", "whatsapp" to "WhatsApp", "sms" to "SMS", "calendar" to "Calendar",
)

internal fun sourceName(id: String) = sourceNames[id] ?: id.replaceFirstChar { it.uppercase() }

/** Counts sit on black while nothing needs the user; red for urgent, or at review time. */
private val Black = Color(0xFF111111)

@Composable
private fun CountBadge(count: Int, red: Boolean, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(
        modifier.defaultMinSize(if (big) 24.dp else 16.dp, if (big) 24.dp else 16.dp).clip(CircleShape)
            .background(
                when {
                    count == 0 -> MaterialTheme.colorScheme.outlineVariant
                    red -> kindColor(TaskKind.DEADLINE)
                    else -> Black
                },
            ).padding(horizontal = if (big) 6.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else Color.White, fontSize = if (big) 13.sp else 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** A minute ticker, so review time turns the bell red without any tap. */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = LocalDateTime.now() } }
    return now
}

/** The bell on the table: everything waiting, on black; red when urgent or at review time. */
@Composable
internal fun UpdatesBell(vm: MainViewModel) {
    val tasks by vm.updateTasks.collectAsStateWithLifecycle()
    val replies by vm.updateReplies.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    val total = tasks.size + replies.size
    val red = (tasks + replies).any { it.urgent } || Updates.reviewDue(board, now)
    Box {
        IconButton(onClick = { vm.updatesOpen.value = true }) { Icon(BellIcon, "Updates, $total waiting" + if (red) ", needs you now" else "") }
        if (total > 0) CountBadge(total, red, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
    }
}

private enum class Pile { MENU, REPLIES, TASKS }

/** The bell's sheet: two big buttons (Replies, Tasks), then each pile with where it came from and what is needed. */
@Composable
internal fun UpdatesSheet(vm: MainViewModel) {
    val open by vm.updatesOpen.collectAsStateWithLifecycle()
    if (!open) return
    val tasks by vm.updateTasks.collectAsStateWithLifecycle()
    val replies by vm.updateReplies.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var pile by remember { mutableStateOf(Pile.MENU) }
    var replying by remember { mutableStateOf<Update?>(null) }
    var away by remember { mutableStateOf<Pair<Update, String>?>(null) }
    val now = LocalDateTime.now()
    // Was it review time when the bell was opened? Opening it is the review.
    val dueSlot = remember {
        if (!Updates.reviewDue(board, now)) null
        else board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.filter { !it.isAfter(now.toLocalTime()) }.maxOrNull()
    }
    LaunchedEffect(Unit) { vm.markReviewed() }
    val close = { vm.updatesOpen.value = false }
    val next = board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.sorted()
        .let { times -> times.firstOrNull { it.isAfter(LocalTime.now()) } ?: times.firstOrNull() }
    val nextText = next?.let { "Next review at ${hm(it.hour, it.minute)}." }.orEmpty()

    replying?.let { u ->
        ReplyDialog(u, vm, onDone = { replying = null })
        return
    }
    away?.let { (u, p) ->
        PutAwayDialog(u, p, onChoice = { done -> vm.putAway(u, done, p); away = null }, onBack = { away = null })
        return
    }

    SoftDialog(
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (pile) { Pile.MENU -> "Updates"; Pile.REPLIES -> "Replies"; Pile.TASKS -> "Tasks" },
                    modifier = Modifier.weight(1f),
                )
                val last = board.checks.lastCheck?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                if (pile == Pile.MENU) last?.let { Text("checked ${hm(it.hour, it.minute)}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (pile) {
                    Pile.MENU -> {
                        dueSlot?.let {
                            Text(
                                "It's your ${hm(it.hour, it.minute)} review: ${tasks.size + replies.size} to look at.",
                                modifier = Modifier.fillMaxWidth().border(1.dp, kindColor(TaskKind.DEADLINE), RoundedCornerShape(10.dp)).padding(10.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            PileButton(EnvelopeIcon, Navy, "Replies", if (board.replies.on) replies.size else 0, replies.any { it.urgent }) { pile = Pile.REPLIES }
                            PileButton(NewTaskIcon, Slate, "Tasks", tasks.size, tasks.any { it.urgent }) { pile = Pile.TASKS }
                        }
                        Text(
                            "✉ Emails, meeting answers and messages ready for you to send.\n☐ Changes to your table from what arrived.\nRed means urgent. $nextText",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Pile.REPLIES -> {
                        if (!board.replies.on) {
                            Text("I can prepare answers to direct questions and requests in your emails and messages, so you only review and send.")
                            Text("🔒 ${ReplyWriter.RULE_1}", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { close(); vm.repliesWizard.value = 1 }) { Text("Set it up") }
                        } else {
                            Text("🔒 Nothing leaves without your tap.", style = MaterialTheme.typography.bodySmall)
                            if (replies.isEmpty()) Text("Nothing to answer. $nextText", style = MaterialTheme.typography.bodyMedium)
                            replies.forEach { u ->
                                // One card per origin: the answer and, when the same message asks for work, its change to the table.
                                val change = u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && u.actions.isNotEmpty()
                                UpdateCard(
                                    u, detail = if (u.meeting.isNotBlank()) "📅 ${u.meeting}" else "→ ${u.summary}", conversation = true,
                                    effects = if (change) vm.describeUpdate(u) else emptyList(),
                                ) {
                                    Button(onClick = { replying = u }) { Text(if (u.meeting.isNotBlank()) "Answer" else "Reply") }
                                    if (change) OutlinedButton(enabled = vm.canApply(u), onClick = { vm.applyUpdate(u) }) { Text("Add to table") }
                                    TextButton(onClick = { away = u to "replies" }) { Text("Put away") }
                                }
                            }
                        }
                    }
                    Pile.TASKS -> {
                        if (tasks.isEmpty()) Text("Nothing to change in your plan. $nextText", style = MaterialTheme.typography.bodyMedium)
                        tasks.forEach { u ->
                            UpdateCard(u, detail = "→ ${u.summary}", effects = vm.describeUpdate(u)) {
                                Button(enabled = vm.canApply(u), onClick = { vm.applyUpdate(u) }) { Text("Apply") }
                                OutlinedButton(onClick = { close(); vm.discussUpdate(u) }) { Text("Discuss") }
                                TextButton(onClick = { away = u to "tasks" }) { Text("Put away") }
                            }
                        }
                        Text(
                            "Only updates that touch your projects, tasks, deadlines or meetings show here. The rest is ignored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = {
            if (pile == Pile.MENU) TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text("Check now") }
            else TextButton(onClick = { pile = Pile.MENU }) { Text("Back") }
        },
    )
}

private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

@Composable
private fun PileButton(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, label: String, count: Int, urgent: Boolean, onClick: () -> Unit) {
    Box {
        RoundAction(icon, "$label: $count", color, onClick = onClick, label = label)
        CountBadge(count, urgent, Modifier.align(Alignment.TopEnd), big = true)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(u: Update, detail: String, conversation: Boolean = false, effects: List<String> = emptyList(), buttons: @Composable () -> Unit) {
    val red = kindColor(TaskKind.DEADLINE)
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (u.urgent) red else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (u.urgent) Text("Urgent", color = red, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (u.cc) Text("CC", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Slate).padding(horizontal = 5.dp, vertical = 1.dp))
            Text(buildString { append(sourceName(u.source)); if (u.from.isNotBlank()) append(" · ").append(u.from) }, style = MaterialTheme.typography.labelMedium)
        }
        if (conversation && u.thread.isNotEmpty()) Conversation(u) else
            Text(u.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 4, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (conversation) waitingDays(u)?.let { Text("⏳ $it days without your reply", color = red, style = MaterialTheme.typography.bodySmall) }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        // What Apply does, with the day: seen before the tap, not after.
        effects.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
        // Wraps on a narrow phone: a card can carry Reply, Add to table and Put away.
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
    }
}

/**
 * Putting a card away: "Already done" (by the user or anyone) closes the whole card; "Not this time" closes only
 * this pile. Neither teaches the assistant to skip anything: a similar item next time is proposed again.
 */
@Composable
private fun PutAwayDialog(u: Update, pile: String, onChoice: (Boolean) -> Unit, onBack: () -> Unit) {
    SoftDialog(
        onDismissRequest = onBack,
        title = { Text("Why put it away?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(u.summary, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onChoice(true) }, modifier = Modifier.fillMaxWidth()) { Text("✓ Already done") }
                Text("By you or by someone else. The whole card closes.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onChoice(false) }, modifier = Modifier.fillMaxWidth()) { Text("Not this time") }
                Text(
                    "Only this one${if (pile == "replies") " needs no answer" else ""}. Next time something like it comes, the assistant proposes it again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

/** How long the person has waited since their first message after the user's last reply, from 2 days on. */
private fun waitingDays(u: Update): Long? {
    val lastMine = u.thread.indexOfLast { it.fromMe }
    val since = u.thread.drop(lastMine + 1).firstOrNull()?.time?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    return java.time.Duration.between(since, LocalDateTime.now()).toDays().takeIf { it >= 2 }
}

/** The conversation since the user's last reply: their own messages and older context in grey. */
@Composable
private fun Conversation(u: Update) {
    val lastMine = u.thread.indexOfLast { it.fromMe }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        u.thread.forEachIndexed { i, m ->
            val faded = m.fromMe || i < lastMine
            Row {
                Box(Modifier.padding(end = 8.dp).background(if (m.fromMe) Navy else MaterialTheme.colorScheme.outlineVariant).padding(horizontal = 1.5.dp, vertical = 14.dp))
                Column {
                    Text(
                        (if (m.fromMe) "You" else m.sender.ifBlank { u.from }) + " · " + m.time.replace('T', ' ').take(16),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(m.text, style = MaterialTheme.typography.bodySmall, color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** One answer: the assistant writes it, the user changes anything, then puts it in Outlook Drafts or copies it for Beeper. */
@Composable
internal fun ReplyDialog(u: Update, vm: MainViewModel, onDone: () -> Unit) {
    val meeting = u.meeting.isNotBlank()
    val email = meeting || u.source in Updates.EMAIL
    val work = remember { if (meeting) null else vm.workTitle(u) }
    val promised = remember { work?.let { vm.promiseFor(u) } }
    val promise = promised?.text
    val send = !email && u.chatId.isNotBlank() && vm.canSendMessages()
    var choice by remember { mutableStateOf(if (meeting) ReplyWriter.Choice.ACCEPT else if (work != null) ReplyWriter.Choice.LATER else null) }
    var slots by remember { mutableStateOf<List<String>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val later = choice == ReplyWriter.Choice.LATER
    LaunchedEffect(choice) { if (choice == ReplyWriter.Choice.OTHER_TIME && slots.isEmpty()) slots = runCatching { vm.meetingSlots() }.getOrDefault(emptyList()) }

    if (confirming) {
        // The last look before a message leaves: who, where, the exact words. Only this tap sends.
        SoftDialog(
            keepOpen = true,
            onDismissRequest = { confirming = false },
            title = { Text("Send to ${u.from}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On ${sourceName(u.source)}, through Beeper, exactly as written:", style = MaterialTheme.typography.bodySmall)
                    Text(text.trim(), modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(10.dp))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        val problem = vm.sendMessage(u, text.trim(), addWork = later)
                        busy = false
                        if (problem == null) { vm.updatesOpen.value = false; onDone() } else error = problem
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Back to edit") } },
        )
        return
    }

    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDone,
        title = { Text("Reply to ${u.from}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔒 ${ReplyWriter.RULE_1}", style = MaterialTheme.typography.bodySmall)
                if (u.thread.isNotEmpty()) Conversation(u)
                else Text(u.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (meeting) {
                    Text("📅 ${u.meeting}", style = MaterialTheme.typography.bodyMedium)
                    Tags(
                        listOf(ReplyWriter.Choice.ACCEPT to "Accept", ReplyWriter.Choice.DECLINE to "Decline", ReplyWriter.Choice.OTHER_TIME to "Another time"),
                        choice ?: ReplyWriter.Choice.ACCEPT, { choice = it },
                    )
                    if (choice == ReplyWriter.Choice.OTHER_TIME) {
                        Text("Times that fit your day: " + slots.joinToString("; ").ifBlank { "looking…" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (work != null) {
                    Tags(listOf(true to "I'll get back to you", false to "Answer now"), later, { choice = if (it) ReplyWriter.Choice.LATER else null })
                    if (later) {
                        Text(
                            "You promise ${promise ?: "soon"}. “$work” is in your table " +
                                (promised?.tableDay?.let(vm::dayName) ?: "when a cell frees up") + "." +
                                if (u.status == com.opslegal.tda.core.model.UpdateStatus.NEW) " Sending also adds that task." else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (promised?.late == true) {
                            Text(
                                "⚠ That is after their deadline. Move the task earlier, or promise a later date in the reply.",
                                color = kindColor(TaskKind.DEADLINE), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Button(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { text = vm.writeReply(u, choice, slots, text, promise) } catch (e: Exception) { error = e.message ?: "The reply could not be written. Try again." }
                        busy = false
                    }
                }) { Text(if (text.isBlank()) "Write the reply" else "Rewrite") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                CompactField(text, { text = it }, "The reply (yours to change)", Modifier.fillMaxWidth(), singleLine = false, minLines = 5)
                Text(
                    when {
                        email -> "Nothing is sent: it goes to your Outlook Drafts, and you send it from Outlook."
                        send -> "Nothing goes until you tap Send and confirm."
                        else -> "It is copied and Beeper opens: you paste and send it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                when {
                    send -> { error = null; confirming = true }
                    !email -> { vm.copyReplyAndOpen(u, text.trim()); onDone() }
                    else -> {
                        busy = true; error = null
                        scope.launch {
                            val problem = vm.saveReplyDraft(u, text.trim(), accepted = choice == ReplyWriter.Choice.ACCEPT && meeting, addWork = later)
                            busy = false
                            if (problem == null) { vm.updatesOpen.value = false; onDone() } else error = problem
                        }
                    }
                }
            }) { Text(when { email -> "Save to Outlook drafts"; send -> "Send…"; else -> "Copy and open Beeper" }) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
