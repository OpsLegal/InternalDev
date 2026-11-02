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
import com.opslegal.tda.core.plan.CalendarAlerts
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate

/* Jimmy's list: one registry, one list. Everything the checks catch, classified (to do, to decide, to pay, to sort,
 * ideas), one card each, nearest date first. Filters at the top; Filtered is a small line at the bottom. The same card
 * and the same "pick, then Go" for every kind; a to-do takes several options at once, the others one. */

private data class JItem(
    val kind: String, val id: String, val title: String, val sub: String, val from: String, val urgent: Boolean, val due: String, val area: String,
    val u: Update? = null, val bill: Bill? = null, val pay: FoundPayment? = null, val flag: Flags.Flag? = null,
    val call: com.opslegal.tda.core.model.MissedCall? = null, val jot: com.opslegal.tda.core.model.QuickThing? = null,
    /** The word an area choice is learned for: the vendor, the sender or the caller. */
    val word: String = "",
)

private val KIND = mapOf("todo" to "✋ To do", "decide" to "📅 To decide", "found" to "🧾 To pay", "pay" to "🧾 To pay", "sort" to "💳 To sort",
    "call" to "📞 Call back", "mind" to "✏️ Jotted", "idea" to "💡 Idea")
private val FILTERS = listOf("all" to "All", "todo" to "✋ To do", "pay" to "🧾 To pay", "sort" to "💳 To sort", "idea" to "💡 Ideas")
private fun inFilter(f: String, x: JItem) = when (f) {
    "all" -> true
    "todo" -> x.kind in setOf("todo", "decide", "call")
    "pay" -> x.kind in setOf("pay", "found")
    "sort" -> x.kind in setOf("sort", "mind")
    else -> x.kind == f
}

private fun money(n: Double) = "%.2f $".format(java.util.Locale.CANADA, n)

private fun items(board: Board, inbox: List<Update>, flags: List<Flags.Flag>, today: LocalDate): List<JItem> {
    val home = Areas.all(board.settings).let { l -> (l.firstOrNull { !it.work } ?: l.first()).id }
    fun areaOf(u: Update) = u.project.takeIf { it.isNotBlank() }?.let { Areas.ofProject(board, it).id } ?: Areas.guess(board, "${u.from} ${u.title} ${u.summary}")
    val todo = inbox.map { u ->
        val cal = u.source == CalendarAlerts.SOURCE
        val bill = !cal && com.opslegal.tda.core.plan.Found.isBill(board, u)
        val title = u.title.removePrefix("📅 ").ifBlank { u.summary.split(" ").take(7).joinToString(" ") }
        if (bill) {
            val v = com.opslegal.tda.core.plan.Found.vendor(u)
            JItem("found", u.id, v + (com.opslegal.tda.core.plan.Found.amount(u)?.let { " · " + money(it) } ?: ""), title, sourceName(u.source), u.urgent,
                u.due.ifBlank { u.actions.firstNotNullOfOrNull { it.date } ?: "9999" }, Areas.guess(board, "$v ${u.title} ${u.summary}"), u = u, word = v)
        } else JItem(if (cal) "decide" else "todo", u.id, title, u.summary,
            if (cal) "Calendar" else listOf(sourceName(u.source), u.from).filter { it.isNotBlank() }.joinToString(" · "), u.urgent,
            u.due.ifBlank { u.actions.firstNotNullOfOrNull { it.date } ?: "9999" }, areaOf(u), u = u, word = if (cal) "" else u.from.substringBefore('<').trim())
    }
    val pay = Bills.open(board).map { b ->
        JItem("pay", b.id, b.vendor + (b.amount?.let { " · " + money(it) } ?: ""), b.category + if (b.auto) " · auto-pay" else "", "To pay",
            b.due <= today.plusDays(2).toString(), b.due, b.area.ifBlank { Areas.learned(board, b.vendor)?.id ?: home }, bill = b, word = b.vendor)
    }
    val sort = if (!board.checks.payments) emptyList() else board.paymentsFound.map { p ->
        JItem("sort", p.id, "${p.merchant} · ${money(p.amount)}", "${if (p.kind == "transfer") "Interac" else p.app} · ${p.at.take(10)}", "Payment",
            false, "9998", Areas.guess(board, p.merchant), pay = p, word = p.merchant)
    }
    val calls = board.missed.map { c ->
        JItem("call", c.id, "Call back ${c.who}", "${if (c.count > 1) "${c.count} missed calls" else "Missed call"} · ${c.at.substringAfter('T').take(5)}", "Phone",
            c.count > 1, today.toString(), Areas.guess(board, c.who), call = c, word = c.who)
    }
    val jots = board.quick.filter { it.check && !it.done }.map { q ->
        JItem("mind", q.id, q.title, "Jotted · sort it in one tap", "On my mind", false, "9997", q.area.ifBlank { Areas.guess(board, q.title) }, jot = q)
    }
    val idea = flags.map { f -> JItem("idea", f.id, f.title, f.sub, "Idea", f.red, "9999", Areas.guess(board, f.title), flag = f) }
    return (calls + todo + pay + sort + jots + idea).sortedWith(compareBy<JItem> { it.due }.thenByDescending { it.urgent })
}

