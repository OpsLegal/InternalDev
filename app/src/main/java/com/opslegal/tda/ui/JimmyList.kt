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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.FoundPayment
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.CalendarAlerts
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.FrontDoor
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate

/* Jimmy's list: one registry, one list. Everything the checks catch, classified (to do, to decide, to pay, to sort,
 * ideas), one card each, nearest date first. Filters at the top; Filtered is a small line at the bottom. The same card
 * and the same "pick, then Go" for every kind; a to-do takes several options at once, the others one. */

private data class JItem(
    val kind: String, val id: String, val title: String, val sub: String, val from: String, val urgent: Boolean, val due: String, val area: String,
    val u: Update? = null, val bill: Bill? = null, val pay: FoundPayment? = null, val flag: Flags.Flag? = null,
    val call: com.opslegal.tda.core.model.MissedCall? = null, val jot: com.opslegal.tda.core.model.QuickThing? = null,
    val after: com.opslegal.tda.core.model.AfterCall? = null,
    /** The word an area choice is learned for: the vendor, the sender or the caller; for a forwarded email, its address. */
    val word: String = "", val to: String = "", val fwd: String = "", val impact: Int = 2, val at: String = "", val prog: Boolean = false,
)

private val KIND = mapOf("todo" to "✋ To do", "flag" to "🚩 Flagged", "loop" to "👀 In the loop", "decide" to "📅 To decide", "found" to "🧾 To pay", "pay" to "🧾 To pay",
    "sort" to "💳 To sort", "call" to "📞 Call back", "mind" to "✏️ Jotted", "idea" to "💡 Idea", "after" to "📞 After the call", "prog" to "⏳ In progress")
/** The type filters: icon, small name (always shown), which kinds. */
private val TYPES = listOf(Triple("all", "☰", "All"), Triple("todo", "✋", "To do"), Triple("flag", "🚩", "Flags"), Triple("loop", "👀", "Loop"),
    Triple("money", "💲", "Money"), Triple("idea", "💡", "Ideas"))
private fun inFilter(f: String, x: JItem) = when (f) {
    "all" -> true
    "todo" -> x.kind in setOf("todo", "decide", "call", "mind", "after", "prog")
    "money" -> x.kind in setOf("pay", "found", "sort")
    else -> x.kind == f
}
/** One choice cancels the others: the sort sits in one small menu. */
private val SORTS = listOf(Triple("deadline", "📅 Date", "nearest date first"), Triple("emergency", "🔴 Urgent", "urgent first"),
    Triple("impact", "⭐ Impact", "what matters most first"), Triple("newest", "🕐 New", "newest first"))
private val MSG = setOf("todo", "flag", "loop")

private fun money(n: Double) = "%.2f $".format(java.util.Locale.CANADA, n)

