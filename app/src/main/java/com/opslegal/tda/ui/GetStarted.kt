package com.opslegal.tda.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Pacing
import com.opslegal.tda.core.plan.Tags
import com.opslegal.tda.data.AppSettings
import com.opslegal.tda.data.BeeperMessages

/** The big navy button of Get started (and other first steps): professional, one thumb. */
@Composable
internal fun Cta(text: String, hint: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Navy, Color(0xFF33415E)))).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (hint.isNotBlank()) Text("   $hint", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Text("   →", color = Color.White, fontSize = 18.sp)
    }
}

/** One question per screen: advice, reassurance, one big button, Skip always there. */
private data class GsStep(
    val key: String, val pic: String, val title: String, val act: String, val advice: String, val assure: String,
    val yes: String, val no: String,
)

private val STEPS = listOf(
    GsStep("persona", "", "What do you want to call me?", "That's me",
        "Give me a name and a face. Your notifications come from me, like a text from a real assistant. People close to you can write to me too: a message with my name comes straight to me.",
        "Whatever my name, I never send anything without your tap.",
        "Your notifications come from your assistant, by name.", "Notifications come from “Assistant”."),
    GsStep("ai", "🔑", "Connect your AI", "Connect",
        "Your assistant thinks with your own Claude or ChatGPT account. About 5 minutes, once; I guide you step by step.",
        "Your key stays encrypted on this phone. Your words go straight to the AI, never through us.",
        "The assistant understands you, plans projects and drafts replies.", "Basic mode: you add tasks yourself; no drafts, no plans."),
    GsStep("messages", "💬", "Your messages", "Connect Beeper",
        "Beeper brings WhatsApp, Signal, SMS and more into one place. I spot what needs an answer or a task.",
        "I only read. A message leaves only when you read it and tap Send.",
        "Messages that need you come to the bell, with a reply ready to check.", "Messages stay outside: nothing is brought back from them."),
    GsStep("email", "✉️", "Your email", "Sign in with Microsoft",
        "Requests in your emails become tasks; answers wait as drafts for you to review.",
        "Drafts stay in your Drafts folder. I never send, accept or decline anything for you.",
        "Emails with a request come to the bell; drafts wait in Outlook.", "Emails stay outside: requests are not brought back."),
    GsStep("profile", "⚖️", "About you", "Keep these",
        "Tap what fits you, as many as you like. I use it to keep your week balanced.",
        "Nothing is fixed: change it anytime in Playbook.",
        "Your week is weighed for you: Ground, Build, Nourish.", "Neutral: everything counts the same."),
    GsStep("task", "☑️", "Your first task", "Add it",
        "Something you have to do this week. One cell is about 1.5 h.",
        "I put it in a free cell. You can move it anytime.",
        "Your table has started.", "Your table waits for its first task: tap the mic anytime."),
    GsStep("project", "📁", "Your first project", "Create it",
        "Something with several steps: a file, a renovation, a trip. Give it a deadline if it has one.",
        "I spread the steps up to the deadline with a buffer, and skip holidays.",
        "Its steps are paced on your table.", "No project yet: the round ▢ button creates one anytime."),
    GsStep("magic", "✨", "Let me do my magic", "Go",
        "I look at the last month of messages and emails and bring back what still waits: tasks to add, replies to prepare.",
        "I only propose. You decide each one, in the bell.",
        "What was waiting is in the bell, ready for you.", "Nothing brought back yet."),
)

private fun done(key: String, b: Board, s: AppSettings, ms: String?): Boolean = when (key) {
    "persona" -> b.persona.name.isNotBlank()
    "ai" -> s.hasApiKey
    "messages" -> s.messagesAccess || b.setup.messages
    "email" -> ms != null || b.setup.email
    "profile" -> b.tags.isNotEmpty() || b.about.bio.isNotBlank()
    "task" -> b.setup.task
    "project" -> b.setup.project
    else -> b.setup.magic
}

/** How many of the 8 are set (for the "5 of 7 set" on the Settings button). */
internal fun setupCount(b: Board, s: AppSettings, ms: String?) = STEPS.count { done(it.key, b, s, ms) }
internal fun firstUnset(b: Board, s: AppSettings, ms: String?) = STEPS.indexOfFirst { !done(it.key, b, s, ms) }.let { if (it < 0) STEPS.size else it }

