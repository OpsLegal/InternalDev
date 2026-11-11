package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.FoundPayment
import com.opslegal.tda.core.model.MissedCall
import com.opslegal.tda.core.model.Update
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/** Missed calls read from the phone's "Missed call" notification. No call log, no AI. */
object Calls {
    private val MISSED = Regex("(?i)(missed call|appels? manqu|appels? en absence|llamada perdida|verpasster anruf)")
    private val COUNT = Regex("(\\d+)\\s*(?i:missed|appels|llamadas|verpasste)")
    private val NUMBER = Regex("\\+?\\d[\\d ()\\-.]{6,}\\d")

    /** The call in a dialer notification ([title], [text]), or null when it is not a missed call. */
    fun read(title: String, text: String, now: LocalDateTime): MissedCall? {
        val all = "$title\n$text"
        if (!MISSED.containsMatchIn(all)) return null
        val count = COUNT.find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        // The caller is the line that is not "Missed call": usually the text, sometimes the title.
        val who = listOf(text, title).map { it.trim() }.firstOrNull { it.isNotBlank() && !MISSED.containsMatchIn(it) && !COUNT.containsMatchIn(it) }
            ?.lineSequence()?.first()?.trim()?.take(60)
            ?: Regex("(?i)(?:missed call from|appel manqué de)\\s+(.+)").find(all)?.groupValues?.get(1)?.lineSequence()?.first()?.trim()?.take(60)
            ?: return null
        val number = NUMBER.find(all)?.value?.filter { it.isDigit() || it == '+' }.orEmpty()
        return MissedCall(BoardOps.newId(), who, number, count, now.withNano(0).toString())
    }

    /** Adds the call; the same person again updates their card (more calls, latest time). */
    fun add(board: Board, c: MissedCall): Board {
        val same = board.missed.firstOrNull { Paperwork.fold(it.who) == Paperwork.fold(c.who) }
            ?: return board.copy(missed = board.missed + c)
        // The dialer re-posts its notification: it carries the total so far, or one more call.
        val count = if (c.count > 1) maxOf(c.count, same.count) else if (c.at.take(16) == same.at.take(16)) same.count else same.count + 1
        return board.copy(missed = board.missed.map { if (it.id == same.id) it.copy(count = count, at = maxOf(it.at, c.at), number = c.number.ifBlank { it.number }) else it })
    }

    fun remove(board: Board, id: String): Board = board.copy(missed = board.missed.filterNot { it.id == id })

    /** "📞 Call back Sam" on today's ⚡ line, impact 3; the card leaves the list. */
    fun toToday(board: Board, id: String, today: LocalDate): Board {
        val c = board.missed.firstOrNull { it.id == id } ?: return board
        return Quick.add(remove(board, id), listOf("📞 Call back ${c.who}"), today, today, impact = 3, area = Areas.guess(board, c.who)).first
    }
}

/** What a notification is, by rule: a bill to pay or not. */
object Found {
    private val WORDS = Regex("(?i)\\b(factures?|invoices?|bills?|solde|impay[ée]e?s?|à payer|a payer|amount due|montant d[ûu]|payment due|due date)\\b")

    /** A bill: it carries a bill action, or bill words, unless its sender was marked "Not a bill" before. */
    fun isBill(board: Board, u: Update): Boolean {
        if (u.from.isNotBlank() && Rules.valueOf(board, "notbill", u.from) != null) return false
        return u.actions.any { it.type == "bill" } || WORDS.containsMatchIn("${u.title} ${u.summary}")
    }

    private val AMOUNT = Regex("(\\d{1,3}(?:[ ,]\\d{3})*[.,]\\d{2})\\s*\\$|\\$\\s*(\\d{1,3}(?:[ ,]\\d{3})*[.,]\\d{2})")

    /** The amount the notification names, if any. */
    fun amount(u: Update): Double? = (u.actions.firstOrNull { it.type == "bill" }?.amount)
        ?: AMOUNT.find("${u.title} ${u.summary} ${u.text}")?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(" ", "").replace(",", ".").let { s ->
            // "1.234.56" after the swap: keep the last dot only.
            val i = s.lastIndexOf('.'); (s.substring(0, i).replace(".", "") + s.substring(i)).toDoubleOrNull() } }

    /** Who to pay: the bill action's title, else the sender. */
    fun vendor(u: Update): String = u.actions.firstOrNull { it.type == "bill" }?.title?.ifBlank { null } ?: u.from.substringBefore('<').trim().ifBlank { u.title.take(40) }
}

