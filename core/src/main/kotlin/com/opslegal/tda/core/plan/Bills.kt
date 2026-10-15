package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Effort
import java.time.LocalDate
import java.util.Locale

/**
 * To pay: bills read from emails, scanned or typed. They never jam the table: one "Pay bills" cell, the work day before
 * the first is due, holds those due soon. The assistant reads and reminds; it never pays (Rule 1): the user taps ✓ Paid.
 */
object Bills {
    val categories = listOf("Home", "Phone & internet", "Car", "Insurance", "Taxes", "Cards & loans", "Kids", "Business", "Other")

    fun open(board: Board): List<Bill> = board.bills.filter { it.paid == null }.sortedBy { it.due }

    /** Due within 7 days, or late: what the header badge counts. */
    fun soon(board: Board, today: LocalDate): List<Bill> = open(board).filter { it.due <= today.plusDays(7).toString() }

    /** Due within 2 days or late: the badge turns red. */
    fun hot(board: Board, today: LocalDate): Boolean = open(board).any { it.due <= today.plusDays(2).toString() }

    fun guessCategory(vendor: String): String = vendor.lowercase(Locale.ROOT).let { v ->
        when {
            Regex("hydro|energir|gaz|electric|water|rent|loyer|condo").containsMatchIn(v) -> "Home"
            Regex("videotron|vidéotron|bell|rogers|fizz|telus|koodo|internet|mobile").containsMatchIn(v) -> "Phone & internet"
            Regex("saaq|car|auto|garage").containsMatchIn(v) -> "Car"
            Regex("insur|assur|desjardins|intact").containsMatchIn(v) -> "Insurance"
            Regex("tax|impot|impôt|revenu|municipal").containsMatchIn(v) -> "Taxes"
            Regex("visa|mastercard|amex|loan|prêt|pret|card").containsMatchIn(v) -> "Cards & loans"
            else -> "Other"
        }
    }

    /** "Hydro 142.37 due 28" or « Vidéotron 96,53 le 20 »: vendor, amount and due day, read on the phone (no AI). */
    fun parse(text: String, today: LocalDate): Bill {
        val t = text.trim()
        val money = Regex("(\\d+[.,]\\d{1,2})\\s*\\$?").find(t) ?: Regex("(\\d{2,})\\s*\\$").find(t)
        val amount = money?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val rest = if (money != null) t.removeRange(money.range) else t
        val day = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b").findAll(rest).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }
        val due = when {
            Regex("(?i)\\b(today|aujourd)").containsMatchIn(rest) -> today
            Regex("(?i)\\b(tomorrow|demain)").containsMatchIn(rest) -> today.plusDays(1)
            day != null -> runCatching { today.withDayOfMonth(day) }.getOrNull()?.let { if (it.isBefore(today)) runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull() else it }
            else -> null
        }
        val vendor = rest.replace(Regex("(?i)\\b(due|on|le|pour|by|avant|before|today|tomorrow|demain|aujourd'?hui)\\b"), " ")
            .replace(Regex("\\b\\d{1,2}(st|nd|rd|th)?\\b"), " ").replace(Regex("[$€]"), " ").replace(Regex("\\s+"), " ").trim()
        return Bill(BoardOps.newId(), vendor.ifBlank { "Bill" }, amount, (due ?: today.plusDays(7)).toString(), guessCategory(vendor))
    }

    fun add(board: Board, bill: Bill): Board = board.copy(bills = board.bills + bill)

    /**
     * Paid (by the user; the app never pays): every bill paid becomes an expense, in its area and on its project when
     * the user says ([area], [project] override what the bill had).
     */
    fun markPaid(board: Board, id: String, today: LocalDate, area: String? = null, project: String? = null): Board {
        val b = board.bills.firstOrNull { it.id == id && it.paid == null } ?: return board
        val a = area ?: b.area; val p = (project ?: b.project).trim()
        val paid = board.copy(bills = board.bills.map { if (it.id == id) it.copy(paid = today.toString(), area = a, project = p) else it })
        val e = com.opslegal.tda.core.model.Expense(BoardOps.newId(), b.amount ?: 0.0, vendor = b.vendor, date = today.toString(),
            category = expenseCategory(b.category), note = listOf("Bill · ${b.category}", b.note).filter { it.isNotBlank() }.joinToString(" · "), area = a)
        return Paperwork.addExpense(paid, e, null, p.ifBlank { null })
    }

    /** A bill's category in the expense report's words. */
    private fun expenseCategory(c: String) = when (c) { "Car" -> "Travel"; "Business" -> "Supplies"; "Taxes", "Insurance", "Cards & loans" -> "Fees"; else -> "Other" }

    /**
     * Already paid: an expense, or a payment seen on the phone (an Interac transfer, a card), of the same amount (to the
     * cent) or to the same name, in the last 30 days. A bill found again is then not proposed.
     */
    fun alreadyPaid(board: Board, vendor: String, amount: Double?, today: LocalDate): Boolean {
        val since = today.minusDays(30).toString()
        val v = vendor.lowercase().take(6)
        fun near(x: Double) = amount != null && kotlin.math.abs(x - amount) < 0.011
        return Paperwork.all(board).any { it.e.date >= since && (near(it.e.amount) || (v.length >= 4 && it.e.vendor.lowercase().contains(v))) } ||
            board.paymentsFound.any { it.at.take(10) >= since && (near(it.amount) || (v.length >= 4 && it.merchant.lowercase().contains(v))) } ||
            board.bills.any { it.paid != null && it.paid >= since && it.vendor.lowercase().take(6) == v }
    }

    fun remove(board: Board, id: String): Board = board.copy(bills = board.bills.filter { it.id != id })

    /** The "Pay bills" cell holding the bills due within 10 days, on the plannable day before the first due date. */
    fun planCell(board: Board, today: LocalDate): Pair<Board, LocalDate?> {
        val list = open(board).filter { it.cell == null && it.due <= today.plusDays(10).toString() }
        if (list.isEmpty()) return board to null
        val first = LocalDate.parse(list.first().due)
        var day = first.minusDays(1)
        while (day.isAfter(today) && Areas.all(board.settings).none { Areas.canPlan(it, day, board.settings) }) day = day.minusDays(1)
        if (day.isBefore(today)) day = today
        val total = list.sumOf { it.amount ?: 0.0 }
        val spec = BoardOps.NewTask(
            title = "Pay bills · ${list.size}",
            description = "To pay: " + list.joinToString(", ") { b -> b.vendor + (b.amount?.let { " %.2f $".format(Locale.CANADA, it) } ?: "") + " (due ${b.due})" } +
                ". Total %.2f $.".format(Locale.CANADA, total),
            effort = Effort.LIGHT, serve = mapOf("Organized" to 2),
        )
        val (placed, task, _) = BoardOps.addTaskOn(board, spec, day, today)
        val withFlag = BoardOps.updateTask(placed, task.id) { it.copy(billsCell = true) }
        val ids = list.map { it.id }.toSet()
        val date = withFlag.tasks.first { it.id == task.id }.steps.firstOrNull()?.date?.let(LocalDate::parse)
        return withFlag.copy(bills = withFlag.bills.map { if (it.id in ids) it.copy(cell = task.id) else it }) to date
    }

    /** The "Pay bills" cell done: its bills are paid. */
    fun cellDone(board: Board, taskId: String, today: LocalDate): Board =
        board.bills.filter { it.cell == taskId && it.paid == null }.fold(board) { acc, b -> markPaid(acc, b.id, today) }
}
