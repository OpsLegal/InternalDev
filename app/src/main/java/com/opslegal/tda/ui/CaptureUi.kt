package com.opslegal.tda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.core.plan.ReceiptRules
import com.opslegal.tda.core.plan.Receipts
import com.opslegal.tda.core.plan.Rules
import kotlinx.coroutines.launch
import java.time.LocalDate

/** ✏️ Jot (from the widget): one line, said or typed, into On my mind, unsorted. Jimmy's list sorts it later. */
@Composable
internal fun JotDialog(vm: MainViewModel) {
    val open by vm.jotOpen.collectAsStateWithLifecycle()
    if (!open) return
    var text by remember { mutableStateOf("") }
    val close = { vm.jotOpen.value = false }
    SoftDialog(
        onDismissRequest = close,
        keepOpen = text.isNotBlank(),
        title = { Text("✏️ Jot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("What's on your mind?") }, maxLines = 3)
                    DictateButton { said -> text = (text.trim() + " " + said).trim() }
                }
                TextButton(onClick = { close(); vm.receiptFor.value = "" }) { Text("📷 A receipt") }
                Text("It goes to On my mind, not sorted yet. Sort it later in Jimmy's list.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { vm.jot(text); close() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/**
 * 📷 A paper receipt: the phone reads it (store, total, date; no AI), finds the payment or expense it belongs to (same
 * amount, within 3 days) and links it; otherwise it becomes a new expense. Area and project are pre-selected (the
 * project the store's receipts went to last time).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptSheet(vm: MainViewModel) {
    val target by vm.receiptFor.collectAsStateWithLifecycle()
    val forWhat = target ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var photo by remember(forWhat) { mutableStateOf<android.net.Uri?>(null) }
    var reading by remember(forWhat) { mutableStateOf(false) }
    var read by remember(forWhat) { mutableStateOf<ReceiptRules.Read?>(null) }
    var vendor by remember(forWhat) { mutableStateOf("") }
    var total by remember(forWhat) { mutableStateOf("") }
    var date by remember(forWhat) { mutableStateOf(LocalDate.now().toString()) }
    var area by remember(forWhat) { mutableStateOf<String?>(null) }
    var project by remember(forWhat) { mutableStateOf<String?>(null) }
    var noLink by remember(forWhat) { mutableStateOf(false) }
    var thing by remember(forWhat) { mutableStateOf<String?>(null) }
    val close = { vm.receiptFor.value = null }
    // The payment or expense it was opened from, when it was.
    val fixedPay = forWhat.removePrefix("pay:").takeIf { forWhat.startsWith("pay:") }?.let { id -> board.paymentsFound.firstOrNull { it.id == id } }
    val fixedExp = forWhat.removePrefix("exp:").takeIf { forWhat.startsWith("exp:") }?.let { id -> Paperwork.all(board).firstOrNull { it.e.id == id } }
    fun readIt(uri: android.net.Uri) {
        photo = uri; reading = true
        scope.launch {
            val r = vm.readReceiptText(uri)
            read = r
            if (r != null) {
                if (vendor.isBlank()) vendor = r.vendor
                if (total.isBlank() && r.total > 0) total = "%.2f".format(java.util.Locale.US, r.total)
                r.date?.let { date = it }
            }
            reading = false
        }
    }
    val scan = rememberScanner { uri -> readIt(uri) }
    LaunchedEffect(forWhat) {
        fixedPay?.let { vendor = it.merchant; total = "%.2f".format(java.util.Locale.US, it.amount); date = it.at.take(10) }
        fixedExp?.let { vendor = it.e.vendor; total = "%.2f".format(java.util.Locale.US, it.e.amount); date = it.e.date }
        // From 📷 Capture: the photo is already taken (read as a receipt); otherwise the camera opens.
        if (forWhat.startsWith("uri:")) readIt(android.net.Uri.parse(forWhat.removePrefix("uri:")))
        else if (forWhat.startsWith("asset:")) { thing = forWhat.removePrefix("asset:"); scan() }
        else scan()
    }
    val amount = total.replace(',', '.').toDoubleOrNull()
    val match: Receipts.Match? = when {
        noLink -> null
        fixedPay != null -> Receipts.Match.Payment(fixedPay)
        fixedExp != null -> Receipts.Match.Spent(fixedExp.e, fixedExp.where)
        amount != null -> Receipts.match(board, amount, date, vendor)
        else -> null
    }
    val areas = Areas.all(board.settings)
    val guessedArea = (match as? Receipts.Match.Spent)?.e?.area?.ifBlank { null } ?: Rules.store(board, vendor)?.second?.ifBlank { null }
        ?: Areas.guess(board, vendor)
    val learnedProject = Receipts.project(board, vendor)
    val pickedArea = area ?: guessedArea
    val pickedProject = project ?: learnedProject ?: (match as? Receipts.Match.Spent)?.where?.takeIf { it != "No project" }.orEmpty()
    SoftDialog(
        onDismissRequest = close,
        keepOpen = photo != null,
        title = { Text("📷 Receipt") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (photo == null) {
                    Text("Take a photo of the receipt: the phone reads it, no AI.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = scan) { Text("📷 Take the photo") }
                } else {
                    Text(if (reading) "Reading…" else if (read == null) "I couldn't read it well: check the store and total." else "✓ Read on your phone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(vendor, { vendor = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Store") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(total, { total = it }, Modifier.weight(1f), singleLine = true, label = { Text("Total") })
                        OutlinedTextField(date, { date = it }, Modifier.weight(1f), singleLine = true, label = { Text("Date") })
                    }
                    if (match != null) {
                        val (what, where) = when (match) {
                            is Receipts.Match.Payment -> "${match.p.merchant} · ${Paperwork.money(match.p.amount)} · ${match.p.at.take(10)}" to "your payment"
                            is Receipts.Match.Spent -> "${match.e.vendor} · ${Paperwork.money(match.e.amount)} · ${match.e.date}" to "your expense"
                        }
                        Text("✓ Matches $where: $what", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (fixedPay == null && fixedExp == null) TextButton(onClick = { noLink = true }) { Text("Not this one: a new expense", fontSize = 12.sp) }
                    } else if (amount != null) Text("No payment matches: it will be a new expense.", style = MaterialTheme.typography.bodySmall)
                    Text("Area", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        areas.forEach { a -> PickChip(a.id == pickedArea, a.name, dot = areaColor(board, a.id)) { area = a.id } }
                    }
                    Text("Project" + if (learnedProject != null && project == null) " · ✨ Like last time" else "", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        PickChip(pickedProject.isBlank(), "None") { project = "" }
                        board.projects.take(14).forEach { p -> PickChip(p.name == pickedProject, p.name) { project = p.name; thing = "" } }
                    }
                    // A thing you own: the expense goes with it (the car, a rental unit, the boat…), pre-selected from the store's words.
                    val pickedThing = thing ?: com.opslegal.tda.core.plan.Assets.guess(board, vendor)?.id.orEmpty()
                    if (board.assets.isNotEmpty()) {
                        Text("Thing", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            PickChip(pickedThing.isBlank(), "None") { thing = "" }
                            board.assets.forEach { a -> PickChip(a.id == pickedThing, com.opslegal.tda.core.plan.Assets.label(a)) { thing = a.id; project = "" } }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = photo != null && !reading && amount != null && vendor.isNotBlank(), onClick = {
                val t = thing ?: com.opslegal.tda.core.plan.Assets.guess(board, vendor)?.id.orEmpty()
                vm.saveReceipt(read, amount ?: 0.0, vendor.trim(), date.trim(), photo.toString(), match, pickedArea, if (t.isNotBlank()) "" else pickedProject, t)
                close()
            }) { Text(if (match != null) "📎 Link it" else "Save expense") }
        },
        dismissButton = {
            Row {
                if (photo != null) OutlinedButton(onClick = scan) { Text("Retake") }
                TextButton(onClick = close) { Text("Cancel") }
            }
        },
    )
}


/**
 * 📷 Capture (widget): one button for any paper. The phone reads it (no AI): a total whose taxes add up = a receipt (then
 * it is matched to its payment); otherwise a document, filed with a thing, a project, a task, or just kept.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureSheet(vm: MainViewModel) {
    val target by vm.captureFor.collectAsStateWithLifecycle()
    val forWhat = target ?: return
    val board by vm.board.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var photo by remember(forWhat) { mutableStateOf<android.net.Uri?>(null) }
    var reading by remember(forWhat) { mutableStateOf(false) }
    var kind by remember(forWhat) { mutableStateOf("doc") }
    var name by remember(forWhat) { mutableStateOf("") }
    var where by remember(forWhat) { mutableStateOf<String?>(forWhat.removePrefix("asset:").takeIf { forWhat.startsWith("asset:") }?.let { "asset:$it" }) }
    var guessed by remember(forWhat) { mutableStateOf("") }
    var todo by remember(forWhat) { mutableStateOf("") }
    val close = { vm.captureFor.value = null }
    val scan = rememberScanner { uri ->
        photo = uri; reading = true
        scope.launch {
            val text = vm.readText(uri).orEmpty()
            val r = text.takeIf { it.isNotBlank() }?.let { ReceiptRules.read(it) }
            kind = if (r != null && r.sure) "receipt" else "doc"
            name = text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 4 && it.any(Char::isLetter) }?.take(60) ?: "Scan ${LocalDate.now()}"
            val a = com.opslegal.tda.core.plan.Assets.guess(board, text)
            val p = board.projects.firstOrNull { pr -> Paperwork.fold(pr.name).split(" ").firstOrNull { it.length >= 4 }?.let { Paperwork.fold(text).contains(it) } == true }
            guessed = a?.let { "asset:${it.id}" } ?: p?.name.orEmpty()
            if (where == null) where = guessed
            reading = false
        }
    }
    LaunchedEffect(forWhat) { scan() }
    val tasks = board.tasks.filter { !it.isProject && it.steps.any { s -> !s.closed } }.takeLast(2)
    val projects = board.projects.filter { com.opslegal.tda.core.plan.BoardOps.projectTask(board, it.name) != null }.take(3)
    val picked = where ?: guessed
    SoftDialog(
        onDismissRequest = close, keepOpen = photo != null,
        title = { Text("📷 Capture") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (photo == null) {
                    Text("A receipt or any document: the phone reads it, no AI.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = scan) { Text("📷 Take the photo") }
                    return@Column
                }
                if (reading) { Text("Reading…", style = MaterialTheme.typography.bodySmall); return@Column }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PickChip(kind == "receipt", "🧾 A receipt (expense)") { kind = "receipt" }
                    PickChip(kind == "doc", "📄 A document") { kind = "doc" }
                }
                if (kind == "receipt") { Text("The receipt screen opens: matched to its payment, area and project pre-selected.", style = MaterialTheme.typography.bodySmall); return@Column }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                Text("Goes with", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    board.assets.forEach { a -> val k = "asset:${a.id}"; PickChip(picked == k, com.opslegal.tda.core.plan.Assets.label(a) + if (k == guessed) " ✨" else "") { where = k } }
                    projects.forEach { p -> PickChip(picked == p.name, "📁 ${p.name}" + if (p.name == guessed) " ✨" else "") { where = p.name } }
                    tasks.forEach { t -> val k = "task:${t.id}"; PickChip(picked == k, "📋 ${t.title.take(28)}") { where = k } }
                    PickChip(picked.isBlank(), "🗂 Just keep it") { where = "" }
                }
                OutlinedTextField(todo, { todo = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Something to do with it? (optional)", fontSize = 12.sp) })
                Text("Saved on your phone (and your drive if connected). Nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(enabled = photo != null && !reading, onClick = {
                val uri = photo ?: return@Button
                if (kind == "receipt") { close(); vm.receiptFor.value = "uri:$uri"; return@Button }
                vm.saveCapturedDoc(uri, name, picked, todo); close()
            }) { Text(if (kind == "receipt") "Next →" else "Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
