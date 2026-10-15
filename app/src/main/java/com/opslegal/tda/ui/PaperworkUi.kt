package com.opslegal.tda.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Doc
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.data.PaperFiles
import kotlinx.coroutines.launch
import java.time.LocalDate

private val DOC_ICON = mapOf("scan" to "📷", "file" to "📄", "email" to "✉️", "link" to "🔗")

/** The four ways to bring a document: scan (camera), a file, an email's attachment, a link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDocButtons(board: Board, onDoc: (Doc) -> Unit, vm: MainViewModel) {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Uri?>(null) }
    var mails by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    // A form of several pages becomes one PDF; edges found, cropped and straightened by the scanner.
    val scan = rememberScanner(pages = 10) { onDoc(vm.docFrom(it, "scan")) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onDoc(vm.docFrom(it, "file")) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = scan) { Text("📷 Scan") }
        OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }) { Text("📄 A file") }
        OutlinedButton(onClick = { mails = !mails }) { Text("✉️ From an email") }
        OutlinedButton(onClick = { link = if (link == null) "" else null }) { Text("🔗 A link") }
    }
    if (mails) {
        val withFiles = board.updates.filter { it.attachments.isNotEmpty() }
        if (withFiles.isEmpty()) Text("No email with an attachment yet: they come with the next check of the bell.", style = MaterialTheme.typography.bodySmall)
        withFiles.forEach { u -> u.attachments.forEach { name ->
            Text("✉️ $name · ${u.from}", modifier = Modifier.fillMaxWidth().clickable {
                onDoc(Doc(BoardOps.newId(), name, "email", from = "from ${u.from}", at = LocalDate.now().toString())); mails = false
            }.padding(vertical = 8.dp))
        } }
    }
    link?.let { l ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(l, { link = it }, "Link (OneDrive, SharePoint, a website)", Modifier.weight(1f))
            TextButton(enabled = l.isNotBlank(), onClick = {
                val url = if (l.startsWith("http")) l.trim() else "https://${l.trim()}"
                onDoc(Doc(BoardOps.newId(), url.removePrefix("https://").removePrefix("http://").take(60), "link", url, at = LocalDate.now().toString())); link = null
            }) { Text("Keep it") }
        }
    }
}

/** 📎 Documents of a cell (kept with its task or project): open, what to fill, remove; add one. */
@Composable
internal fun DocsDialog(vm: MainViewModel, board: Board, task: Task, step: Step, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val docs = Paperwork.docs(board, task)
    var filling by remember { mutableStateOf<Doc?>(null) }
    filling?.let { d -> WhatToFillDialog(vm, task, step, d) { filling = null }; return }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text("📎 Documents") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kept with ${if (task.isProject) "the project ${task.project}" else "this task"}: letters you scan, forms you download, attachments of emails. " +
                    "The file stays on your phone; only the link is kept.", style = MaterialTheme.typography.bodySmall)
                if (docs.isEmpty()) Text("No document yet.", style = MaterialTheme.typography.bodySmall)
                docs.forEach { d ->
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${DOC_ICON[d.kind] ?: "📄"}  ${d.name}", fontWeight = FontWeight.SemiBold)
                        Text(listOf(d.from.ifBlank { mapOf("scan" to "scanned", "file" to "from the phone", "email" to "from an email", "link" to "link")[d.kind].orEmpty() }, d.at).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                        if (d.todo.isNotEmpty()) Text("What to fill: " + d.todo.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (d.uri.isNotBlank()) OutlinedButton(onClick = { PaperFiles.open(context, d.uri) }) { Text("Open") }
                            Button(onClick = { filling = d }) { Text("✨ What to fill") }
                            TextButton(onClick = { vm.removeDoc(d.id) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                AddDocButtons(board, { vm.addDoc(task.id, it) }, vm)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** ✨ What to fill: the assistant reads the form and makes a short checklist that goes on the cell. */
@Composable
internal fun WhatToFillDialog(vm: MainViewModel, task: Task, step: Step, doc: Doc, onDismiss: () -> Unit) {
    var fill by remember { mutableStateOf<com.opslegal.tda.core.agent.Readers.Fill?>(null) }
    var note by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<Int>()) }
    androidx.compose.runtime.LaunchedEffect(doc.id) {
        fill = runCatching { vm.whatToFill(task, doc) }.getOrElse {
            note = "${it.message ?: "The document could not be read."} Here is a general list."
            com.opslegal.tda.core.agent.Readers.Fill(listOf("Read it once and mark what is asked", "Gather the papers it asks for", "Fill it in", "Sign and date it", "Send it (you send it yourself)"), emptyList())
        }
        picked = fill!!.items.indices.toSet()
    }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ What to fill") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(doc.name, fontWeight = FontWeight.SemiBold)
                val f = fill
                if (f == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading it…", style = MaterialTheme.typography.bodySmall) }
                else {
                    Text("To fill or gather:", style = MaterialTheme.typography.labelMedium)
                    f.items.forEachIndexed { i, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { picked = if (i in picked) picked - i else picked + i }) {
                            Checkbox(checked = i in picked, onCheckedChange = { picked = if (it) picked + i else picked - i }); Text(item)
                        }
                    }
                    if (f.known.isNotEmpty()) Text("I already know: " + f.known.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(note.ifBlank { "Add: the items go on this cell as a checklist (“Also during this”). You fill, sign and send it yourself." }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = fill != null && picked.isNotEmpty(), onClick = { fill?.let { f -> vm.addFillItems(step.id, doc.id, f.items.filterIndexed { i, _ -> i in picked }) }; onDismiss() }) { Text("Add to this cell") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
private fun fmt(d: Double) = if (d == 0.0) "" else "%.2f".format(java.util.Locale.ROOT, d)

/**
 * ＋ Expense: the receipt photo first, so the assistant fills the total, taxes, tip and where; then the user checks.
 * Kept with the cell's task or project, a chosen project, or the day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseDialog(vm: MainViewModel, board: Board, task: Task?, step: Step?, existing: Expense?, date: LocalDate?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf(existing?.photo.orEmpty()) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var total by remember { mutableStateOf(existing?.amount?.let(::fmt).orEmpty()) }
    var tax by remember { mutableStateOf(existing?.tax?.let(::fmt).orEmpty()) }
    var tip by remember { mutableStateOf(existing?.tip?.let(::fmt).orEmpty()) }
    var vendor by remember { mutableStateOf(existing?.vendor.orEmpty()) }
    var day by remember { mutableStateOf(existing?.date ?: (date ?: LocalDate.now()).toString()) }
    var cat by remember { mutableStateOf(existing?.category ?: "Meal") }
    var billable by remember { mutableStateOf(existing?.billable ?: (task?.isProject == true)) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var project by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val read = { uri: Uri ->
        photo = uri.toString(); status = "Reading the receipt…"
        scope.launch {
            val r = runCatching { vm.readReceipt(uri) }.getOrNull()
            if (r == null) status = "The receipt could not be read here: type the amounts."
            else {
                if (total.isBlank()) r.total?.let { total = fmt(it) }
                if (tax.isBlank()) r.taxes?.let { tax = fmt(it) }
                if (tip.isBlank()) r.tip?.let { tip = fmt(it) }
                if (vendor.isBlank()) r.where?.let { vendor = it }
                r.date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }?.let { day = it }
                r.category?.let { cat = it }
                status = "✨ Filled from the receipt: check each amount, then Save."
            }
        }
    }
    val scan = rememberScanner { read(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { PaperFiles.keep(context, it); read(it) } }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Expense" else "＋ Expense") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The receipt first: the assistant reads it and fills what it can.
                Cta(if (photo.isBlank()) "📷 Photo of the receipt" else "📷 Replace the receipt") { scan() }
                TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("or pick a photo") }
                Text(status.ifBlank { if (photo.isBlank()) "Start here: your assistant reads the total, taxes, tip and where. No receipt? Type it below." else "Receipt kept." },
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (task != null) Text("For ${if (task.isProject) "the project ${task.project}" else "“${task.title}”"}.", style = MaterialTheme.typography.bodySmall)
                else if (existing == null) ProjectPicker(board, project, { project = it; billable = it.isNotBlank() }, "For a project? (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactField(total, { total = it }, "Total ($)", Modifier.weight(1f))
                    CompactField(tax, { tax = it }, "Taxes ($)", Modifier.weight(1f))
                    CompactField(tip, { tip = it }, "Tip ($)", Modifier.weight(1f))
                }
                CompactField(vendor, { vendor = it }, "Where", Modifier.fillMaxWidth())
                DateField("Date", day, { day = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Paperwork.CATEGORIES.forEach { c -> TagChip(cat == c, { cat = c }, label = { Text(c) }) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Billable to the client"); Text("It shows on the report, apart from your own costs.", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = billable, onCheckedChange = { billable = it })
                }
                CompactField(note, { note = it }, "Note (optional)", Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (existing != null) TextButton(onClick = { vm.deleteExpense(existing.id); onDismiss() }) { Text("Delete this expense", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = num(total)
                if (amount == null || amount <= 0) { error = "Type the total of the receipt."; return@Button }
                val e = Expense(existing?.id ?: BoardOps.newId(), amount, num(tax) ?: 0.0, num(tip) ?: 0.0, vendor.trim(), day, cat, billable, note.trim(), photo,
                    existing?.task ?: task?.let { t -> step?.let { com.opslegal.tda.core.plan.Planner.cellTitle(t, it) } ?: t.title } ?: project)
                vm.saveExpense(e, task?.id, projectName(board, project).ifBlank { null }, isNew = existing == null)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The expense report: a period and a place; the total with taxes, tips and the billable part; CSV or PDF you send yourself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseReport(vm: MainViewModel) {
    val open by vm.expensesOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var period by remember { mutableStateOf("month") }
    var where by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Expense?>(null) }
    val today = LocalDate.now()
    val all = Paperwork.all(board)
    val rows = Paperwork.filter(board, today, period, where)
    val t = Paperwork.totals(rows)
    val close = { vm.expensesOpen.value = false }
    editing?.let { e -> ExpenseDialog(vm, board, null, null, e, null) { editing = null }; return }
    SoftDialog(
        onDismissRequest = close,
        title = { Text("💲 Expense report") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("month" to "This month", "last" to "Last month", "all" to "Everything").forEach { (k, l) -> TagChip(period == k, { period = k }, label = { Text(l) }) }
                }
                val places = all.map { it.where }.distinct()
                if (places.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(where.isBlank(), { where = "" }, label = { Text("All") })
                    places.forEach { p -> TagChip(where == p, { where = p }, label = { Text(p, maxLines = 1) }) }
                }
                if (rows.isEmpty()) Text("No expense for this choice. Add one from any cell, or from ＋.", style = MaterialTheme.typography.bodySmall)
                rows.forEach { (e, w) ->
                    Column(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 6.dp)) {
                        Row { Text("${if (e.photo.isNotBlank()) "🧾" else "💲"}  ${e.vendor.ifBlank { e.category }}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(Paperwork.money(e.amount)) }
                        Text("${e.date} · ${e.category} · $w${if (e.billable) " · billable" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
                if (rows.isNotEmpty()) {
                    Row { Text("Total", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(Paperwork.money(t.total), fontWeight = FontWeight.Bold) }
                    Text("of which taxes ${Paperwork.money(t.taxes)}" + (if (t.tips > 0) " · tips ${Paperwork.money(t.tips)}" else "") + " · billable ${Paperwork.money(t.billable)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { PaperFiles.shareCsv(context, "expenses-$period.csv", rows) }) { Text("⬇ CSV") }
                        OutlinedButton(onClick = { PaperFiles.sharePdf(context, "expense-report-$period.pdf", "Expense report${if (where.isNotBlank()) " · $where" else ""} · $today", rows) }) { Text("🖨 PDF") }
                    }
                    Text("You send the report yourself: nothing leaves the phone without you.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** On Progress: this month's expenses, one tap to the report. */
@Composable
internal fun ExpensesCard(vm: MainViewModel, board: Board) {
    val month = LocalDate.now().toString().take(7)
    val list = Paperwork.all(board).filter { it.e.date.take(7) == month }
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).clickable { vm.expensesOpen.value = true }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("💲  ")
        Column(Modifier.weight(1f)) {
            Text("Expenses", fontWeight = FontWeight.Bold)
            Text(if (list.isEmpty()) "Add one from any cell: ＋ Expense" else "${Paperwork.money(list.sumOf { it.e.amount })} this month · ${list.size} receipt${if (list.size > 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Report ›", style = MaterialTheme.typography.labelMedium)
    }
    // Payments the phone announced (card, wallet, bank app, Interac): sort them in a tap, or turn the reading on.
    val found = board.paymentsFound.size
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, if (found > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        .clickable { vm.paymentsOpen.value = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💳  ")
        Column(Modifier.weight(1f)) {
            Text(if (found > 0) "$found payment${if (found > 1) "s" else ""} from your phone to sort" else "Payments from your phone", fontWeight = FontWeight.Bold)
            Text(if (!board.checks.payments) "Card, Google Wallet, your bank's app, Interac: caught as you pay, no typing." else if (found > 0) "An expense (with a project) or personal: one tap each."
                else "On: new payments show here as you make them.", style = MaterialTheme.typography.bodySmall)
        }
        Text(if (board.checks.payments) "›" else "Turn on ›", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Payments to sort: each one the phone announced becomes an expense (category, a project, billable) or goes away as
 * personal. Read on the phone from the notifications of the apps listed; the bank is never touched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaymentsSheet(vm: MainViewModel) {
    val open by vm.paymentsOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val close = { vm.paymentsOpen.value = false }
    val allowed = com.opslegal.tda.updates.UpdatesListener.allowed(context)
    SoftDialog(
        onDismissRequest = close,
        title = { Text("💳 Payments to sort") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!board.checks.payments || !allowed) {
                    Text("Every payment you make with your phone already shows a notification: Google Wallet, your bank's app (Scotia, Desjardins, RBC, TD…), " +
                        "an Interac e-Transfer email. Turn this on and each one waits here, amount and place filled. You only say: an expense (which project) or personal.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("🔒 Read on this phone only, by simple rules (no AI). Money you receive and refunds are skipped. Nothing is ever done with your bank.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        vm.setPayments(true)
                        if (!allowed) runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (allowed) "Turn it on" else "Turn it on (allow notification access)") }
                } else if (board.paymentsFound.isEmpty()) Text("Nothing to sort. New payments show here as you make them.", style = MaterialTheme.typography.bodyMedium)
                board.paymentsFound.sortedByDescending { it.at }.forEach { p ->
                    var cat by remember(p.id) { mutableStateOf(com.opslegal.tda.core.plan.Payments.guessCategory(p.merchant)) }
                    var project by remember(p.id) { mutableStateOf("") }
                    var note by remember(p.id) { mutableStateOf("") }
                    val areas = com.opslegal.tda.core.plan.Areas.all(board.settings)
                    var area by remember(p.id) { mutableStateOf("") }
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.merchant, fontWeight = FontWeight.SemiBold)
                                Text("${if (p.kind == "transfer") "Interac e-Transfer" else p.app} · ${p.at.take(10)} ${p.at.drop(11).take(5)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(Paperwork.money(p.amount), fontWeight = FontWeight.Bold)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Paperwork.CATEGORIES.forEach { c -> TagChip(cat == c, { cat = c }, label = { Text(c) }) } }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { areas.forEach { a -> TagChip(area == a.id, { area = if (area == a.id) "" else a.id }, label = { Text(a.name) }) } }
                        ProjectPicker(board, project, { project = it }, "For a project or a task? (optional)")
                        CompactField(note, { note = it }, "📝 Note (optional)", Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.keepPayment(p.id, cat, project.trim().ifBlank { null }, project.isNotBlank(), note.trim(), area) }) { Text("✓ Expense") }
                            OutlinedButton(onClick = { vm.dropPayment(p.id) }) { Text("Personal") }
                        }
                    }
                }
                if (board.checks.payments && allowed) TextButton(onClick = { vm.setPayments(false) }) { Text("Stop reading payments") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** ＋ → Document: a task to fill a form. The assistant cleans what it is and writes the name and the intention; any day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentTaskDialog(vm: MainViewModel, board: Board, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    var what by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var intention by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onDismiss,
        title = { Text("A document to fill") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A form, a letter to answer, a document to sign: it gets its own cell, with the file and the list of what to fill.", style = MaterialTheme.typography.bodySmall)
                AddDocButtons(board, { d -> doc = d; if (what.isBlank()) what = d.name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ') }, vm)
                doc?.let { Text("📎 ${it.name}", style = MaterialTheme.typography.bodySmall) }
                CompactField(what, { what = it }, "What it is (in your words)", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
                Button(enabled = !busy && (what.isNotBlank() || doc != null), onClick = {
                    busy = true; error = null
                    scope.launch {
                        val r = vm.documentName(what, doc?.name.orEmpty(), doc?.uri?.takeIf { doc?.kind == "scan" || doc?.kind == "file" })
                        what = r.what; name = r.name; intention = r.intention; busy = false
                    }
                }) { Text("✨ Fill it with the assistant") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                CompactField(name, { name = it }, "Name (what the cell shows)", Modifier.fillMaxWidth())
                CompactField(intention, { intention = it }, "Intention", Modifier.fillMaxWidth())
                ProjectPicker(board, project, { project = it })
                // ✨ A day said in the words ("for Thursday").
                WhenChips(day, { day = it }, com.opslegal.tda.core.plan.Updates.dayInNote("$what $name $intention", today), board = board)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val title = name.ifBlank { if (what.isNotBlank()) "Fill: ${what.trim()}" else "" }
                if (title.isBlank()) { error = "Say what the document is."; return@Button }
                vm.documentTask(title, what, intention, projectName(board, project), doc, day)
                onDismiss()
            }) { Text("Add it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