private fun items(board: Board, inbox: List<Update>, flags: List<Flags.Flag>, today: LocalDate): List<JItem> {
    val home = Areas.all(board.settings).let { l -> (l.firstOrNull { !it.work } ?: l.first()).id }
    val now = java.time.LocalDateTime.now()
    fun areaOf(u: Update) = FrontDoor.forwardedArea(board, u) ?: u.project.takeIf { it.isNotBlank() }?.let { Areas.ofProject(board, it).id } ?: Areas.guess(board, "${u.from} ${u.title} ${u.summary}")
    val todo = inbox.filter { !FrontDoor.parked(it, now) }.map { u ->
        val cal = u.source == CalendarAlerts.SOURCE
        val bill = !cal && com.opslegal.tda.core.plan.Found.isBill(board, u)
        val to = FrontDoor.addressedTo(u.text, board.about.firstName, u.cc)
        val loop = !cal && to.isNotBlank() && to != "me" && to != "all"
        val title = u.title.removePrefix("📅 ").ifBlank { u.summary.split(" ").take(7).joinToString(" ") }
        val due = u.due.ifBlank { u.actions.firstNotNullOfOrNull { it.date } ?: "9999" }
        val impact = com.opslegal.tda.core.plan.Updates.impactOf(board, u) - if (loop) 1 else 0
        if (bill) {
            val v = com.opslegal.tda.core.plan.Found.vendor(u)
            JItem("found", u.id, v + (com.opslegal.tda.core.plan.Found.amount(u)?.let { " · " + money(it) } ?: ""), title, sourceName(u.source), u.urgent,
                due, Areas.guess(board, "$v ${u.title} ${u.summary}"), u = u, word = v, impact = impact, at = u.at)
        } else JItem(if (cal) "decide" else if (loop) "loop" else if (u.flagged) "flag" else "todo", u.id, title,
            if (loop) "Written to $to: you're in the loop. ${u.summary}" else u.summary,
            if (cal) "Calendar" else listOf(sourceName(u.source), u.from).filter { it.isNotBlank() }.joinToString(" · "), u.urgent && !loop,
            due, areaOf(u), u = u, word = if (cal) "" else u.from.substringBefore('<').trim(), to = to, fwd = u.to, impact = impact, at = u.at)
    }
    val pay = Bills.open(board).map { b ->
        JItem("pay", b.id, b.vendor + (b.amount?.let { " · " + money(it) } ?: ""), b.category + if (b.auto) " · auto-pay" else "", "To pay",
            b.due <= today.plusDays(2).toString(), b.due, b.area.ifBlank { Areas.learned(board, b.vendor)?.id ?: home }, bill = b, word = b.vendor, impact = 3)
    }
    val sort = if (!board.checks.payments) emptyList() else board.paymentsFound.map { p ->
        JItem("sort", p.id, "${p.merchant} · ${money(p.amount)}", "${if (p.kind == "transfer") "Interac" else p.app} · ${p.at.take(10)}", "Payment",
            false, "9998", Areas.guess(board, p.merchant), pay = p, word = p.merchant, at = p.at)
    }
    val calls = board.missed.map { c ->
        JItem("call", c.id, "Call back ${c.who}", "${if (c.count > 1) "${c.count} missed calls" else "Missed call"} · ${c.at.substringAfter('T').take(5)}", "Phone",
            c.count > 1, today.toString(), Areas.guess(board, c.who), call = c, word = c.who, impact = 3, at = c.at)
    }
    val afters = board.afterCalls.map { c ->
        JItem("after", c.id, "Call with ${c.who} ended (${c.mins} min)", "Anything to follow up? One line per action.", "📞 ${c.at.substringAfter('T').take(5)}",
            false, today.toString(), Areas.guess(board, c.who), after = c, word = c.who, impact = 3, at = c.at)
    }
    val jots = board.quick.filter { it.check && !it.done }.map { q ->
        JItem("mind", q.id, q.title, "Jotted · sort it in one tap", "On my mind", false, "9997", q.area.ifBlank { Areas.guess(board, q.title) }, jot = q, impact = 1)
    }
    val idea = flags.map { f -> JItem("idea", f.id, f.title, f.sub, "Idea", f.red, "9999", Areas.guess(board, f.title), flag = f, impact = 1) }
    // In progress: cards turned into a next action; they close by themselves once it is done.
    val prog = FrontDoor.inProgress(board).map { u ->
        JItem("prog", u.id, u.prog!!.title, "From ${u.from}: ${u.title.ifBlank { u.summary }}", "⏳ ${u.prog!!.where}", false, "9995", Areas.guess(board, u.prog!!.title), u = u, prog = true, impact = 1)
    }
    val all = afters + calls + todo + pay + sort + jots + idea
    val order: Comparator<JItem> = when (board.checks.sort) {
        "emergency" -> compareByDescending<JItem> { it.urgent }.thenBy { it.due }
        "impact" -> compareByDescending<JItem> { it.impact + if (it.urgent) 1 else 0 }.thenBy { it.due }
        "newest" -> compareByDescending<JItem> { it.at }
        else -> compareBy<JItem> { it.due }.thenByDescending { it.urgent }
    }
    return all.sortedWith(order) + prog
}

