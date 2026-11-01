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
)

private val KIND = mapOf("todo" to "✋ To do", "decide" to "📅 To decide", "pay" to "🧾 To pay", "sort" to "💳 To sort", "idea" to "💡 Idea")
private val FILTERS = listOf("all" to "All", "todo" to "✋ To do", "pay" to "🧾 To pay", "sort" to "💳 To sort", "idea" to "💡 Ideas")
private fun inFilter(f: String, x: JItem) = when (f) { "todo" -> x.kind == "todo" || x.kind == "decide"; "all" -> true; else -> x.kind == f }

private fun items(board: Board, inbox: List<Update>, flags: List<Flags.Flag>, today: LocalDate): List<JItem> {
    val home = Areas.all(board.settings).let { l -> (l.firstOrNull { !it.work } ?: l.first()).id }
    fun areaOf(u: Update) = u.project.takeIf { it.isNotBlank() }?.let { Areas.ofProject(board, it).id } ?: Areas.guess(board, "${u.title} ${u.summary}")
    val todo = inbox.map { u ->
        val cal = u.source == CalendarAlerts.SOURCE
        JItem(if (cal) "decide" else "todo", u.id, u.title.removePrefix("📅 ").ifBlank { u.summary.split(" ").take(7).joinToString(" ") }, u.summary,
            if (cal) "Calendar" else listOf(sourceName(u.source), u.from).filter { it.isNotBlank() }.joinToString(" · "), u.urgent,
            u.due.ifBlank { u.actions.firstNotNullOfOrNull { it.date } ?: "9999" }, areaOf(u), u = u)
    }
    val pay = Bills.open(board).map { b ->
        JItem("pay", b.id, b.vendor + (b.amount?.let { " · %.2f $".format(java.util.Locale.CANADA, it) } ?: ""), b.category + if (b.auto) " · auto-pay" else "", "To pay",
            b.due <= today.plusDays(2).toString(), b.due, b.area.ifBlank { home }, bill = b)
    }
    val sort = if (!board.checks.payments) emptyList() else board.paymentsFound.map { p ->
        JItem("sort", p.id, "${p.merchant} · %.2f $".format(java.util.Locale.CANADA, p.amount), "${if (p.kind == "transfer") "Interac" else p.app}: which part of your life?", "Payment",
            false, "9998", Areas.guess(board, p.merchant), pay = p)
    }
    val idea = flags.map { f -> JItem("idea", f.id, f.title, f.sub, "Idea", f.red, "9999", Areas.guess(board, f.title), flag = f) }
    return (todo + pay + sort + idea).sortedWith(compareBy<JItem> { it.due }.thenByDescending { it.urgent })
}

/** Each kind's options: key, label, can be picked. */
private fun options(x: JItem): List<Triple<String, String, Boolean>> = when (x.kind) {
    "todo" -> listOf(Triple("apply", "✓ Do it", x.u!!.status == com.opslegal.tda.core.model.UpdateStatus.NEW && x.u.actions.isNotEmpty()), Triple("task", "📋 Add to tasks", true),
        Triple("quick", "⚡ Quick thing", true), Triple("reply", if (x.u.meeting.isNotBlank()) "↩ Answer" else "↩ Reply", !x.u.replied), Triple("done", "✓ Already done", true), Triple("none", "✕ No action", true))
    "decide" -> listOf(Triple("add", "📅 Add to my day", true), Triple("out", "Leave out", true), Triple("open", "Open calendar", true))
    "pay" -> listOf(Triple("paid", "✓ Paid", true), Triple("later", "⏰ Remind me", true), Triple("none", "✕ Not mine", true))
    "sort" -> emptyList()
    else -> x.flag!!.actions.map { Triple(it.first, it.second, true) } + listOf(Triple("later", "⏰ Later", true), Triple("no", "✕ No, drop it", true))
}

