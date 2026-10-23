package com.opslegal.tda.core.plan

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A receipt read by rules, in any country: the total first, then the amounts printed between the subtotal and the
 * total that add up to it (each tax, however many, or none) and the tip. When the sums agree to the cent it is
 * certain ([Read.sure]) and no AI is needed. Taxes printed as included in the total (VAT, TVA, IVA, MwSt) are read too.
 */
object ReceiptRules {
    data class Tax(val label: String, val amount: Double, val rate: Double?)
    data class Read(val vendor: String, val date: String?, val total: Double, val subtotal: Double?, val taxes: List<Tax>, val tip: Double, val sure: Boolean, val how: String) {
        val taxTotal: Double get() = taxes.sumOf { it.amount }
    }

    private data class Line(val i: Int, val label: String, val n: Double)

    private val AMOUNT = Regex("(-?\\d{1,3}(?:[ \\u00a0.,']\\d{3})*[.,]\\d{2}|-?\\d+[.,]\\d{2})\\s*(?:[$€£¥]|CAD|USD|EUR|AED|CHF|GBP|MAD|EGP|DH)?\\s*$", RegexOption.IGNORE_CASE)
    private val TOTAL = Regex("\\b(total|grand total|montant|amount due|à payer|a payer|to pay|ttc|summe|gesamt|totale|importe|net à payer|balance due)\\b", RegexOption.IGNORE_CASE)
    private val SUB = Regex("\\b(sub-?total|sous-?total|s/?total|subtotal|ht|hors tax\\w*|before tax|avant tax\\w*|zwischensumme|netto|imponible|subtotale|net)\\b", RegexOption.IGNORE_CASE)
    private val TIP = Regex("\\b(tip|pourboire|gratuity|gratuité|propina|trinkgeld|mancia|service charge)\\b", RegexOption.IGNORE_CASE)
    private val TAX = Regex("\\b(tax\\w*|tps|tvq|gst|qst|hst|pst|tvh|vat|tva|iva|mwst|ust|igv)\\b", RegexOption.IGNORE_CASE)

    fun amount(s: String): Pair<String, Double>? {
        if (s.trim().endsWith("%")) return null
        val m = AMOUNT.find(s) ?: return null
        var v = m.groupValues[1].replace(Regex("[ \\u00a0']"), "")
        val last = maxOf(v.lastIndexOf(','), v.lastIndexOf('.'))
        v = if (last >= 0 && v.length - last - 1 <= 2) v.substring(0, last).replace(Regex("[.,]"), "") + "." + v.substring(last + 1) else v.replace(Regex("[.,]"), "")
        val n = v.toDoubleOrNull() ?: return null
        return s.substring(0, m.range.first).trimEnd(' ', ':', '-', '–', '=').trim() to n
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 0.025
    private fun round2(x: Double) = (x * 100).roundToInt() / 100.0

    fun read(text: String): Read? {
        val raw = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val money = raw.mapIndexedNotNull { i, l -> amount(l)?.takeIf { it.second > 0 }?.let { Line(i, it.first, it.second) } }
        if (money.isEmpty()) return null
        val vendor = raw.firstOrNull { amount(it) == null && it.any(Char::isLetter) }.orEmpty().take(40)
        val date = Regex("\\b(20\\d\\d)[-/.](\\d{1,2})[-/.](\\d{1,2})\\b").find(raw.joinToString(" "))?.let { "${it.groupValues[1]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[3].padStart(2, '0')}" }
            ?: Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](20\\d\\d)\\b").find(raw.joinToString(" "))?.let { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                // Whichever can't be a month (over 12) is the day; else day first.
                val (d, mo) = if (b > 12) b to a else a to b
                "${m.groupValues[3]}-${mo.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
            }
        val totals = money.filter { TOTAL.containsMatchIn(it.label) && !SUB.containsMatchIn(it.label) }
        val t = totals.lastOrNull() ?: money.maxBy { it.n }
        val tipLine = money.firstOrNull { TIP.containsMatchIn(it.label) && it.n < t.n }
        val tip = tipLine?.n ?: 0.0
        fun result(sub: Double, taxes: List<Line>, how: String, sure: Boolean) = Read(vendor, date, t.n, round2(sub),
            taxes.map { Tax(it.label.ifBlank { "Tax" }, it.n, if (sub > 0) round2(it.n / sub * 100) else null) }, tip, sure, how)
        // 1. A subtotal before the total: the amounts between them (tip aside) must close the sum.
        val subs = money.filter { it.i < t.i && it != tipLine && it.n <= t.n }
            .sortedWith(compareByDescending<Line> { SUB.containsMatchIn(it.label) }.thenByDescending { it.n })
        for (s in subs) {
            val between = money.filter { it.i > s.i && it.i < t.i && it != tipLine && !TOTAL.containsMatchIn(it.label) }
            if (near(s.n + between.sumOf { it.n } + tip, t.n) && between.all { it.n < s.n * 0.35 })
                return result(s.n, between, if (between.isEmpty()) "no tax on this receipt" else "${between.size} tax${if (between.size > 1) "es" else ""} between the subtotal and the total", true)
        }
        // 2. Taxes printed as included in the total.
        val incl = money.filter { it != t && it != tipLine && TAX.containsMatchIn(it.label) && it.n < t.n * 0.3 }
        if (incl.isNotEmpty()) return result(t.n - tip - incl.sumOf { it.n }, incl, "taxes included in the total", true)
        return Read(vendor, date, t.n, null, emptyList(), tip, false, "the total only: the other amounts don't add up")
    }
}