/** Get started, shown above every tab while open. Reopen anytime from Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GetStarted(vm: MainViewModel) {
    val step by vm.getStarted.collectAsStateWithLifecycle()
    val i = step ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ms by vm.microsoftAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ai by remember { mutableStateOf(false) }
    val go = { n: Int? -> vm.getStarted.value = n }
    val beeperPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updateSettings { it.copy(messagesAccess = granted) }
        if (granted) { vm.setSetup { it.copy(messages = true) }; go(i + 1) }
    }
    if (ai) { AiWizard(vm, onDismiss = { ai = false; if (vm.hasAi()) go(i + 1) }); return }

    if (i >= STEPS.size) {
        val n = setupCount(board, settings, ms)
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.markWelcomed() }
        SoftDialog(
            onDismissRequest = { go(null) },
            title = { Text(if (n == STEPS.size) "Initial setup complete ✓" else "$n of ${STEPS.size} set") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (n == STEPS.size) Column(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Advanced setup", fontWeight = FontWeight.Bold)
                        Text("Your week's areas, routines, your calendar, the kinds of cells, replies, days off. Each one optional, each one explained. Start when you're ready.",
                            style = MaterialTheme.typography.bodySmall)
                        Cta("Start", "${advancedCount(board, settings)} of $advancedTotal set") { go(null); vm.advanced.value = firstAdvanced(board, settings) }
                    }
                    STEPS.forEachIndexed { k, s ->
                        val d = done(s.key, board, settings, ms)
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (d) "✓" else "○", Modifier.width(26.dp), color = if (d) projectBarColor() else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontWeight = FontWeight.SemiBold)
                                Text(if (d) s.yes else s.no, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!d) OutlinedButton(onClick = { go(k) }) { Text("Set it") }
                        }
                        HorizontalDivider()
                    }
                    Text("Come back anytime: Settings → ${if (n == STEPS.size) "Advanced setup" else "Get started"}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { go(null); if (board.setup.magic) vm.updatesOpen.value = true }) { Text(if (board.setup.magic) "See the bell" else "Done") }
            },
        )
        return
    }

    val s = STEPS[i]
    val isDone = done(s.key, board, settings, ms)
    var text by remember(i) { mutableStateOf("") }
    var deadline by remember(i) { mutableStateOf("") }
    var busy by remember(i) { mutableStateOf(false) }
    val beeper = remember { BeeperMessages(context) }
    var pName by remember(i) { mutableStateOf(board.persona.name.ifBlank { "Jimmy" }) }
    var pFace by remember(i) { mutableStateOf(board.persona.face) }
    var pPhoto by remember(i) { mutableStateOf(board.persona.photo) }
    val again = s.key == "profile" || s.key == "persona"
    SoftDialog(
        keepOpen = true,
        onDismissRequest = { go(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((i + 1f) / STEPS.size).height(6.dp).background(Navy))
                }
                Text("  ${i + 1} of ${STEPS.size}", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (s.pic.isNotEmpty()) Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(s.pic, fontSize = 34.sp) }
                Text(s.title + if (isDone) " ✓" else "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(s.advice, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                    Text("🔒  "); Text(s.assure, style = MaterialTheme.typography.bodySmall)
                }
                when (s.key) {
                    "persona" -> PersonaPicker(vm, pName, { pName = it }, pFace, pPhoto) { f, p -> pFace = f; pPhoto = p }
                    "messages" -> if (!beeper.installed) Text("Beeper isn't on this phone yet: the button opens it in Google Play. Come back here after signing in.", style = MaterialTheme.typography.bodySmall)
                    "profile" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tags.all(board).keys.forEach { t -> val on = t in board.tags; TagChip(on, { vm.toggleTag(t) }, label = { Text(if (on) "✓ $t" else t) }) }
                    }
                    "task" -> HelpField(text, { text = it }, "What do you have to do?", "E.g. Call the notary, prepare the board deck.")
                    "project" -> {
                        HelpField(text, { text = it }, "Project name", "E.g. Kitchen renovation, Smith file.")
                        DateField("Deadline (optional)", deadline, { deadline = it })
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Cta(if (isDone && !again) "Done ✓ · Next" else s.act) {
                    if (isDone && !again) return@Cta go(i + 1)
                    when (s.key) {
                        "persona" -> if (pName.isNotBlank()) { vm.setPersona(pName, pFace, pPhoto); go(i + 1) }
                        "ai" -> ai = true
                        "messages" -> if (beeper.installed) beeperPermission.launch(BeeperMessages.READ_PERMISSION)
                            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.beeper.android"))) }
                        "email" -> { vm.signInMicrosoft(); vm.setSetup { it.copy(email = true) }; go(i + 1) }
                        "profile" -> { vm.edit { b -> Tags.apply(if (b.gbn) b else com.opslegal.tda.core.plan.Gbn.turnOn(b)) }; go(i + 1) }
                        "task" -> if (text.isNotBlank()) { vm.addTask(BoardOps.NewTask(text.trim())); vm.setSetup { it.copy(task = true) }; go(i + 1) }
                        "project" -> if (text.isNotBlank()) {
                            val name = text.trim()
                            val steps = Pacing.fill(listOf("Gather what you need", "Do the main work", "Wrap it up").map { Pacing.Filled(it) }, name)
                            vm.saveProject(Project(name, deadline = deadline.ifBlank { null }, intention = "To get $name done."), null,
                                steps.map { BoardOps.EditedStep(null, it.title, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added) })
                            vm.setSetup { it.copy(project = true) }; go(i + 1)
                        }
                        else -> { busy = true; vm.doMagic(); go(i + 1) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { go(i + 1) }) { Text("Skip") } },
        dismissButton = { TextButton(onClick = { go(if (i == 0) null else i - 1) }) { Text(if (i == 0) "Close" else "Back") } },
    )
}

internal val setupTotal get() = STEPS.size

/** Advanced setup: after the first 8, when the user is ready. Each step optional, its benefit first. */
private data class AdvStep(val key: String, val pic: String, val title: String, val act: String, val advice: String, val benefit: String)