/** Each kind's options: key, label, can be picked. Always the same ones, in the same places; what doesn't apply is greyed. */
private fun options(x: JItem): List<Triple<String, String, Boolean>> = when (x.kind) {
    "todo" -> listOf(Triple("apply", "Do it", x.u!!.status == com.opslegal.tda.core.model.UpdateStatus.NEW && x.u.actions.isNotEmpty()), Triple("task", "📋 Add to tasks", true),
        Triple("quick", "⚡ Quick thing", true), Triple("reply", if (x.u.meeting.isNotBlank()) "↩ Answer" else "↩ Reply", !x.u.replied), Triple("done", "Already done", true), Triple("none", "No action", true))
    "decide" -> listOf(Triple("add", "📅 Add to my day", true), Triple("out", "Leave out", true), Triple("open", "Open calendar", true))
    "found" -> listOf(Triple("topay", "🧾 Add to To pay", true), Triple("paid", "Already paid", true), Triple("notbill", "Not a bill", true))
    "pay" -> listOf(Triple("paid", "Paid", true), Triple("later", "⏰ Remind me", true), Triple("none", "Not mine", true))
    "sort" -> listOf(Triple("keep", "💲 Keep as expense", true), Triple("receipt", "📷 With its receipt", true), Triple("none", "Not mine", true))
    "call" -> listOf(Triple("call", "📞 Call now", true), Triple("today", "⚡ Today's line", true), Triple("done", "Already done", true), Triple("none", "No need", true))
    "mind" -> listOf(Triple("quick", "⚡ Quick thing", true), Triple("task", "📋 Task", true), Triple("buy", "🛒 To buy", true), Triple("idea", "💡 Idea", true), Triple("delete", "Delete", true))
    else -> x.flag!!.actions.map { Triple(it.first, it.second, true) } + listOf(Triple("later", "⏰ Later", true), Triple("no", "No, drop it", true))
}

/** Automation pre-selects (by rules); the user can always change it. */
private fun preselect(x: JItem): Set<String> = when (x.kind) {
    "todo" -> buildSet {
        val adds = x.u!!.actions.isNotEmpty() && x.u.actions.all { it.type == "add" }
        add(if (x.u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && x.u.actions.isNotEmpty() && !adds) "apply" else "task")
        if (x.u.needsReply && !x.u.replied) add("reply")
    }
    "found" -> setOf("topay")
    "sort" -> setOf("keep")
    "call" -> setOf("today")
    "mind" -> setOf(com.opslegal.tda.core.plan.Labels.kindOf(x.title).let { if (it == "big") "task" else it })
    else -> emptySet()
}

