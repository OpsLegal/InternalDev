package com.opslegal.tda.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.data.PaperFiles
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun money(x: Double?) = x?.let { "%.2f $".format(Locale.CANADA, it) } ?: "—"

/** The bill in the header, next to the cart: bills due within 7 days; red when one is due within 2 days or late. */
@Composable
internal fun BillButton(vm: MainViewModel) {
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val soon = Bills.soon(board, today).size
    Box {
        IconButton(onClick = { vm.billsOpen.value = true }, modifier = Modifier.size(HeadIconSize)) { Icon(BillIcon, "To pay, $soon due soon") }
        if (soon > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).defaultMinSize(16.dp, 16.dp).clip(CircleShape)
                .background(if (Bills.hot(board, today)) kindColor(TaskKind.DEADLINE) else Navy).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text("$soon", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * To pay: bills from emails (the assistant proposes them as updates), scanned or typed. Never a cell each: one Pay bills
 * cell, the day before the first is due. The assistant reads and reminds; it never pays (Rule 1).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BillsSheet(vm: MainViewModel) {
    val open by vm.billsOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val name = com.opslegal.tda.core.agent.Me.name(board)
    var typed by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<Bill?>(null) }
    var why by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    val read: (android.net.Uri) -> Unit = { uri ->
        why = "Reading…"
        scope.launch {
            val r = vm.readBill(uri)
            draft = Bill(BoardOps.newId(), r?.vendor.orEmpty(), r?.amount, r?.due?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: today.plusDays(7).toString(),
                r?.category ?: Bills.guessCategory(r?.vendor.orEmpty()), r?.note.orEmpty(), "scan")
            why = if (r == null) "It could not be read here (no AI key?): type what you see." else "✨ Read from the photo."
        }
    }
    val scan = rememberScanner { read(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { PaperFiles.keep(context, it); read(it) } }
    val list = Bills.open(board)
    var tab by remember { mutableStateOf("pay") }
    var paying by remember { mutableStateOf<String?>(null) }
    val paid = board.bills.filter { it.paid != null }.sortedByDescending { it.paid }
    val cell = board.tasks.firstOrNull { t -> t.billsCell && t.steps.any { !it.closed } }
    val red = kindColor(TaskKind.DEADLINE)
    val close = { vm.billsOpen.value = false }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = close,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (tab == "pay") "To pay" else "Expenses", Modifier.weight(1f)); if (tab == "pay" && list.isNotEmpty()) Text("${list.size} · ${money(list.sumOf { it.amount ?: 0.0 })}", style = MaterialTheme.typography.labelMedium) } },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Every bill paid becomes an expense: the two lists, one tap apart.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(tab == "pay", { tab = "pay" }, label = { Text("🧾 To pay" + if (list.isNotEmpty()) " · ${list.size}" else "") })
                    TagChip(tab == "exp", { tab = "exp" }, label = { Text("💲 Expenses") })
                }
                if (tab == "exp") { ExpensesByArea(vm, board, today); return@Column }
                Text("🔒 $name reads bills and reminds you. It never pays: you pay, then tap ✓ Paid; it goes to your expenses, in its area and project.", style = MaterialTheme.typography.bodySmall)
                val d = draft
                if (d == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactField(typed, { typed = it }, "Add a bill: “Hydro 142.37 due 28”", Modifier.weight(1f))
                        TextButton(enabled = typed.isNotBlank(), onClick = { draft = Bills.parse(typed, today); why = "Read on your phone."; typed = "" }) { Text("Add") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = scan, modifier = Modifier.weight(1f)) { Text("📷 Scan a bill") }
                        TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("or a photo") }
                    }
                    if (why == "Reading…") Text(why, style = MaterialTheme.typography.bodySmall)
                } else {
                    // Check, then save: what was read shows before anything is kept.
                    var vendor by remember(d.id) { mutableStateOf(d.vendor) }
                    var amount by remember(d.id) { mutableStateOf(d.amount?.let { "%.2f".format(Locale.US, it) }.orEmpty()) }
                    var due by remember(d.id) { mutableStateOf(d.due) }
                    var cat by remember(d.id) { mutableStateOf(d.category) }
                    var auto by remember(d.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$why Check, then save.", style = MaterialTheme.typography.bodySmall)
                        CompactField(vendor, { vendor = it }, "Who", Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactField(amount, { amount = it }, "Amount ($)", Modifier.weight(1f))
                            DateField("Due", due, { due = it }, Modifier.weight(1f))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Bills.categories.forEach { c -> TagChip(cat == c, { cat = c }, label = { Text(c) }) } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(auto, { auto = it }); Text("Paid automatically: only a reminder to check", style = MaterialTheme.typography.bodySmall) }
                        Row { Box(Modifier.weight(1f)); TextButton(onClick = { draft = null }) { Text("Cancel") }
                            Button(enabled = vendor.isNotBlank() && runCatching { LocalDate.parse(due) }.isSuccess, onClick = {
                                vm.addBill(d.copy(vendor = vendor.trim(), amount = amount.replace(',', '.').toDoubleOrNull(), due = due, category = cat, auto = auto)); draft = null
                            }) { Text("Save") } }
                    }
                }
                if (cell != null) {
                    val day = cell.steps.firstOrNull { !it.closed }?.date?.let(LocalDate::parse)
                    Text("🧾 Pay bills cell${day?.let { " on " + vm.dayName(it) } ?: ""}. Marking it done marks its bills paid.", style = MaterialTheme.typography.bodySmall)
                } else if (list.any { it.cell == null && it.due <= today.plusDays(10).toString() }) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (Bills.hot(board, today)) "Due soon. " else "") + "Pay them all in one cell, the day before the first is due.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.planBills() }) { Text("Plan a cell") }
                }
                if (list.isEmpty() && draft == null) Text("Nothing to pay. Add one, scan it, or tell $name: “Vidéotron 96.53 on the 20th”.", style = MaterialTheme.typography.bodySmall)
                list.forEach { b ->
                    val days = ChronoUnit.DAYS.between(today, LocalDate.parse(b.due))
                    val whenText = when { days < 0 -> "${-days} day${if (days < -1) "s" else ""} late"; days == 0L -> "due today"; days == 1L -> "due tomorrow"; else -> "due ${vm.dayName(LocalDate.parse(b.due))}" }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.vendor + (if (b.auto) " · auto-pay" else ""), fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(whenText, b.category, b.note.ifBlank { null }, "in your Pay bills cell".takeIf { b.cell != null }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = if (days < 0) red else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(b.amount), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                        if (paying != b.id) Button(onClick = { paying = b.id }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text("✓ Paid") }
                    }
                    // Paid: where it belongs (an area, a project) before it joins the expenses.
                    if (paying == b.id) {
                        val areas = com.opslegal.tda.core.plan.Areas.all(board.settings)
                        var area by remember(b.id) { mutableStateOf(b.area.ifBlank { (areas.firstOrNull { !it.work } ?: areas.first()).id }) }
                        var project by remember(b.id) { mutableStateOf(b.project) }
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("For which part of your life?", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { areas.forEach { a -> TagChip(area == a.id, { area = a.id }, label = { Text(a.name) }) } }
                            ProjectPicker(board, project, { project = it }, "For a project or a task? (optional)")
                            Row { Box(Modifier.weight(1f)); TextButton(onClick = { paying = null }) { Text("Cancel") }
                                Button(onClick = { vm.payBill(b.id, area, project); paying = null }) { Text("✓ Paid · to expenses") } }
                        }
                    }
                }
                if (paid.isNotEmpty()) {
                    var showPaid by remember { mutableStateOf(false) }
                    TextButton(onClick = { showPaid = !showPaid }) { Text("Paid (${paid.size}) ${if (showPaid) "▴" else "▾"}") }
                    if (showPaid) paid.take(20).forEach { b ->
                        Row { Text("${b.vendor} · ${money(b.amount)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("paid ${b.paid}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.removeBill(b.id) }) { Text("×") } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/**
 * This month's expenses by area (a company, Real estate, Personal…), each with its total and where it belongs
 * (project or task). The full report (periods, CSV, PDF) is one tap away.
 */
@Composable
private fun ExpensesByArea(vm: MainViewModel, board: com.opslegal.tda.core.model.Board, today: LocalDate) {
    val month = today.toString().take(7)
    val areas = com.opslegal.tda.core.plan.Areas.all(board.settings)
    val rows = com.opslegal.tda.core.plan.Paperwork.all(board).filter { it.e.date.take(7) == month }
    fun areaOf(r: com.opslegal.tda.core.plan.Paperwork.Row): String {
        r.e.area.takeIf { it.isNotBlank() }?.let { id -> areas.firstOrNull { it.id == id }?.let { return it.name } }
        com.opslegal.tda.core.plan.BoardOps.findProject(board, r.where)?.let { return com.opslegal.tda.core.plan.Areas.ofProject(board, it.name).name }
        board.tasks.firstOrNull { it.title == r.where }?.let { return com.opslegal.tda.core.plan.Areas.of(board, it).name }
        return "Not sorted"
    }
    if (rows.isEmpty()) Text("No expense this month yet. A paid bill, a payment from your phone or ＋ Expense on any cell lands here.", style = MaterialTheme.typography.bodySmall)
    rows.groupBy(::areaOf).toList().sortedByDescending { (_, l) -> l.sumOf { it.e.amount } }.forEach { (area, l) ->
        Row(Modifier.padding(top = 4.dp)) { Text(area, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(money(l.sumOf { it.e.amount }), fontWeight = FontWeight.Bold) }
        l.sortedByDescending { it.e.date }.forEach { r ->
            Row {
                Column(Modifier.weight(1f)) {
                    Text(r.e.vendor.ifBlank { r.e.category }, style = MaterialTheme.typography.bodyMedium)
                    Text(listOf(r.e.date.drop(5), r.where.takeIf { it != "No project" && it.isNotBlank() }, r.e.category).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(money(r.e.amount), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (board.paymentsFound.isNotEmpty()) OutlinedButton(onClick = { vm.billsOpen.value = false; vm.paymentsOpen.value = true }) { Text("💳 ${board.paymentsFound.size} to sort") }
        OutlinedButton(onClick = { vm.billsOpen.value = false; vm.expensesOpen.value = true }) { Text("Full report ›") }
    }
}
