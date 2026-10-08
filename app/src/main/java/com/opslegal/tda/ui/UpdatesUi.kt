@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.material3.HorizontalDivider
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
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    // Only what arrived and needs an action counts; the assistant's own advice never does.
    val total = inbox.size
    val red = inbox.any { it.urgent } || Updates.reviewDue(board, now)
    Box {
        IconButton(onClick = { vm.updatesOpen.value = true }) { Icon(BellIcon, "Updates, $total waiting" + if (red) ", needs you now" else "") }
        if (total > 0) CountBadge(total, red, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
    }
}

/**
 * The bell's one panel: what arrived (emails, messages, meeting requests), each shown once with its actions, then the
 * assistant's own advice. Reply · Add a task · Add to calendar · Discuss · Dismiss (done, not relevant or noted, with
 * an optional comment that goes to the project's history).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun UpdatesSheet(vm: MainViewModel, onAddTask: (Update, Boolean) -> Unit = { _, _ -> }) {
    val open by vm.updatesOpen.collectAsStateWithLifecycle()
    if (!open) return
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val flags by vm.flags.collectAsStateWithLifecycle()
    var replying by remember { mutableStateOf<Update?>(null) }
    // Back from "Add to tasks & reply": straight to the reply.
    LaunchedEffect(Unit) { vm.replyNext.value?.let { id -> replying = board.updates.firstOrNull { it.id == id }; vm.replyNext.value = null } }
    var dismissing by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<String?>(null) }
    val now = LocalDateTime.now()
    val dueSlot = remember {
        if (!Updates.reviewDue(board, now)) null
        else board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.filter { !it.isAfter(now.toLocalTime()) }.maxOrNull()
    }
    LaunchedEffect(Unit) { vm.markReviewed(); vm.settleAnswered() }
    val close = { vm.updatesOpen.value = false }
    val next = board.checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.sorted()
        .let { times -> times.firstOrNull { it.isAfter(LocalTime.now()) } ?: times.firstOrNull() }
    val nextText = next?.let { "Next review at ${hm(it.hour, it.minute)}." }.orEmpty()
    val red = kindColor(TaskKind.DEADLINE)

    replying?.let { u ->
        ReplyDialog(u, vm, onDone = { replying = null })
        return
    }

    SoftDialog(
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updates", modifier = Modifier.weight(1f))
                val last = board.checks.lastCheck?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                last?.let { Text("checked ${hm(it.hour, it.minute)}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                dueSlot?.let {
                    Text("It's your ${hm(it.hour, it.minute)} review: ${inbox.size} to look at.",
                        modifier = Modifier.fillMaxWidth().border(1.dp, red, RoundedCornerShape(10.dp)).padding(10.dp))
                }
                if (inbox.isEmpty()) Text("Nothing waits on you. $nextText", style = MaterialTheme.typography.bodyMedium)
                inbox.forEach { u ->
                    val needsAnswer = u.needsReply && !u.replied
                    val meeting = u.meeting.isNotBlank()
                    val change = u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && u.actions.isNotEmpty()
                    val onlyAdds = u.actions.all { it.type == "add" }
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, if (u.urgent) red else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (meeting) "📅" else if (u.source in Updates.EMAIL) "✉" else "💬", Modifier.padding(end = 8.dp))
                            Text(u.title.ifBlank { u.summary.split(" ").take(6).joinToString(" ") }, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            waitingDays(u)?.let { Text("$it d", style = MaterialTheme.typography.labelSmall, color = if (it >= 3) red else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(listOfNotNull("Urgent".takeIf { u.urgent }, "CC".takeIf { u.cc }, sourceName(u.source), u.from.ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = if (u.urgent) red else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (meeting) "📅 ${u.meeting}" else u.summary, style = MaterialTheme.typography.bodySmall)
                        if (change) vm.describeUpdate(u).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                        if (dismissing == u.id) DismissChoices(u, board, onCancel = { dismissing = null }) { how, comment, project ->
                            vm.dismissItem(u, how, comment, project); dismissing = null
                        } else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Reply is always there: to say when it will be done, to act, or to ask what is missing.
                            if (!u.replied || needsAnswer || meeting) Button(onClick = { replying = u }) { Text(if (meeting) "Answer" else "Reply") }
                            if (meeting && change) OutlinedButton(enabled = vm.canApply(u), onClick = { vm.applyUpdate(u) }) { Text("Add to calendar") }
                            else if (change && !onlyAdds) Button(enabled = vm.canApply(u), onClick = { vm.applyUpdate(u) }) { Text(if (u.actions.any { it.type == "change" || it.type == "move" }) "Update the cell" else "Apply") }
                            if (!meeting && u.actions.none { it.type == "change" || it.type == "move" }) {
                                OutlinedButton(onClick = { close(); onAddTask(u, false) }) { Text("Add to tasks") }
                                if (!u.replied) OutlinedButton(onClick = { close(); onAddTask(u, true) }) { Text("Add to tasks & reply") }
                            }
                            OutlinedButton(onClick = { close(); vm.discussUpdate(u) }) { Text("Discuss") }
                            TextButton(onClick = { dismissing = u.id }) { Text("Dismiss") }
                        }
                    }
                }
                if (flags.isNotEmpty()) {
                    // The assistant's own advice: same rows, never counted on the bell.
                    Text("From your assistant", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    flags.forEach { f ->
                        Column(
                            Modifier.fillMaxWidth().border(1.dp, if (f.red) red else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.icon, Modifier.padding(end = 8.dp))
                                Text(f.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = if (f.red) red else MaterialTheme.colorScheme.onSurface)
                            }
                            Text(f.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (asking == f.id) com.opslegal.tda.core.plan.Flags.notNowReasons.forEach { why ->
                                    OutlinedButton(onClick = { vm.flagNotNow(f.id, f.title, why); asking = null }) { Text(why) }
                                } else f.actions.filter { !it.first.startsWith("popen:") }.forEach { (key, label) ->
                                    OutlinedButton(onClick = {
                                        if (key.startsWith("notnow:")) asking = f.id
                                        else if (!vm.flagAction(key, f.title)) close()
                                    }) { Text(label) }
                                }
                            }
                        }
                    }
                }
                Text("🔒 Nothing leaves without your tap. Red means urgent. $nextText", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text("Check now") } },
    )
}

/** Dismiss: why, in one tap, and an optional comment; done and noted keep it in the project's history. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DismissChoices(u: Update, board: com.opslegal.tda.core.model.Board, onCancel: () -> Unit, onDismiss: (String, String, String?) -> Unit) {
    var how by remember { mutableStateOf("done") }
    var comment by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(u.project.ifBlank { null }) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("done" to "✓ Already done", "noted" to "Noted: worth knowing", "irrelevant" to "Not relevant").forEach { (k, l) ->
                TagChip(how == k, { how = k }, label = { Text(l) })
            }
        }
        if (how != "irrelevant") {
            if (board.projects.isNotEmpty()) {
                Text("Keep it in the project's history:", style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    board.projects.filter { !com.opslegal.tda.core.plan.Projects.isIdea(board, it) }.take(8).forEach { p ->
                        TagChip(project == p.name, { project = if (project == p.name) null else p.name }, label = { Text(p.name, maxLines = 1) })
                    }
                }
            }
            CompactField(comment, { comment = it }, "Why, or what to remember (optional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onDismiss(how, comment, project) }) { Text("Dismiss") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
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
    // Already thought through: the reply is written from the facts as soon as it opens (the promise, the task's day).
    LaunchedEffect(Unit) {
        if (text.isBlank() && vm.hasAi()) {
            busy = true
            text = runCatching { vm.writeReply(u, choice, slots, "", promise) }.getOrDefault("")
            busy = false
        }
    }

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
private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
