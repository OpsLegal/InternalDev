package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Bill
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.plan.Areas
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Bills
import com.opslegal.tda.core.plan.Paperwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * The two money checks of the invoice sheet. [Found]: invoices and receipts in the last 90 days of email and messages
 * that the app does not hold yet (a double check). [unsorted]: expenses without an area, each with where it surely
 * belongs when that is clear. Nothing is added or moved until the user taps.
 */
object MoneyCheck {
    const val DAYS = 90L

    /** [paid]: a receipt (goes to the expenses); else a bill to pay. [area] (an id) and [project] only when clear. */
    data class Found(val vendor: String, val amount: Double, val date: String, val paid: Boolean, val due: String?, val note: String,
        val area: String = "", val project: String = "", val from: String = "", val category: String = "Other")

    private val MONEY = Regex("""(?i)(invoice|facture|receipt|re[çc]u|payment|paiement|pay[ée]|interac|virement|e-?transfer|order|commande|achat|purchase|bill|relev[ée]|statement|total|montant|amount|\$\s?\d|\d[.,]\d{2}\s?\$)""")

    /** Only what speaks of money goes to the assistant: fewer words, a cleaner answer. */
    fun relevant(items: List<Incoming>): List<Incoming> = items.filter { MONEY.containsMatchIn(it.text) || MONEY.containsMatchIn(it.from) }

    fun prompt(board: Board, items: List<Incoming>, today: LocalDate): String = buildString {
        val since = today.minusDays(DAYS).toString()
        appendLine("Today is $today. Find every INVOICE (a bill to pay) and RECEIPT (already paid: a purchase, an Interac e-Transfer sent, a card payment) in these emails and messages of the last $DAYS days.")
        appendLine("Leave out: newsletters, ads, quotes not accepted, money RECEIVED by the user, refunds, balance or limit alerts.")
        val areas = Areas.all(board.settings)
        appendLine("The user's areas: " + areas.joinToString("; ") { a -> "${a.id} = ${a.name}" + if (a.words.isNotEmpty()) " (${a.words.joinToString()})" else "" } +
            ". \"area\" = an id ONLY when the document clearly belongs to one (renovation materials → real estate; a company's name → that company), else null.")
        val projects = board.projects.map { it.name }
        if (projects.isNotEmpty()) appendLine("The user's projects: ${projects.take(40).joinToString()}. \"project\" = the exact name ONLY when clear, else null.")
        val have = Paperwork.all(board).filter { it.e.date >= since }.map { "${it.e.date} ${it.e.vendor} ${it.e.amount}" } +
            board.bills.map { "${it.due} ${it.vendor} ${it.amount ?: ""}${if (it.paid != null) " paid" else " to pay"}" } + board.paymentsFound.map { "${it.at.take(10)} ${it.merchant} ${it.amount}" }
        if (have.isNotEmpty()) appendLine("ALREADY IN THE APP (leave these out, same vendor and amount): ${have.take(150).joinToString("; ")}")
        appendLine("ITEMS:")
        items.forEachIndexed { i, it -> appendLine("[$i] ${it.at.take(10)} · ${it.source} · ${it.from}: ${it.text.take(400)}") }
        append("Reply with only {\"found\": [{\"item\": number, \"vendor\": string, \"amount\": number, \"date\": \"YYYY-MM-DD\", \"paid\": boolean, " +
            "\"due\": \"YYYY-MM-DD\"|null, \"category\": one of ${Bills.categories.joinToString()}, \"note\": string, \"area\": string|null, \"project\": string|null}]}.")
    }

    fun parse(reply: String, board: Board, items: List<Incoming>, today: LocalDate): List<Found> {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        val areas = Areas.all(board.settings).map { it.id }.toSet(); val projects = board.projects.map { it.name }.toSet()
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        return runCatching { root["found"]!!.jsonArray }.getOrDefault(emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val vendor = o.s("vendor") ?: return@mapNotNull null
            val amount = o["amount"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 && it < 1_000_000 } ?: return@mapNotNull null
            val date = o.s("date")?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: today.toString()
            val sure = Bills.sureWhere(board, vendor)
            Found(vendor, amount, date, o["paid"]?.jsonPrimitive?.contentOrNull == "true", o.s("due")?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess },
                o.s("note").orEmpty(), o.s("area")?.takeIf { it in areas } ?: sure?.first.orEmpty(), o.s("project")?.takeIf { it in projects } ?: sure?.second.orEmpty(),
                o["item"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { items.getOrNull(it)?.from }.orEmpty(), o.s("category")?.takeIf { it in Bills.categories } ?: "Other")
        }.filterNot { have(board, it) }.distinctBy { it.vendor.lowercase() + it.amount }
    }

