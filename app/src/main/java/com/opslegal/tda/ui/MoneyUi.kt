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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.Paperwork
import java.time.LocalDate

/** One line of the page: a bill still to pay, or money already spent. */
private data class MoneyRow(val id: String, val toPay: Boolean, val vendor: String, val amount: Double, val date: String, val area: String,
    val where: String, val category: String, val receipt: Boolean)

/**
 * 💲 Expenses, built by itself: Paid for the period and To pay (open bills), totals per area, live search (the Progress
 * search box), To pay first. Tap a row's area to change it (learned for that vendor). 📎 a receipt is linked, 📷 add one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpensesPage(vm: MainViewModel, board: Board, query: String) {
    val today = LocalDate.now()
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf("month") }
    var from by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var to by rememberSaveable { mutableStateOf(today.toString()) }
    var areasOn by rememberSaveable { mutableStateOf(listOf<String>()) }
    var noReceipt by rememberSaveable { mutableStateOf(false) }
    var areaRow by rememberSaveable { mutableStateOf<String?>(null) }
    val areas = Areas.all(board.settings)
    val start = when (period) {
        "month" -> today.withDayOfMonth(1); "3m" -> today.minusMonths(3); "6m" -> today.minusMonths(6); "year" -> today.withDayOfYear(1)
        else -> runCatching { LocalDate.parse(from) }.getOrDefault(today.withDayOfMonth(1))
    }.toString()
    val end = if (period == "range") to else "9999"
    val home = (areas.firstOrNull { !it.work } ?: areas.first()).id
    val paid = Paperwork.all(board).filter { it.e.date.take(10) in start..end }.map { r ->
        MoneyRow(r.e.id, false, r.e.vendor.ifBlank { r.e.category }, r.e.amount, r.e.date, r.e.area.ifBlank { Areas.learned(board, r.e.vendor)?.id ?: home },
            r.where, r.e.category, r.e.photo.isNotBlank())
    }
    val open = Bills.open(board).map { b ->
        MoneyRow(b.id, true, b.vendor, b.amount ?: 0.0, b.due, b.area.ifBlank { Areas.learned(board, b.vendor)?.id ?: home }, b.project.ifBlank { "No project" }, b.category, false)
    }
    fun areaName(id: String) = areas.firstOrNull { it.id == id }?.name.orEmpty()
    fun hit(r: MoneyRow) = query.isBlank() || Paperwork.fold(query).split(Regex("\\s+")).filter { it.isNotBlank() }.all { w ->
        Paperwork.fold(listOf(r.vendor, r.category, r.where, areaName(r.area), "%.2f".format(java.util.Locale.US, r.amount), r.date).joinToString(" ")).contains(w)
    }
    val base = (open + paid).filter { hit(it) && (areasOn.isEmpty() || it.area in areasOn) }
    val rows = base.filter { !noReceipt || (!it.toPay && !it.receipt) }
    val shape = RoundedCornerShape(12.dp)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(10.dp)) {
                Text("Paid", style = MaterialTheme.typography.labelMedium)
                Text(Paperwork.money(base.filter { !it.toPay }.sumOf { it.amount }), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f).border(1.dp, kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE), shape).padding(10.dp)) {
                Text("To pay", style = MaterialTheme.typography.labelMedium)
                Text(Paperwork.money(base.filter { it.toPay }.sumOf { it.amount }), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        // Per area, for what is shown.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            areas.forEach { a ->
                val n = base.filter { !it.toPay && it.area == a.id }.sumOf { it.amount }
                if (n > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(areaColor(board, a.id)))
                    Text("${a.name} ${Paperwork.money(n)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("month" to "This month", "3m" to "3 months", "6m" to "6 months", "year" to "Year", "range" to "📅 Period").forEach { (k, l) ->
                PickChip(period == k, l) { period = k }
            }
        }
        if (period == "range") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun pick(v: String, set: (String) -> Unit) {
                val d = runCatching { LocalDate.parse(v) }.getOrDefault(today)
                android.app.DatePickerDialog(context, { _, y, m, day -> set(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }
            Text("From 📅 $from", Modifier.clickable { pick(from) { from = it } }.padding(4.dp), style = MaterialTheme.typography.bodyMedium)
            Text("to 📅 $to", Modifier.clickable { pick(to) { to = it } }.padding(4.dp), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PickChip(areasOn.isEmpty(), "All") { areasOn = emptyList() }
            areas.forEach { a -> PickChip(a.id in areasOn, a.name, dot = areaColor(board, a.id)) { areasOn = if (a.id in areasOn) areasOn - a.id else areasOn + a.id } }
            PickChip(noReceipt, "🧾 No receipt · ${base.count { !it.toPay && !it.receipt }}") { noReceipt = !noReceipt }
        }
        if (rows.isEmpty()) Text(if (query.isNotBlank()) "Nothing matches “$query”." else "Nothing for this period.", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp))
        rows.forEach { r ->
            val c = areaColor(board, r.area)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, c, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (r.toPay) "🧾" else if (r.receipt) "📎" else "📷", Modifier.clip(CircleShape).clickable(enabled = !r.toPay && !r.receipt) { vm.receiptFor.value = "exp:" + r.id }.padding(2.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.vendor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable { areaRow = if (areaRow == r.id) null else r.id }) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                            Text("${areaName(r.area)} ▾ · ${if (r.toPay) "due " else ""}${r.date.take(10)}${if (r.where != "No project") " · ${r.where}" else ""}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(Paperwork.money(r.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = if (r.toPay) kindColor(com.opslegal.tda.core.model.TaskKind.DEADLINE) else androidx.compose.ui.graphics.Color.Unspecified)
                }
                if (areaRow == r.id) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    areas.forEach { a ->
                        PickChip(a.id == r.area, a.name, dot = areaColor(board, a.id)) {
                            areaRow = null
                            if (r.toPay) vm.setBillArea(r.id, a.id)
                            else vm.sortExpense(r.id, a.id, r.where.takeIf { it != "No project" }, null)
                            if (a.id != r.area) vm.learnAreaWord(r.vendor, a.id)
                        }
                    }
                }
            }
        }
    }
}