private val ADVANCED = listOf(
    AdvStep("week", "🗓️", "My week", "Next",
        "Work, personal life, a second job, a client you only handle on some days: each area gets its own days, and the words that point to it.",
        "Company A's work never lands on company B's day, and weekends stay free of work."),
    AdvStep("routines", "🔁", "My routines", "Add my routines",
        "Gym, school run, weekly review: tap the moment of the day they happen.",
        "I plan around them and check, on your weekly review, which ones held."),
    AdvStep("calendar", "📅", "My calendar", "Show my calendar",
        "Your events show under each day; you add the ones that take a cell.",
        "Nothing is planned over a meeting you already have."),
    AdvStep("types", "🎨", "The four kinds of cells", "Got it",
        "Blue: a task. Green: a step of a project. Black: a meeting or a call. Red: a delivery or a deadline due that day.",
        "One look at a day tells you what is fixed and what can move."),
    AdvStep("replies", "✍️", "Replies ready to send", "Set it up",
        "I prepare answers to emails and messages, in your own style, for you to read and send.",
        "An answer in one tap instead of ten minutes. Nothing ever leaves without your tap."),
    AdvStep("off", "🏖️", "Holidays and days off", "Next",
        "Your region's public holidays, and your own vacation days on a 12-month calendar.",
        "Deadlines are planned around them, never on them."),
)
internal val advancedTotal get() = ADVANCED.size

private fun advDone(key: String, b: Board, s: AppSettings): Boolean = key in b.setup.advanced || when (key) {
    "week" -> b.settings.areas.isNotEmpty()
    "routines" -> b.routines.isNotEmpty()
    "calendar" -> s.calendarAccess
    "replies" -> b.replies.on
    "off" -> b.settings.daysOff.isNotEmpty()
    else -> false
}
internal fun advancedCount(b: Board, s: AppSettings) = ADVANCED.count { advDone(it.key, b, s) }
internal fun firstAdvanced(b: Board, s: AppSettings) = ADVANCED.indexOfFirst { !advDone(it.key, b, s) }.coerceAtLeast(0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedSetup(vm: MainViewModel) {
    val step by vm.advanced.collectAsStateWithLifecycle()
    val i = step ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val go = { n: Int? -> vm.advanced.value = n?.takeIf { it < ADVANCED.size } }
    val a = ADVANCED.getOrNull(i) ?: return go(null)
    val isDone = advDone(a.key, board, settings)
    var daysOff by remember(i) { mutableStateOf(false) }
    val mark = { vm.setSetup { it.copy(advanced = (it.advanced + a.key).distinct()) } }
    if (daysOff) {
        DaysOffDialog(board.settings.daysOff, board.settings.holidays,
            onSave = { list -> vm.editAndPlan { b -> b.copy(settings = b.settings.copy(daysOff = list)) }; daysOff = false },
            onDismiss = { daysOff = false })
        return
    }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = { go(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((i + 1f) / ADVANCED.size).height(6.dp).background(Navy))
                }
                Text("  Advanced · ${i + 1} of ${ADVANCED.size}", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(a.pic, fontSize = 34.sp) }
                Text(a.title + if (isDone) " ✓" else "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(a.advice, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                    Text("✨  "); Text("Why: ${a.benefit}", style = MaterialTheme.typography.bodySmall)
                }
                // Two steps are done right here; the others open their place.
                when (a.key) {
                    "week" -> WeekGrid(vm, board, settings.dayLanguage == "fr")
                    "types" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(TaskKind.TASK to "Task", null to "Project step", TaskKind.MEETING to "Meeting", TaskKind.DEADLINE to "Deadline").forEach { (k, l) ->
                            Text(l, color = if (k == null) projectBarColor() else kindColor(k), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    "off" -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            com.opslegal.tda.core.plan.Holidays.regions.forEach { (k, l) ->
                                TagChip(board.settings.holidays == k, { vm.editAndPlan { b -> b.copy(settings = b.settings.copy(holidays = k)) } }, label = { Text(l) })
                            }
                        }
                        OutlinedButton(onClick = { daysOff = true }) { Text("📅 My days off" + board.settings.daysOff.size.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()) }
                    }
                }
                Cta(if (isDone && a.key != "week" && a.key != "off") "Done ✓ · Next" else a.act) {
                    mark()
                    when {
                        isDone || a.key in setOf("week", "off", "types") -> go(i + 1)
                        a.key == "routines" -> { go(null); vm.goTo.value = "playbook" to "My routines" }
                        a.key == "calendar" -> { go(null); vm.goTo.value = "settings" to "What the assistant sees" }
                        a.key == "replies" -> { go(null); vm.repliesWizard.value = 1 }
                    }
                }
                Text("Optional. Skip it now; it stays in Settings → Advanced setup.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = { go(i + 1) }) { Text("Skip") } },
        dismissButton = { TextButton(onClick = { go(if (i == 0) null else i - 1) }) { Text(if (i == 0) "Close" else "Back") } },
    )
}