    /** Already in the app: an expense, a bill or a payment seen on the phone of the same amount (to the cent), same vendor or within 3 days. */
    fun have(board: Board, f: Found): Boolean {
        val v = Paperwork.fold(f.vendor).take(6)
        fun near(x: Double?) = x != null && kotlin.math.abs(x - f.amount) < 0.011
        fun close(d: String) = runCatching { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(d.take(10)), LocalDate.parse(f.date))) <= 3 }.getOrDefault(false)
        fun same(name: String) = v.length >= 4 && Paperwork.fold(name).contains(v)
        return Paperwork.all(board).any { near(it.e.amount) && (same(it.e.vendor) || close(it.e.date)) } ||
            board.bills.any { near(it.amount) && (same(it.vendor) || close(it.due)) } ||
            board.paymentsFound.any { near(it.amount) && (same(it.merchant) || close(it.at)) }
    }

    /** A found receipt to the expenses, or a found invoice to To pay. */
    fun add(board: Board, f: Found, area: String, project: String?, paid: Boolean): Board =
        if (paid) Bills.addPaid(board, f.vendor, f.amount, f.date, f.category, listOf(f.note, f.from.takeIf { it.isNotBlank() }?.let { "from $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), area, project)
        else Bills.add(board, Bill(BoardOps.newId(), f.vendor, f.amount, f.due ?: f.date, f.category, f.note, "email", area = area, project = project.orEmpty()))

    /** An expense to sort: where it is now, and where it surely belongs when that is clear ([area] id, [project]). */
    data class ToSort(val id: String, val vendor: String, val amount: Double, val date: String, val note: String, val area: String, val project: String, val sure: Boolean)

    /** Every expense without an area (and not in a project or task), newest first, with a sure place when there is one. */
    fun unsorted(board: Board): List<ToSort> {
        val areas = Areas.all(board.settings)
        return Paperwork.all(board).filter { it.e.area.isBlank() && it.where == "No project" }.map { r ->
            val sure = Bills.sureWhere(board, r.e.vendor)?.takeIf { it.first.isNotBlank() }
            val byWords = Areas.byWords(areas, "${r.e.vendor} ${r.e.note}")?.id
            ToSort(r.e.id, r.e.vendor.ifBlank { r.e.category }, r.e.amount, r.e.date, r.e.note, sure?.first ?: byWords.orEmpty(), sure?.second.orEmpty(), sure != null || byWords != null)
        }
    }

    /** The assistant's guesses for the ones still without a place (only clear ones come back). */
    fun sortPrompt(board: Board, list: List<ToSort>): String = buildString {
        val areas = Areas.all(board.settings)
        appendLine("Sort these expenses into the user's areas, and a project when clear. Areas: " + areas.joinToString("; ") { a -> "${a.id} = ${a.name}" + if (a.words.isNotEmpty()) " (${a.words.joinToString()})" else "" } + ".")
        if (board.projects.isNotEmpty()) appendLine("Projects: ${board.projects.take(40).joinToString { it.name }}.")
        list.take(80).forEach { appendLine("${it.id} | ${it.date} | ${it.vendor} | ${it.amount} | ${it.note.take(80)}") }
        append("Only when clear; leave out the others. Reply with only {\"sort\": [{\"id\": string, \"area\": string, \"project\": string|null}]}.")
    }

    fun parseSort(reply: String, board: Board, list: List<ToSort>): List<ToSort> {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        val guesses = if (start < 0 || end <= start) emptyMap() else runCatching {
            Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject["sort"]!!.jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                id to (o["area"]?.jsonPrimitive?.contentOrNull.orEmpty() to o["project"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.toMap()
        }.getOrDefault(emptyMap())
        val areas = Areas.all(board.settings).map { it.id }.toSet(); val projects = board.projects.map { it.name }.toSet()
        return list.map { t ->
            val g = guesses[t.id]?.takeIf { !t.sure && it.first in areas } ?: return@map t
            t.copy(area = g.first, project = g.second.takeIf { it in projects }.orEmpty(), sure = true)
        }
    }
}