/** Each kind's options: key, label, can be picked. Always the same ones, in the same places; what doesn't apply is greyed. */
private fun options(x: JItem, agents: Boolean): List<Triple<String, String, Boolean>> = when (x.kind) {
    in MSG -> {
        val other = x.u!!.actions.any { it.type != "add" } && x.u.status == com.opslegal.tda.core.model.UpdateStatus.NEW
        listOf(Triple("done", "Done", true), Triple("today", "⚡ Today", true), Triple("next", "➜ Next action", true), Triple("park", "⏰ Park", true),
            Triple("apply", "As proposed", other), Triple("agent", "🤖 Agent", agents), Triple("reply", if (x.u.meeting.isNotBlank()) "↩ Answer" else "↩ Reply", !x.u.replied), Triple("none", "Not mine", true))
    }
    "decide" -> listOf(Triple("add", "📅 Add to my day", true), Triple("out", "Leave out", true), Triple("open", "Open calendar", true))
    "found" -> listOf(Triple("topay", "🧾 Add to To pay", true), Triple("paid", "Already paid", true), Triple("notbill", "Not a bill", true))
    "pay" -> listOf(Triple("paid", "Paid", true), Triple("later", "⏰ Remind me", true), Triple("none", "Not mine", true))
    "sort" -> listOf(Triple("keep", "💲 Keep as expense", true), Triple("receipt", "📷 With its receipt", true), Triple("none", "Not mine", true))
    "call" -> listOf(Triple("call", "📞 Call now", true), Triple("today", "⚡ Today's line", true), Triple("done", "Already done", true), Triple("none", "No need", true))
    "after" -> listOf(Triple("say", "➜ Make actions", true), Triple("nothing", "Nothing to do", true))
    "prog" -> listOf(Triple("done2", "Done", true), Triple("open", "Open it", true), Triple("drop", "Not needed any more", true))
    "mind" -> listOf(Triple("quick", "⚡ Quick thing", true), Triple("task", "📋 Task", true), Triple("buy", "🛒 To buy", true), Triple("idea", "💡 Idea", true), Triple("delete", "Delete", true))
    else -> x.flag!!.actions.map { Triple(it.first, it.second, true) } + listOf(Triple("later", "⏰ Later", true), Triple("no", "No, drop it", true))
}

/** Automation pre-selects (by rules); the user can always change it. */
private fun preselect(x: JItem): Set<String> = when (x.kind) {
    in MSG -> buildSet {
        val acts = x.u!!.actions
        val other = acts.any { it.type != "add" } && x.u.status == com.opslegal.tda.core.model.UpdateStatus.NEW
        add(if (x.kind == "loop") "done" else if (other) "apply" else if (acts.isNotEmpty() || x.kind == "flag") "next" else "done")
        if (x.kind != "loop" && x.u.needsReply && !x.u.replied) add("reply")
    }
    "found" -> setOf("topay")
    "sort" -> setOf("keep")
    "call" -> setOf("today")
    "after" -> setOf("say")
    "prog" -> setOf("done2")
    "mind" -> setOf(com.opslegal.tda.core.plan.Labels.kindOf(x.title))
    else -> emptySet()
}

/** What the panel under a card holds while it is open (Next action, Park, After the call). */
private class Panel(var text: String? = null, var where: String? = null, var project: String = "", var newName: String = "", var day: LocalDate? = null,
    var park: String = "next", var parkDay: LocalDate? = null, var said: String = "")

