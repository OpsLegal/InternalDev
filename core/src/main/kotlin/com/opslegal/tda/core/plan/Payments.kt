package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Expense
import com.opslegal.tda.core.model.FoundPayment
import java.time.LocalDateTime

/**
 * Expenses without typing: the payments the phone already announces (a card tapped with Google Wallet, a purchase in
 * the bank's app, an Interac e-Transfer sent or deposited). Read on the phone, by these rules, with no AI; nothing is
 * kept until the user sorts it (an expense, or personal). Money received and refunds are never expenses.
 */
object Payments {
    /** Apps whose notifications announce payments: wallets, Canadian banks and cards. Email apps only for Interac. */
    val APPS = mapOf(
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.samsung.android.spay" to "Samsung Wallet",
        "com.scotiabank.banking" to "Scotia", "com.scotiabank.mobile" to "Scotia",
        "com.desjardins.mobile" to "Desjardins", "com.rbc.mobile.android" to "RBC", "com.td" to "TD",
        "com.bmo.mobile" to "BMO", "com.cibc.android.mobi" to "CIBC", "com.bnc.ca.mobile" to "National Bank",
        "ca.tangerine.clients.banking.app" to "Tangerine", "com.americanexpress.android.acctsvcs.ca" to "Amex",
        "ca.koho" to "KOHO", "com.wealthsimple.trade" to "Wealthsimple", "com.mogo.mobile" to "Mogo",
    )
    val EMAIL_APPS = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider")

    private val AMOUNT = Regex("""(?:\$\s?(\d{1,3}(?:[ ,]\d{3})*(?:[.,]\d{2}))|(\d{1,3}(?:[  .]\d{3})*(?:,\d{2}|\.\d{2}))\s?\$)""")
    private val SPENT = Regex("""(?i)\b(purchase|achat|paid|payé|payment|paiement|transaction|debit|débit|charged|e-?transfer|virement|deposited|déposé|accepted|accepté|sent|envoyé|spent|dépensé)""")
    private val NOT_SPENT = Regex("""(?i)(declin|refus|refund|rembours|sent you|vous a envoyé|vous avez reçu|you received|received \$|reçu \d|credit|crédit|cashback|remise|balance|solde|available|disponible|limit)""")
    private val TO = Regex("""(?i)\b(?:at|chez|to|à|au|from merchant)\s+([^.,;\n]{2,40})""")

    private fun amountOf(text: String): Double? = AMOUNT.find(text)?.let { m ->
        val raw = (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(" ", "").replace(" ", "")
        // 1,234.56 or 1 234,56 or 12,50: the last separator is the decimal one.
        val dec = raw.lastIndexOfAny(charArrayOf('.', ','))
        (raw.substring(0, dec).replace(",", "").replace(".", "") + "." + raw.substring(dec + 1)).toDoubleOrNull()
    }

    /** A payment in one notification, or null. [pkg] the app; [title] and [text] as Android shows them. */
    fun read(pkg: String, title: String, text: String, at: LocalDateTime): FoundPayment? {
        val all = "$title. $text"
        val interac = Regex("(?i)interac|e-?transfer|virement").containsMatchIn(all)
        val app = APPS[pkg] ?: if (pkg in EMAIL_APPS && interac) "Interac" else return null
        if (NOT_SPENT.containsMatchIn(all) || !SPENT.containsMatchIn(all) && pkg !in setOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay")) return null
        val amount = amountOf(all)?.takeIf { it > 0 && it < 100_000 } ?: return null
        // The wallet's title is the store; the bank says "at STORE" / « chez MAGASIN »; a transfer says "to NAME".
        val merchant = (if (pkg.contains("wallet") || pkg.contains("spay")) title else TO.find(text)?.groupValues?.get(1) ?: TO.find(title)?.groupValues?.get(1) ?: title)
            .replace(Regex("""(?i)\b(with|avec)\b.*$"""), "").replace(Regex("""(?i)\s+(was|has|is|a été|est|have)\b.*$"""), "").replace(Regex("""\s{2,}"""), " ").trim().take(40)
            .split(' ').joinToString(" ") { w -> if (w.length > 3 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.uppercase() } else w }
        return FoundPayment(BoardOps.newId(), amount, merchant.ifBlank { app }, at.withNano(0).toString(), if (interac) "transfer" else "card", app)
    }

    /** Kept to sort, unless the same payment is already there (the same amount and place within 7 days: a transfer sent, then deposited). */
    fun add(board: Board, p: FoundPayment): Board {
        val day = LocalDateTime.parse(p.at)
        val dup = board.paymentsFound.any { it.amount == p.amount && it.merchant.equals(p.merchant, true) && kotlin.math.abs(java.time.Duration.between(LocalDateTime.parse(it.at), day).toDays()) <= 7 } ||
            Paperwork.all(board).any { it.e.amount == p.amount && it.e.date == p.at.take(10) }
        return if (dup) board else board.copy(paymentsFound = (board.paymentsFound + p).takeLast(60))
    }

    /** Sorted as an expense: it joins the report (on a project when chosen), the payment leaves the list. */
    fun keep(board: Board, id: String, category: String, project: String?, billable: Boolean, note: String = "", area: String = ""): Board {
        val p = board.paymentsFound.firstOrNull { it.id == id } ?: return board
        val e = Expense(BoardOps.newId(), p.amount, vendor = p.merchant, date = p.at.take(10), category = category, billable = billable,
            note = listOf("From ${p.app}", note).filter { it.isNotBlank() }.joinToString(" · "), area = area)
        return Paperwork.addExpense(board.copy(paymentsFound = board.paymentsFound.filter { it.id != id }), e, null, project?.ifBlank { null })
    }

    /** Personal, or not something to track: it leaves the list, nothing is kept. */
    fun drop(board: Board, id: String): Board = board.copy(paymentsFound = board.paymentsFound.filter { it.id != id })

    /** A first guess of the expense's category from the place's name. */
    fun guessCategory(merchant: String): String = merchant.lowercase().let { m ->
        when {
            Regex("resto|restaurant|café|cafe|coffee|tim horton|starbucks|mcdo|subway|pizza|bistro|bar\\b|uber eats|doordash").containsMatchIn(m) -> "Meal"
            Regex("uber|taxi|lyft|stm|exo|via rail|air|hotel|hôtel|airbnb|parking|stationnement|essence|petro|shell|esso|ultramar").containsMatchIn(m) -> "Travel"
            Regex("bureau|staples|best buy|amazon|office|papeterie").containsMatchIn(m) -> "Supplies"
            Regex("fee|frais|registre|court|greffe|notaire").containsMatchIn(m) -> "Fees"
            else -> "Other"
        }
    }
}
