package com.opslegal.tda.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.plan.Feed
import com.opslegal.tda.core.plan.Feed.Card
import com.opslegal.tda.core.plan.Planner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val GREEN = Color(0xFF2E9E5B)
private val NAVY = Color(0xFF1F2A44)

/** One way to swipe: its label and what it does. */
private class Way(val label: String, val run: () -> Unit)
/** What a card shows: kicker, title, its body, and the three ways (right acts now, up the other good way, left not now). */
private class View(val kicker: String, val title: String, val right: Way?, val left: Way?, val up: Way? = null, val body: @Composable () -> Unit = {})
/** A small thing being done here: a timer runs; ✓ Done, or it can't be finished now. */
private class Doing(val title: String, val done: () -> Unit, val later: () -> Unit)
/** Writing in the card: the main idea, the draft, busy while the assistant writes. */
private class Work { var idea by mutableStateOf(""); var draft by mutableStateOf<String?>(null); var busy by mutableStateOf(false); var also by mutableStateOf(false) }

/**
 * 🎲 The feed: one card at a time, full screen. Swipe right = act now (reply, call, write it, do it with a timer, or
 * plan it at its best moment), up = the other good way, left = not now. Reality first; the cards for the person
 * (people, a value, a method, a mindful minute, a dream, remember) only once real things are done. It always ends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedScreen(vm: MainViewModel) {
    val open by vm.feedOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    val deck = remember { mutableStateListOf<Card>().apply { addAll(Feed.deck(board, inbox, today)) } }
    var i by remember { mutableIntStateOf(0) }
    var cleared by remember { mutableIntStateOf(0) }
    val start = remember { System.currentTimeMillis() }
    var doing by remember { mutableStateOf<Doing?>(null) }
    var called by remember { mutableStateOf<String?>(null) }
    var points by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    val works = remember { mutableStateMapOf<String, Work>() }
    val seen = remember { mutableStateMapOf<Int, Boolean>() }
    var energy by remember { mutableStateOf("") }
    var need by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }
    var dreamSel by remember { mutableStateOf("") }
    var dreamText by remember { mutableStateOf("") }
    val round = remember { (System.currentTimeMillis() / 60000 % 97).toInt() }
    val close = { vm.feedOpen.value = false }
    fun work(id: String) = works.getOrPut(id) { Work() }
    fun next(done: Boolean = false) { if (done) { cleared++; vm.feedCount(cleared = 1) }; i = (i + 1).coerceAtMost(deck.lastIndex); doing = null; called = null; points = ""; after = "" }
    fun acted() = vm.feedCount(acted = 1)
    fun draft(id: String, kind: String, from: String, text: String, title: String) {
        val w = work(id); if (w.idea.isBlank()) { vm.notify("Your main idea first: a few words."); return }
        w.busy = true
        scope.launch { w.draft = vm.feedDraft(kind, from, text, title, w.idea); w.busy = false }
    }
    val card = deck.getOrNull(i) ?: Card.End
    val inspiring = card is Card.Reach || card == Card.Thank || card is Card.Weak || card is Card.Method || card == Card.Breath || card is Card.DreamQ || card == Card.Dream
    LaunchedEffect(i) { if (inspiring && seen[i] != true) { seen[i] = true; vm.feedCount(inspired = 1) } }

    @Composable fun Panel(id: String, label: String) {
        val w = work(id)
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(w.idea, { w.idea = it }, Modifier.weight(1f), placeholder = { Text("Your main idea, in a few words", fontSize = 13.sp) }, maxLines = 3)
            DictateButton { said -> w.idea = listOf(w.idea.trim(), said.trim()).filter { it.isNotBlank() }.joinToString(" ") }
        }
        if (w.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        w.draft?.let { d -> OutlinedTextField(d, { w.draft = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Yours to change") }) }
    }

    fun callView(title: String, sub: String, done: () -> Unit, number: () -> Unit): View =
        if (called == title) View("📞 After the call", "What came out of it?",
            Way("✓ Done") { after.split('\n').map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { vm.addQuick(it, null) }; done(); acted(); next(true) },
            Way("Nothing to add") { done(); next(true) }) {
            if (points.isNotBlank()) Text("📝 $points", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GREEN.copy(alpha = 0.10f)).padding(10.dp))
            OutlinedTextField(after, { after = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp), placeholder = { Text("One line per thing to do, e.g. Send the 2 documents by Friday", fontSize = 13.sp) })
        } else View("📞 Call", title, Way("📞 Call now") { number(); called = title }, Way("Not now") { next() },
            Way("📅 Plan it · today") { vm.addQuick(listOf("📞 ${title.removePrefix("📞 ")}"), today, impact = 3); done(); next(true) }) {
            if (sub.isNotBlank()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("📝 What to say (optional)", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(points, { points = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp), placeholder = { Text("Your points, e.g. confirm Thursday, ask about the deposit", fontSize = 13.sp) })
        }

    val v: View = doing?.let { g ->
        View("▶ Doing it now", g.title, Way("✓ Done") { g.done(); acted(); next(true) }, Way("Can't finish now") { g.later(); next(true) }) {
            var secs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(g) { while (true) { delay(1000); secs++ } }
            Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}", fontSize = 54.sp, fontWeight = FontWeight.Bold, color = GREEN)
                Text("Just this. Tap ✓ when it's done.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } ?: when (val c = card) {
        is Card.Note -> {
            val u = c.u
            val add = u.actions.firstOrNull { it.type == "add" }?.title?.takeIf { u.status == com.opslegal.tda.core.model.UpdateStatus.NEW }
            val w = work(u.id)
            val canReply = !u.replied && u.from.isNotBlank()
            val plan = Way("📅 Plan it") { vm.nextAction(u, u.title.ifBlank { u.summary.take(60) }, "task", "", null, ""); next(true) }
            if (canReply) View("✋ ${u.source.replaceFirstChar { it.uppercase() }} · ${u.from}", u.title.ifBlank { u.summary.take(80) },
                if (w.draft != null) Way("✓ ${if (u.source in com.opslegal.tda.core.plan.Updates.EMAIL && u.mailId.isNotBlank()) "Save to Outlook drafts" else "Copy & done"}") {
                    val t = w.draft!!.trim(); if (t.isBlank()) vm.notify("The reply is empty.") else { vm.feedReply(u, t, w.also); acted(); next(true) }
                } else Way("✨ Write it") { draft(u.id, "reply", u.from, u.thread.joinToString("\n") { it.text }.ifBlank { u.text }, "") },
                Way("Not now") { next() }, plan) {
                Text(u.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Panel(u.id, "↩ Reply: your main idea")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (add != null) PickChip(w.also, "Also do: “$add”") { w.also = !w.also }
                    TagChip(false, { vm.dismissItem(u, "done", "", null); next(true) }, label = { Text("Already handled", fontSize = 12.sp) })
                }
            } else View("✋ ${u.from}", u.title.ifBlank { u.summary.take(80) },
                if (add != null) Way("✓ Do as proposed") { vm.applyUpdate(u); next(true) } else Way("✓ Done") { vm.dismissItem(u, "done", "", null); next(true) },
                Way("Not now") { next() }, plan) { Text(u.summary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        is Card.Call -> callView("Call back ${c.c.who}", if (c.c.count > 1) "${c.c.count} missed calls" else "Missed call", { vm.dropCall(c.c.id) }) { vm.callNow(c.c.id) }
        is Card.Quick -> {
            val q = c.q; val kind = Feed.workKind(q.title)
            val done: () -> Unit = { vm.tickQuickThing(q.id) }
            val left = Way("Not now") { if (!c.mind) vm.swapQuick(q.id); next() }
            val up = if (c.mind) Way("📅 Plan it") { vm.quickOnDay(q.id, if (com.opslegal.tda.core.plan.Quick.onDay(board, today, today).count { !it.done } < board.settings.quickPerDay) today else today.plusDays(1)); next(true) }
                else Way("✓ Already done") { done(); next(true) }
            val kicker = if (c.mind) "✏️ On your mind" else "⚡ On today's line"
            when (kind) {
                "call" -> callView(q.title, "", done) { runCatching { vm.dialNumber("") } }.let { cv -> if (called == q.title) cv else View(kicker, cv.title, cv.right, cv.left, up, cv.body) }
                "msg", "doc" -> { val w = work(q.id)
                    View(kicker, q.title, if (w.draft != null) Way(if (kind == "doc") "✓ Keep it & done" else "✓ Copy & done") { vm.copyText(w.draft!!); done(); acted(); vm.notify(if (kind == "doc") "📄 Copied. Done." else "✉️ Copied: paste it where it goes. Done."); next(true) }
                        else Way("✨ Write it") { draft(q.id, kind, "", "", q.title) }, left, up) { Panel(q.id, if (kind == "doc") "📄 The document: your main idea" else "✉️ The message: your main idea") } }
                else -> View(kicker, q.title, Way("▶ Do it now") { doing = Doing(q.title, { done() }, { vm.quickOnDay(q.id, today.plusDays(1)) }) }, left, up) {
                    Text((if (q.due.isNotBlank()) "Due ${vm.dayName(LocalDate.parse(q.due))}" else "No deadline") + " · about 15 minutes or less", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is Card.Cell -> {
            val t = c.task; val st = c.step; val title = Planner.cellTitle(t, st); val kind = Feed.workKind(title)
            val heavy = t.effortOf(st) == com.opslegal.tda.core.model.Effort.HEAVY
            val planIt = Way("📅 Plan it properly") { vm.planStepBest(st.id); next(true) }
            when {
                !heavy && kind == "call" -> callView(title, "", { vm.setDone(st.id, true) }) { vm.dialNumber("") }
                !heavy && (kind == "msg" || kind == "doc") -> { val w = work(st.id)
                    View("📋 In today's table", title, if (w.draft != null) Way("✓ Keep it & done") { vm.keepDraft(st.id, w.draft!!); acted(); vm.notify("📄 Kept with the task and copied. Done."); next(true) }
                        else Way("✨ Write it") { draft(st.id, kind, "", "", title) }, Way("Not now") { next() }, planIt) { Panel(st.id, if (kind == "doc") "📄 The document: your main idea" else "✉️ The message: your main idea") } }
                else -> View("📋 In today's table", title, Way("▶ Start: 25 minutes") { doing = Doing("25 minutes: $title", {}, {}) }, Way("Not now") { next() }, planIt) {
                    Text((if (heavy) "A big one." else "More than a quick thing.") + " Start with its first 25 minutes, or give it its best moment.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is Card.Parked -> View("⏸ Parked", c.p.name, Way("▶ Resume") { vm.resumeProject(c.p.name); next(true) }, Way("Not now") { next() }, Way("1️⃣ One small step") { vm.unparkOne(c.p.name); next(true) }) {
            Text((c.p.pause?.why ?: "Paused") + " · since ${c.p.pause?.since ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card.Gate -> {
            val n = cleared + Feed.doneToday(board, today)
            if (n < Feed.EARN) { LaunchedEffect(i) { i = deck.lastIndex }; View("", "", null, null) }
            else View("✅ Reality handled", "$cleared done this round. Now for you.", Way("Continue") {
                deck.addAll(i + 1, Feed.deep(board, today, round, energy == "tired" || need in setOf("rest", "calm", "connect"))); next()
            }, Way("Stop here") { i = deck.lastIndex }) {
                Text("A few cards for you: people, one value, one method, a dream. Quick, then it ends.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("How are you right now?", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("tired" to "😴 Tired", "ok" to "🙂 OK", "full" to "⚡ Full").forEach { (k, l) -> PickChip(energy == k, l) { energy = k } } }
                Text("What would help", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("rest" to "Rest", "move" to "Move", "connect" to "Connect", "fun" to "Fun", "calm" to "Calm").forEach { (k, l) -> PickChip(need == k, l) { need = k } } }
            }
        }
        is Card.Reach -> { val msg = "Hi ${c.name.split(' ').first()}! I was thinking of you. How are you doing? 🙂"
            View("💌 Connected", "It's been a while with ${c.name}.", Way("💌 Send it…") { vm.copyText(msg); vm.openMessaging(); acted(); next(true) }, Way("Not now") { next() }) {
                Text("A one-line hello keeps people close. Prepared for you; it's copied and your messages open: you send it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(msg, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GREEN.copy(alpha = 0.10f)).padding(10.dp))
            } }
        Card.Thank -> { val names = (board.checks.keyContacts + inbox.take(6).map { it.from.substringBefore('(').substringBefore('<').trim() }).filter { it.isNotBlank() }.distinct().take(5)
            val msg = "Thank you ${who.split(' ').first()} for your help this week. It really made a difference 🙏"
            View("🙏 Connected", "Who helped you this week?", Way("🙏 Send it…") { if (who.isBlank()) vm.notify("Pick someone first.") else { vm.copyText(msg); vm.openMessaging(); acted(); who = ""; next(true) } }, Way("Not now") { who = ""; next() }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { names.forEach { n -> PickChip(who == n, n) { who = n } } }
                if (who.isNotBlank()) Text(msg, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GREEN.copy(alpha = 0.10f)).padding(10.dp))
            } }
        is Card.Weak -> { val steps = Feed.steps(c.value)
            val (q, chips) = when (c.q) {
                0 -> "${c.value} is at ${c.pct}% this week. Its smallest step today?" to steps.map { s -> s to { vm.addQuick(listOf("${s.substringAfter(' ')} · ${c.value}"), today, impact = 3) } }
                1 -> "What gets in the way of ${c.value}?" to listOf("⏱ No time" to { vm.addQuick(listOf("15 minutes for ${c.value}"), today.plusDays(1), impact = 3) }, "😴 Tired" to { vm.addQuick(listOf("5 minutes for ${c.value} (tiny version)"), today) },
                    "🔁 I forget" to { vm.addFeedRoutine("${c.value}: 10 minutes", Moment.MORNING, mapOf(c.value to 2)) }, "🗺 No plan" to { vm.addTask(com.opslegal.tda.core.plan.BoardOps.NewTask("First step for ${c.value}")) })
                else -> "After I ___, I will ___ for ${c.value}." to listOf("my morning coffee" to Moment.MORNING, "lunch" to Moment.MIDDAY, "getting home" to Moment.EVENING).map { (a, m) ->
                    "After $a: ${steps.first().substringAfter(' ')}" to { vm.addFeedRoutine("${steps.first().substringAfter(' ')}, after $a", m, mapOf(c.value to 2)) } }
            }
            View("🌱 ${c.bucket.ifBlank { "Ground" }.replaceFirstChar { it.uppercase() }} · your lowest this week", q, null, Way("Not now") { next() }) {
                chips.forEach { (l, run) -> ChoiceRow(l) { run(); acted(); vm.notify("✓ In your plan."); next(true) } }
            } }
        is Card.Method -> { val m = c.m; val top = Feed.top(board, today)
            val tries: List<Pair<String, () -> Unit>> = when (m.tryIt) {
                "two" -> listOf("⚡ Do one 2-minute thing now" to { (com.opslegal.tda.core.plan.Quick.pool(board).firstOrNull())?.let { q -> doing = Doing(q.title, { vm.tickQuickThing(q.id) }, {}) } ?: vm.notify("Nothing that small right now.") })
                "frog", "box", "draft" -> top.take(if (m.tryIt == "frog") 3 else 2).map { (t, s) -> val ti = Planner.cellTitle(t, s)
                    when (m.tryIt) { "frog" -> "🐸 $ti first tomorrow" to { vm.frogTomorrow(s.id); vm.notify("🐸 Tomorrow's first cell.") }
                        "box" -> "⏱ 25 minutes on “$ti” now" to { doing = Doing("25 minutes: $ti", { vm.setDone(s.id, true) }, {}) }
                        else -> "✏️ 15 ugly minutes on “$ti”" to { doing = Doing("15 minutes, imperfect: $ti", {}, {}) } } }
                "stack" -> listOf("my morning coffee" to Moment.MORNING, "lunch" to Moment.MIDDAY, "getting home" to Moment.EVENING).map { (a, mo) -> "After $a: one tiny habit" to { vm.addFeedRoutine("Tiny habit, after $a", mo); vm.notify("🔁 A tiny routine, set.") } }
                else -> listOf("🗓 A 15-minute review, Sunday" to { vm.addQuick(listOf("🗓 Weekly review (15 minutes)"), today.with(java.time.DayOfWeek.SUNDAY).let { if (it.isBefore(today)) it.plusWeeks(1) else it }, impact = 3); vm.notify("🗓 Sunday's ⚡ line.") })
            }
            View("🧠 A method that works", "“${m.quote}”", null, Way("Next") { next() }) {
                Text("— ${m.by}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                    Text("The method", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(m.method, fontSize = 15.sp)
                }
                if (tries.isNotEmpty()) Text("Try it now", fontWeight = FontWeight.SemiBold)
                tries.forEach { (l, run) -> ChoiceRow(l) { val before = doing; run(); acted(); if (doing == null || doing === before) next(true) } }
            } }
        Card.Breath -> View("🧘 A mindful minute", "Breathe with the circle.", Way("✓ Done") { acted(); next(true) }, Way("Skip") { next() }) {
            val s = remember { Animatable(1f) }
            LaunchedEffect(Unit) { while (true) { s.animateTo(2.4f, androidx.compose.animation.core.tween(4000)); s.animateTo(1f, androidx.compose.animation.core.tween(4000)) } }
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(64.dp).scale(s.value).clip(CircleShape).background(GREEN.copy(alpha = 0.8f))) }
            Text("In as it grows, out as it shrinks. One minute resets a scattered mind.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is Card.DreamQ -> View("✨ Your dreams", c.q, Way("✨ Keep it") { if (dreamText.isBlank()) vm.notify("A few words first.") else { vm.addDream(dreamText, c.q); dreamText = ""; acted(); next(true) } }, Way("Not now") { next() }) {
            OutlinedTextField(dreamText, { dreamText = it }, Modifier.fillMaxWidth(), placeholder = { Text("A few words, e.g. play the piano") })
            Text("It becomes a dream ✨ in your ideas, with a first small step.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card.Dream -> { val dreams = board.projects.filter { it.dream }.map { it.name }
            View("✨ A dream, one step", "Which dream have you postponed the longest?", null, Way("Not now") { dreamSel = ""; next() }) {
                Text("Small steps are how dreams move: a 10-minute step today beats a perfect plan someday.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { dreams.take(5).forEach { n -> PickChip(dreamSel == n, "✨ $n") { dreamSel = n } } }
                if (dreamSel.isNotBlank()) listOf("⏱ 10 minutes on it today" to "10 minutes on it", "📞 Call someone about it" to "call someone about it", "🗓 Book or plan it" to "book or plan it").forEach { (l, what) ->
                    ChoiceRow(l) { vm.addQuick(listOf("✨ $dreamSel: $what"), today, impact = 3); acted(); dreamSel = ""; vm.notify("✨ On today's ⚡ line."); next(true) } }
            } }
        is Card.Remember -> View("🏆 Remember this?", c.text, Way("❤️ Keep it") { vm.keepProud(c.text); next(true) }, Way("Next") { next() }) {
            Text("Achievements deserve a moment, not just a tick. Keep it in your Proud of jar for a harder day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> { val st = Feed.stats(board, today); val min = ((System.currentTimeMillis() - start) / 60000).coerceAtLeast(1); val n = cleared + Feed.doneToday(board, today)
            View("🎉 All clear", "$cleared done in $min minute${if (min > 1) "s" else ""}.", Way("Done for now") { close() }, Way("🎲 One more") {
                deck.clear(); deck.addAll(Feed.deck(vm.board.value, vm.inbox.value, today)); i = 0; cleared = 0 }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("${st.cleared}" to "done this week", "${st.inspired}" to "inspired", "${st.acted}" to "turned into action").forEach { (b, l) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(b, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(l, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
                Text(if (n < Feed.EARN) "Do ${Feed.EARN - n} more thing${if (Feed.EARN - n > 1) "s" else ""} today, and the cards for you open: people, a method, a dream." else "You're allowed to stop 🌿 Go live.",
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$cleared done · ${(deck.lastIndex - i).coerceAtLeast(0)} to go", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("✕", fontSize = 18.sp) }
                }
                LinearProgressIndicator(progress = { if (deck.size > 1) i.toFloat() / deck.lastIndex else 1f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = GREEN)
                // The card: drag it; past a threshold it goes that way.
                val dx = remember(i, doing, called) { Animatable(0f) }
                val dy = remember(i, doing, called) { Animatable(0f) }
                fun go(way: Way?) { way?.run?.invoke() }
                // The latest card's ways, read when the finger lifts (the gesture itself isn't restarted while dragging).
                val current by androidx.compose.runtime.rememberUpdatedState(v)
                Column(Modifier.weight(1f).fillMaxWidth()
                    .graphicsLayer { translationX = dx.value; translationY = minOf(0f, dy.value); rotationZ = dx.value / 40f }
                    .clip(RoundedCornerShape(22.dp)).background(if (doing != null) GREEN.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                    .pointerInput(i, doing, called) {
                        // Left and right swipe; up/down stays the card's scroll (the middle button is the "up" way).
                        detectHorizontalDragGestures(onDragEnd = {
                            val x = dx.value
                            scope.launch { dx.snapTo(0f) }
                            when { x > 220 -> go(current.right); x < -220 -> go(current.left) }
                        }) { ch, d -> ch.consume(); scope.launch { dx.snapTo(dx.value + d) } }
                    }
                    .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (v.kicker.isNotBlank()) Text(v.kicker, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (v.title.isNotBlank()) Text(v.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
                    v.body()
                    val hint = when { dx.value > 80 -> v.right?.label; dx.value < -80 -> v.left?.label; dy.value < -100 -> v.up?.label; else -> null }
                    if (hint != null) Text(hint, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (dx.value > 80) GREEN else NAVY).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    v.left?.let { FeedButton(it.label, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { go(it) } }
                    v.up?.let { FeedButton(it.label, NAVY, Color.White, Modifier.weight(1f)) { go(it) } }
                    v.right?.let { FeedButton(it.label, GREEN, Color.White, Modifier.weight(1f)) { go(it) } }
                }
                Text(listOfNotNull(v.right?.let { "Swipe → ${it.label}" }, v.left?.let { "← ${it.label}" }).joinToString(" · ").ifBlank { "Tap one" },
                    fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeedButton(label: String, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(fill).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        contentAlignment = Alignment.Center) { Text(label, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center) }
}

/** A big answer to tap. */
@Composable
private fun ChoiceRow(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp))
}