private val EXCL = mapOf("done" to setOf("apply", "task", "quick", "none"), "none" to setOf("apply", "task", "quick", "done", "reply"),
    "apply" to setOf("done", "none"), "task" to setOf("done", "none"), "quick" to setOf("done", "none"), "reply" to setOf("none"))

/** Jimmy's panel: the list (it replaces the two round buttons). */
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
    var filter by rememberSaveable { mutableStateOf("all") }
    val picked = remember { mutableStateMapOf<String, Set<String>>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    val areaOver = remember { mutableStateMapOf<String, String>() }
    var areaOpen by remember { mutableStateOf<String?>(null) }
    val notBill = remember { mutableStateMapOf<String, Boolean>() }
    var replying by remember { mutableStateOf<Update?>(null) }
    val close = { vm.hubOpen.value = false }
    replying?.let { u -> ReplyDialog(u, vm, onDone = { replying = null }); return }
    val all = items(board, inbox, flags, today).map { if (it.kind == "found" && notBill[it.id] == true) it.copy(kind = "todo") else it }
    val shown = all.filter { inFilter(filter, it) }
    val name = if (com.opslegal.tda.core.agent.Me.named(board)) com.opslegal.tda.core.agent.Me.name(board) else "Your assistant"
    val red = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)
    val areas = Areas.all(board.settings)

    fun go(x: JItem) {
        val sel = picked[x.id] ?: preselect(x)
        val note = notes[x.id].orEmpty().trim()
        val area = areaOver[x.id] ?: x.area
        picked.remove(x.id); notes.remove(x.id); areaOver.remove(x.id)
        when (x.kind) {
            "todo" -> {
                val u0 = x.u!!
                if (note.isNotBlank()) vm.setUpdateNote(u0.id, note)
                val u = if (note.isNotBlank()) u0.copy(comment = note) else u0
                if ("apply" in sel) vm.applyUpdate(u)
                if ("task" in sel) { if (u.actions.any { it.type == "add" } && "apply" !in sel) vm.applyUpdate(u) else if ("apply" !in sel) vm.addTask(com.opslegal.tda.core.plan.BoardOps.NewTask(x.title, listOf(u.summary, note).filter { it.isNotBlank() }.joinToString("\n"), area = area)) }
                if ("quick" in sel) vm.itemQuickAdded(u)
                when {
                    "done" in sel -> vm.dismissItem(u, "done", u.comment, null)
                    "none" in sel -> vm.dismissItem(u, "irrelevant", u.comment, null)
                    "reply" !in sel -> { vm.skipReply(u.id); if ("task" in sel && "apply" !in sel && u.actions.none { it.type == "add" }) vm.dismissItem(u, "done", u.comment, null) }
                }
                if ("reply" in sel) replying = u
            }
            "decide" -> when (sel.firstOrNull()) { "add" -> vm.decideCalendar(x.u!!, true); "out" -> vm.decideCalendar(x.u!!, false); "open" -> vm.openCalendarOn(x.u!!) }
            "found" -> {
                val u = x.u!!.let { if (note.isNotBlank()) it.copy(comment = note) else it }
                when (sel.firstOrNull()) {
                    "topay" -> vm.billFromUpdate(u, area)
                    "paid" -> vm.paidFromUpdate(u, area)
                    "notbill" -> { vm.notBill(u); notBill[x.id] = true }
                }
            }
            "pay" -> when (sel.firstOrNull()) {
                "paid" -> vm.payBill(x.id, area = area)
                "none" -> vm.removeBill(x.id)
                else -> vm.notify("I'll remind you 2 days before it's due.")
            }
            "sort" -> when (sel.firstOrNull()) {
                "keep" -> vm.keepPayment(x.id, com.opslegal.tda.core.plan.Payments.guessCategory(x.pay!!.merchant), null, false, note, area)
                "receipt" -> { close(); vm.receiptFor.value = "pay:" + x.id }
                "none" -> vm.dropPayment(x.id)
            }
            "call" -> when (sel.firstOrNull()) {
                "call" -> vm.callNow(x.id)
                "today" -> vm.callToday(x.id)
                "done", "none" -> vm.dropCall(x.id)
            }
            "mind" -> sel.firstOrNull()?.let { k ->
                if (area != x.jot!!.area) vm.editQuick(x.id) { it.copy(area = area, check = true) }
                vm.sortJotted(x.id, k)
            }
            "idea" -> {
                val f = x.flag!!
                when (val k = sel.firstOrNull()) {
                    null -> {}
                    "later" -> vm.flagNotNow(f.id, f.title, "later", today)
                    "no" -> vm.flagDone(f.id, f.title, note)
                    else -> if (!vm.flagAction(k, f.title)) close()
                }
            }
        }
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
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FILTERS.forEach { (k, l) -> PickChip(filter == k, "$l ${all.count { inFilter(k, it) }}") { filter = k } }
                }
                Text("Nearest date first. Pick, then Go →. 🔒 Nothing leaves without your tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shown.isEmpty()) Text("Nothing here. Well done.", style = MaterialTheme.typography.bodyMedium)
                shown.forEach { x ->
                    val area = areaOver[x.id] ?: x.area
                    val c = areaColor(board, area)
                    val sel = picked[x.id] ?: preselect(x)
                    val shape = RoundedCornerShape(10.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(if (x.urgent) 2.dp else 1.5.dp, if (x.urgent) red else c, shape)
                        .padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(KIND.getValue(x.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp))
                            Text(x.from, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            // The area: tap to change it (learned for this vendor, sender or caller).
                            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { areaOpen = if (areaOpen == x.id) null else x.id }.padding(horizontal = 3.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                Text(areas.firstOrNull { it.id == area }?.name.orEmpty() + " ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            runCatching { LocalDate.parse(x.due.take(10)) }.getOrNull()?.let {
                                Text(vm.dayName(it), fontSize = 11.sp, color = if (x.due < today.toString()) red else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (areaOpen == x.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            areas.forEach { a ->
                                PickChip(a.id == area, a.name, dot = areaColor(board, a.id)) {
                                    areaOver[x.id] = a.id; areaOpen = null
                                    if (x.word.isNotBlank() && a.id != x.area) vm.learnAreaWord(x.word, a.id)
                                }
                            }
                        }
                        Text((if (x.urgent) "🔴 " else "") + x.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (x.sub.isNotBlank()) Text(x.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            options(x).forEach { (k, l, on) ->
                                PickChip(k in sel, l, enabled = on) {
                                    val now = if (x.kind == "todo") { if (k in sel) sel - k else sel + k - EXCL[k].orEmpty() } else if (k in sel) emptySet() else setOf(k)
                                    picked[x.id] = now
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.foundation.text.BasicTextField(notes[x.id].orEmpty(), { notes[x.id] = it.replace("\n", " ") }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                                decorationBox = { inner -> Box { if (notes[x.id].isNullOrEmpty()) Text("📝 A note (goes with Go)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                            if (x.kind == "todo") TextButton(onClick = { close(); vm.discussUpdate(x.u!!) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("💬", fontSize = 13.sp) }
                            Button(enabled = sel.isNotEmpty(), onClick = { go(x) }, contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.heightIn(min = 32.dp)) { Text("Go →", fontSize = 13.sp) }
                        }
                    }
                }
                Text("🚫 ${board.filtered.size} filtered (ads, scams, ignored) · see", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { close(); vm.filteredOpen.value = true }.padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { close(); vm.startFirstLook() }) { Text("✨ Last 30 days", fontSize = 12.sp) }
                    TextButton(onClick = { close(); vm.startLookAhead() }) { Text("🔭 Next 60 days", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(enabled = !checking, onClick = { vm.checkUpdatesNow() }) { Text(if (checking) "Checking…" else "Check now") } },
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