/** Jimmy's panel: the front door. One card per thing, one decision each; filters stay on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HubSheet(vm: MainViewModel) {
    val open by vm.hubOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val inbox by vm.inbox.collectAsStateWithLifecycle()
    val flags by vm.flags.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by rememberSaveable { mutableStateOf("all") }
    var areasOn by remember { mutableStateOf(setOf<String>()) }
    var sortOpen by remember { mutableStateOf(false) }
    val picked = remember { mutableStateMapOf<String, Set<String>>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    val areaOver = remember { mutableStateMapOf<String, String>() }
    val panels = remember { mutableStateMapOf<String, Panel>() }
    var tick by remember { mutableStateOf(0) }
    var areaOpen by remember { mutableStateOf<String?>(null) }
    val notBill = remember { mutableStateMapOf<String, Boolean>() }
    var replying by remember { mutableStateOf<Update?>(null) }
    var afterLines by remember { mutableStateOf<Pair<com.opslegal.tda.core.model.AfterCall, List<String>>?>(null) }
    val close = { vm.hubOpen.value = false }
    replying?.let { u -> ReplyDialog(u, vm, onDone = { replying = null }); return }
    afterLines?.let { (c, lines) -> AfterCallDialog(board, c, lines, onDone = { ok -> if (ok != null) vm.afterCallActions(c.id, ok); afterLines = null }); return }
    val all = items(board, inbox, flags, today).map { if (it.kind == "found" && notBill[it.id] == true) it.copy(kind = "todo") else it }
        .map { x -> areaOver[x.id]?.let { x.copy(area = it) } ?: x }
    fun inArea(x: JItem) = areasOn.isEmpty() || x.area in areasOn
    val shown = all.filter { inFilter(filter, it) && inArea(it) }
    val name = if (com.opslegal.tda.core.agent.Me.named(board)) com.opslegal.tda.core.agent.Me.name(board) else "Your assistant"
    val red = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)
    val areas = Areas.all(board.settings)
    val sort = SORTS.firstOrNull { it.first == board.checks.sort } ?: SORTS.first()
    fun panel(id: String) = panels.getOrPut(id) { Panel() }

    fun go(x: JItem) {
        val sel = picked[x.id] ?: preselect(x)
        val note = notes[x.id].orEmpty().trim()
        val area = x.area
        val k = sel.firstOrNull { it != "reply" } ?: sel.firstOrNull()
        val p = panel(x.id)
        picked.remove(x.id); notes.remove(x.id); areaOver.remove(x.id)
        when (x.kind) {
            in MSG -> {
                val u0 = x.u!!
                if (note.isNotBlank()) vm.setUpdateNote(u0.id, note)
                val u = if (note.isNotBlank()) u0.copy(comment = note) else u0
                when (k) {
                    "done" -> vm.dismissItem(u, "done", u.comment, null)
                    "none" -> vm.dismissItem(u, "irrelevant", u.comment, null)
                    "apply" -> vm.applyUpdate(u)
                    "today" -> vm.nextAction(u, x.title, "quick", "", today, area)
                    "next" -> {
                        val proposed = u.actions.firstOrNull { it.type == "add" }
                        val where = p.where ?: (if ((proposed?.project ?: u.project).isNotBlank()) "proj" else "quick")
                        val proj = if (where == "new") p.newName else if (where == "proj") p.project.ifBlank { proposed?.project ?: u.project } else ""
                        vm.nextAction(u, p.text ?: proposed?.title ?: x.title, if (where == "quick") "quick" else "task", proj, p.day, area)
                    }
                    "park" -> {
                        val nextT = FrontDoor.nextCheckin(board, java.time.LocalDateTime.now())
                        val (pk, label) = when (p.park) {
                            "someday" -> com.opslegal.tda.core.model.Park("9999-12-31") to "someday"
                            "day" -> com.opslegal.tda.core.model.Park((p.parkDay ?: today.plusDays(1)).toString()) to vm.dayName(p.parkDay ?: today.plusDays(1))
                            else -> (if (nextT != null) com.opslegal.tda.core.model.Park(today.toString(), nextT) else com.opslegal.tda.core.model.Park(today.plusDays(1).toString(), board.checks.times.minOrNull().orEmpty())) to
                                "at the next check-in (${nextT ?: "tomorrow"})"
                        }
                        vm.parkCard(u.id, pk, label)
                    }
                    "agent" -> vm.notify("Agents come with the next update: connect Clay or your Claude agent in Settings › Agents.")
                }
                if ("reply" in sel) replying = u
            }
            "prog" -> when (k) {
                "done2" -> vm.closeProgress(x.id, true)
                "drop" -> vm.closeProgress(x.id, false)
                "open" -> { close(); vm.notify("Your action: “${x.title}” · ${x.from.removePrefix("⏳ ")}") }
            }
            "after" -> {
                val c = x.after!!
                if (k == "nothing") vm.dropAfterCall(c.id)
                else {
                    val lines = p.said.split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isEmpty()) vm.notify("Say or type what you agreed first (one line per action).") else afterLines = c to lines
                }
            }
            "decide" -> when (sel.firstOrNull()) { "add" -> vm.decideCalendar(x.u!!, true); "out" -> vm.decideCalendar(x.u!!, false); "open" -> vm.openCalendarOn(x.u!!) }
            "found" -> {
                val u = x.u!!.let { if (note.isNotBlank()) it.copy(comment = note) else it }
                when (k) {
                    "topay" -> vm.billFromUpdate(u, area)
                    "paid" -> vm.paidFromUpdate(u, area)
                    "notbill" -> { vm.notBill(u); notBill[x.id] = true }
                }
            }
            "pay" -> when (k) {
                "paid" -> vm.payBill(x.id, area = area)
                "none" -> vm.removeBill(x.id)
                else -> vm.notify("I'll remind you 2 days before it's due.")
            }
            "sort" -> when (k) {
                "keep" -> vm.keepPayment(x.id, com.opslegal.tda.core.plan.Payments.guessCategory(x.pay!!.merchant), null, false, note, area)
                "receipt" -> { close(); vm.receiptFor.value = "pay:" + x.id }
                "none" -> vm.dropPayment(x.id)
            }
            "call" -> when (k) {
                "call" -> vm.callNow(x.id)
                "today" -> vm.callToday(x.id)
                "done", "none" -> vm.dropCall(x.id)
            }
            "mind" -> k?.let { kk ->
                if (area != x.jot!!.area) vm.editQuick(x.id) { it.copy(area = area, check = true) }
                vm.sortJotted(x.id, kk)
            }
            "idea" -> {
                val f = x.flag!!
                when (k) {
                    null -> {}
                    "later" -> vm.flagNotNow(f.id, f.title, "later", today)
                    "no" -> vm.flagDone(f.id, f.title, note)
                    else -> if (!vm.flagAction(k, f.title)) close()
                }
            }
        }
        panels.remove(x.id)
    }

    SoftDialog(
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(board.persona, 30.dp); Text(name, modifier = Modifier.weight(1f))
                board.checks.lastCheck?.let { Text("checked " + it.substringAfter('T').take(5), style = MaterialTheme.typography.bodySmall) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 620.dp)) {
                // The filters stay on top while the cards scroll: type (icon + small name + count), areas, and the sort menu.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TYPES.forEach { (k, ic, l) -> FilterBox(filter == k, ic, l, all.count { inFilter(k, it) && inArea(it) }, Modifier.weight(1f)) { filter = k } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    areas.forEach { a ->
                        FilterBox(a.id in areasOn, null, a.name, all.count { inFilter(filter, it) && it.area == a.id }, Modifier.width(62.dp), dot = areaColor(board, a.id)) {
                            areasOn = if (a.id in areasOn) areasOn - a.id else areasOn + a.id
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TagChip(false, { sortOpen = true }, label = { Text("↕ ${sort.second} ▾", fontSize = 12.sp) })
                        DropdownMenu(sortOpen, { sortOpen = false }) {
                            SORTS.forEach { (k, l, d) ->
                                DropdownMenuItem(text = { Text((if (k == sort.first) "✓ " else "") + "$l  ·  $d", fontSize = 13.sp, fontWeight = if (k == sort.first) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { sortOpen = false; vm.setUpdateSort(k) })
                            }
                        }
                    }
                }
                Text("${TYPES.first { it.first == filter }.third} · ${if (areasOn.isEmpty()) "all areas" else areas.filter { it.id in areasOn }.joinToString { it.name }} · ${sort.third}. Pick, then Go →. 🔒",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fresh = shown.filter { !it.prog }
                    if (fresh.isEmpty()) Text("Nothing new here. Well done.", style = MaterialTheme.typography.bodyMedium)
                    @Composable fun card(x: JItem) {
                        tick.let { }
                        val c = areaColor(board, x.area)
                        val sel = picked[x.id] ?: preselect(x)
                        val shape = RoundedCornerShape(10.dp)
                        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(if (x.urgent) 2.dp else 1.5.dp, if (x.urgent) red else c.copy(alpha = if (x.prog) 0.5f else 1f), shape)
                            .padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(KIND.getValue(x.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp))
                                if (x.to == "me") Text("👋 To you", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D6B3A))
                                if (x.fwd.isNotBlank()) Text("↪ " + x.fwd.substringAfter('@').substringBefore('.').replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(x.from, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { areaOpen = if (areaOpen == x.id) null else x.id }.padding(horizontal = 3.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                    Text(areas.firstOrNull { it.id == x.area }?.name.orEmpty() + " ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                runCatching { LocalDate.parse(x.due.take(10)) }.getOrNull()?.let {
                                    Text(vm.dayName(it), fontSize = 11.sp, color = if (x.due < today.toString()) red else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (areaOpen == x.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                areas.forEach { a ->
                                    PickChip(a.id == x.area, a.name, dot = areaColor(board, a.id)) {
                                        areaOver[x.id] = a.id; areaOpen = null
                                        if (a.id != x.area) { if (x.fwd.isNotBlank()) vm.learnAddress(x.fwd, a.id) else if (x.word.isNotBlank()) vm.learnAreaWord(x.word, a.id) }
                                    }
                                }
                            }
                            Text((if (x.urgent) "🔴 " else "") + x.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (x.sub.isNotBlank()) Text(x.sub, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                options(x, false).forEach { (k, l, on) ->
                                    PickChip(k in sel, l, enabled = on) {
                                        val now = if (x.kind in MSG) {
                                            if (k == "reply") (if (k in sel) sel - k else sel + k)
                                            else (if (k in sel) emptySet() else setOf(k)) + (if ("reply" in sel && k != "done" && k != "none") setOf("reply") else emptySet())
                                        } else if (k in sel) emptySet() else setOf(k)
                                        picked[x.id] = now
                                    }
                                }
                            }
                            CardPanel(x, sel, panel(x.id), board, vm, today, context) { tick++ }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                androidx.compose.foundation.text.BasicTextField(notes[x.id].orEmpty(), { notes[x.id] = it.replace("\n", " ") }, singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                                    decorationBox = { inner -> Box { if (notes[x.id].isNullOrEmpty()) Text("📝 A note (goes with Go)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                                if (x.kind in MSG) TextButton(onClick = { close(); vm.discussUpdate(x.u!!) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("💬", fontSize = 13.sp) }
                                Button(enabled = sel.isNotEmpty(), onClick = { go(x) }, contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    modifier = Modifier.heightIn(min = 32.dp)) { Text("Go →", fontSize = 13.sp) }
                            }
                        }
                    }
                    fresh.forEach { card(it) }
                    val prog = shown.filter { it.prog }
                    if (prog.isNotEmpty()) {
                        Text("⏳ In progress · ${prog.size}: they close when the action is done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        prog.forEach { card(it) }
                    }
                    Text("🚫 ${board.filtered.size} filtered (ads, scams, ignored) · see", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { close(); vm.filteredOpen.value = true }.padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { close(); vm.startFirstLook() }) { Text("✨ Last 30 days", fontSize = 12.sp) }
                        TextButton(onClick = { close(); vm.startLookAhead() }) { Text("🔭 Next 60 days", fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text(if (checking) "Checking…" else "Check now") } },
    )
}

/** A filter: its icon (or the area's dot) with a small count, and its name in small letters under it. Always readable. */
@Composable
private fun FilterBox(on: Boolean, icon: String?, label: String, count: Int, modifier: Modifier, dot: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.heightIn(min = 40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.Top) {
            if (icon != null) Text(icon, fontSize = 15.sp) else Box(Modifier.padding(top = 3.dp).size(11.dp).clip(CircleShape).background(dot ?: Color.Gray))
            Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 1.dp))
        }
        Text(label, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The small panel under a card when Next action, Park or Make actions is picked: one line, where, when. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardPanel(x: JItem, sel: Set<String>, p: Panel, board: Board, vm: MainViewModel, today: LocalDate, context: android.content.Context, changed: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    refresh.let { }
    fun bump() { refresh++; changed() }
    val shape = RoundedCornerShape(10.dp)
    val box = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp)
    @Composable fun lab(t: String) = Text(t, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    fun pickDay(start: LocalDate, set: (LocalDate) -> Unit) =
        android.app.DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y, m + 1, d)); bump() }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    when {
        x.kind in MSG && "next" in sel -> {
            val u = x.u!!
            val proposed = u.actions.firstOrNull { it.type == "add" }
            val suggested = (proposed?.project ?: u.project).takeIf { it.isNotBlank() && BoardOps.findProject(board, it) != null }.orEmpty()
            if (p.text == null) p.text = proposed?.title ?: x.title
            if (p.where == null) { p.where = if (suggested.isNotBlank()) "proj" else "quick"; p.project = suggested }
            Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lab("Next action (one concrete step)")
                var text by remember(x.id) { mutableStateOf(p.text.orEmpty()) }
                OutlinedTextField(text, { text = it; p.text = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                lab("Where")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PickChip(p.where == "quick", "⚡ Quick thing") { p.where = "quick"; bump() }
                    PickChip(p.where == "task", "📋 Task") { p.where = "task"; p.project = ""; bump() }
                    (listOf(suggested) + board.projects.filter { BoardOps.projectTask(board, it.name) != null }.map { it.name }).filter { it.isNotBlank() }.distinct().take(5).forEach { n ->
                        PickChip(p.where == "proj" && p.project == n, "📁 $n" + if (n == suggested) " ✨" else "") { p.where = "proj"; p.project = n; bump() }
                    }
                    PickChip(p.where == "new", "＋ New project") { p.where = "new"; bump() }
                }
                if (p.where == "new") {
                    var nn by remember(x.id) { mutableStateOf(p.newName) }
                    OutlinedTextField(nn, { nn = it; p.newName = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("New project's name", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                }
                lab("When")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PickChip(p.day == null, "Next free") { p.day = null; bump() }
                    PickChip(p.day == today, "Today") { p.day = today; bump() }
                    PickChip(p.day != null && p.day != today, p.day?.takeIf { it != today }?.let { "📅 " + vm.dayName(it) } ?: "📅") { pickDay(p.day ?: today.plusDays(1)) { p.day = it } }
                }
                Text("The card stays in ⏳ In progress until this is done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        x.kind in MSG && "park" in sel -> Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lab("Bring it back")
            val nextT = FrontDoor.nextCheckin(board, java.time.LocalDateTime.now())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PickChip(p.park == "next", "At the next check-in (${nextT ?: "tomorrow"})") { p.park = "next"; bump() }
                PickChip(p.park == "day", p.parkDay?.let { "📅 " + vm.dayName(it) } ?: "📅 A day") { pickDay(p.parkDay ?: today.plusDays(1)) { p.park = "day"; p.parkDay = it } }
                PickChip(p.park == "someday", "Someday") { p.park = "someday"; bump() }
            }
        }
        x.kind == "after" && "say" in sel -> Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lab("What did you agree? One line per action")
            var said by remember(x.id) { mutableStateOf(p.said) }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(said, { said = it; p.said = it }, Modifier.weight(1f), minLines = 2, textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("Send the documents by Friday\nDrinks with Fares next week", fontSize = 12.sp) })
                DictateButton { t -> said = listOf(said.trim(), t.trim()).filter { it.isNotBlank() }.joinToString("\n"); p.said = said }
            }
        }
    }
}