private fun preselect(x: JItem): Set<String> = when (x.kind) {
    "todo" -> buildSet {
        val adds = x.u!!.actions.isNotEmpty() && x.u.actions.all { it.type == "add" }
        add(if (x.u.status == com.opslegal.tda.core.model.UpdateStatus.NEW && x.u.actions.isNotEmpty() && !adds) "apply" else "task")
        if (x.u.needsReply && !x.u.replied) add("reply")
    }
    "sort" -> setOf("area:" + x.area)
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
    var replying by remember { mutableStateOf<Update?>(null) }
    val close = { vm.hubOpen.value = false }
    replying?.let { u -> ReplyDialog(u, vm, onDone = { replying = null }); return }
    val all = items(board, inbox, flags, today)
    val shown = all.filter { inFilter(filter, it) }
    val name = if (com.opslegal.tda.core.agent.Me.named(board)) com.opslegal.tda.core.agent.Me.name(board) else "Your assistant"
    val red = kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE)

    fun go(x: JItem) {
        val sel = picked[x.id] ?: preselect(x)
        picked.remove(x.id)
        when (x.kind) {
            "todo" -> {
                val u = x.u!!
                if ("apply" in sel) vm.applyUpdate(u)
                if ("task" in sel) { if (u.actions.any { it.type == "add" } && "apply" !in sel) vm.applyUpdate(u) else if ("apply" !in sel) vm.addTask(com.opslegal.tda.core.plan.BoardOps.NewTask(x.title, u.summary, area = x.area)) }
                if ("quick" in sel) vm.itemQuickAdded(u)
                when {
                    "done" in sel -> vm.dismissItem(u, "done", u.comment, null)
                    "none" in sel -> vm.dismissItem(u, "irrelevant", u.comment, null)
                    "reply" !in sel -> { vm.skipReply(u.id); if ("task" in sel && "apply" !in sel && u.actions.none { it.type == "add" }) vm.dismissItem(u, "done", u.comment, null) }
                }
                if ("reply" in sel) replying = u
            }
            "decide" -> when (sel.firstOrNull()) { "add" -> vm.decideCalendar(x.u!!, true); "out" -> vm.decideCalendar(x.u!!, false); "open" -> vm.openCalendarOn(x.u!!) }
            "pay" -> when (sel.firstOrNull()) {
                "paid" -> vm.payBill(x.id, area = x.area)
                "none" -> vm.removeBill(x.id)
                else -> vm.notify("I'll remind you 2 days before it's due.")
            }
            "sort" -> sel.firstOrNull()?.let { k ->
                if (k == "none") vm.dropPayment(x.id)
                else vm.keepPayment(x.id, com.opslegal.tda.core.plan.Payments.guessCategory(x.pay!!.merchant), null, false, "", k.removePrefix("area:"))
            }
            "idea" -> {
                val f = x.flag!!
                when (val k = sel.firstOrNull()) {
                    null -> {}
                    "later" -> vm.flagNotNow(f.id, f.title, "later", today)
                    "no" -> vm.flagDone(f.id, f.title, "")
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
                    FILTERS.forEach { (k, l) -> TagChip(filter == k, { filter = k }, label = { Text("$l ${all.count { inFilter(k, it) }}", fontSize = 12.sp) }) }
                }
                Text("Nearest date first. Pick, then Go. 🔒 Nothing leaves without your tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shown.isEmpty()) Text("Nothing here. Well done.", style = MaterialTheme.typography.bodyMedium)
                shown.forEach { x ->
                    val c = areaColor(board, x.area)
                    val sel = picked[x.id] ?: preselect(x)
                    val shape = RoundedCornerShape(10.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(if (x.urgent) 2.dp else 1.5.dp, if (x.urgent) red else c, shape)
                        .padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(KIND.getValue(x.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp))
                            Text(x.from, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                            Text(Areas.all(board.settings).firstOrNull { it.id == x.area }?.name.orEmpty() +
                                (runCatching { LocalDate.parse(x.due.take(10)) }.getOrNull()?.let { " · " + vm.dayName(it) } ?: ""), fontSize = 11.sp,
                                color = if (x.due < today.toString()) red else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (x.urgent) "🔴 " else "") + x.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(x.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val opts = if (x.kind == "sort") Areas.all(board.settings).map { Triple("area:" + it.id, it.name, true) } + Triple("none", "✕ Not mine", true) else options(x)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            opts.forEach { (k, l, on) ->
                                TagChip(k in sel, {
                                    if (!on) return@TagChip
                                    val now = if (x.kind == "todo") { if (k in sel) sel - k else sel + k - EXCL[k].orEmpty() } else if (k in sel) emptySet() else setOf(k)
                                    picked[x.id] = now
                                }, label = { Text(l, fontSize = 12.sp, color = if (on) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) })
                            }
                            Button(enabled = sel.isNotEmpty(), onClick = { go(x) }, contentPadding = ButtonDefaults.TextButtonContentPadding,
                                modifier = Modifier.heightIn(min = 32.dp)) { Text("Go", fontSize = 13.sp) }
                            if (x.kind == "todo") TextButton(onClick = { close(); vm.discussUpdate(x.u!!) }) { Text("💬 Discuss", fontSize = 12.sp) }
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