/** A paper receipt read on the phone, matched to what is already known. */
object Receipts {
    sealed class Match {
        data class Payment(val p: FoundPayment) : Match()
        data class Spent(val e: Expense, val where: String) : Match()
    }

    /**
     * The payment or expense (without a receipt yet) with the same amount (to 2 cents) within 3 days. When several
     * fit, the one whose store shares the receipt's first word wins; still several, none (the user picks).
     */
    fun match(board: Board, total: Double, date: String?, vendor: String): Match? {
        val d = runCatching { LocalDate.parse(date?.take(10)) }.getOrNull()
        fun near(day: String) = d == null || runCatching { abs(LocalDate.parse(day.take(10)).toEpochDay() - d.toEpochDay()) <= 3 }.getOrDefault(false)
        val cands: List<Pair<Match, String>> =
            board.paymentsFound.filter { abs(it.amount - total) <= 0.02 && near(it.at) }.map { Match.Payment(it) to it.merchant } +
                Paperwork.all(board).filter { it.e.photo.isBlank() && abs(it.e.amount - total) <= 0.02 && near(it.e.date) }.map { Match.Spent(it.e, it.where) to it.e.vendor }
        if (cands.size <= 1) return cands.firstOrNull()?.first
        val word = Paperwork.fold(vendor).split(Regex("[^a-z0-9]+")).firstOrNull { it.length >= 3 } ?: return null
        return cands.filter { Paperwork.fold(it.second).contains(word) }.singleOrNull()?.first
    }

    /** The project a store's receipts went to last time ("Dollarama → Boat"). */
    fun project(board: Board, vendor: String): String? = Rules.valueOf(board, "receipt-project", firstWord(vendor))?.takeIf { BoardOps.findProject(board, it) != null }

    fun learnProject(board: Board, vendor: String, project: String, today: LocalDate): Board =
        if (project.isBlank() || firstWord(vendor).isBlank()) board else Rules.learn(board, "receipt-project", firstWord(vendor), project, "your receipt from $vendor", today)

    private fun firstWord(v: String) = Paperwork.fold(v).split(Regex("[^a-z0-9]+")).firstOrNull { it.length >= 3 }.orEmpty()

    /** Links the photo: the payment becomes an expense with it, or the expense gets it. */
    fun link(board: Board, m: Match, photo: String, area: String, project: String?, today: LocalDate, asset: String = ""): Board =
        if (asset.isBlank()) link(board, m, photo, area, project, today)
        else when (m) {
            // For a thing: the expense goes with it (a payment becomes its expense; an expense moves to it).
            is Match.Payment -> Assets.addExpense(board.copy(paymentsFound = board.paymentsFound.filter { it.id != m.p.id }), asset,
                Expense(BoardOps.newId(), m.p.amount, vendor = m.p.merchant, date = m.p.at.take(10), category = Payments.guessCategory(m.p.merchant), note = "From ${m.p.app}", photo = photo, area = area))
            is Match.Spent -> Assets.addExpense(Paperwork.changeExpense(board, m.e.id, null), asset, m.e.copy(photo = photo, area = area.ifBlank { m.e.area }))
        }

    fun link(board: Board, m: Match, photo: String, area: String, project: String?, today: LocalDate): Board = when (m) {
        is Match.Payment -> {
            val e = Expense(BoardOps.newId(), m.p.amount, vendor = m.p.merchant, date = m.p.at.take(10), category = Payments.guessCategory(m.p.merchant),
                note = "From ${m.p.app}", photo = photo, area = area)
            Paperwork.addExpense(board.copy(paymentsFound = board.paymentsFound.filter { it.id != m.p.id }), e, null, project?.ifBlank { null })
        }
        is Match.Spent -> {
            val b = Paperwork.changeExpense(board, m.e.id, m.e.copy(photo = photo, area = area.ifBlank { m.e.area }))
            if (project.isNullOrBlank() || project == m.where) b else Paperwork.sort(b, m.e.id, area.ifBlank { m.e.area }, project)
        }
    }.let { learnProject(it, if (m is Match.Payment) m.p.merchant else (m as Match.Spent).e.vendor, project.orEmpty(), today) }
}