/** After a call: each line said becomes a next action; the project is guessed from its words, changed in a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AfterCallDialog(board: Board, c: com.opslegal.tda.core.model.AfterCall, lines: List<String>, onDone: (List<Pair<String, String>>?) -> Unit) {
    val rows = remember { mutableStateMapOf<Int, Pair<String, String>>().apply { lines.forEachIndexed { i, l -> put(i, l to FrontDoor.guessProject(board, l)) } } }
    val projects = board.projects.map { it.name }.take(6)
    SoftDialog(
        onDismissRequest = { onDone(null) }, keepOpen = true,
        title = { Text("Next actions from your call") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("With ${c.who}. Check where each one goes, then Add.", style = MaterialTheme.typography.bodySmall)
                lines.indices.forEach { i ->
                    val (t, proj) = rows.getValue(i)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(t, { rows[i] = it to proj }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            PickChip(proj.isBlank(), "⚡ Quick thing") { rows[i] = t to "" }
                            projects.forEach { n -> PickChip(proj == n, "📁 $n") { rows[i] = t to n } }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onDone(lines.indices.map { rows.getValue(it) }.filter { it.first.isNotBlank() }) }) { Text("Add ${lines.size}") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/** A choice chip: the chosen one shows ✓ and is highlighted; one that doesn't apply is greyed, never removed. */
@Composable
internal fun PickChip(selected: Boolean, label: String, enabled: Boolean = true, dot: Color? = null, onClick: () -> Unit) {
    TagChip(selected, { if (enabled) onClick() }, label = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text((if (selected) "✓ " else "") + label, fontSize = 12.sp, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    })
}
