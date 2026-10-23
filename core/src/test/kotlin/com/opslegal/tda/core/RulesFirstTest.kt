package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.QuickCommands
import com.opslegal.tda.core.plan.ReceiptRules
import com.opslegal.tda.core.plan.Rules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules on the phone, before any AI: receipts anywhere, quick commands, filtering that never drops anything silently. */
class RulesFirstTest {
    private val today = LocalDate.parse("2026-10-26")

    @Test
    fun receiptsAddUpInAnyCountry() {
        val qc = ReceiptRules.read("Chez Lévêque\n2026-10-20\nTartare 58.00\nVin 16.00\nSous-total 74.00\nTPS 5% 3.70\nTVQ 9.975% 7.38\nPourboire 13.32\nTOTAL 98.40 $")!!
        assertTrue(qc.sure); assertEquals(98.40, qc.total); assertEquals(2, qc.taxes.size); assertEquals(13.32, qc.tip); assertEquals("2026-10-20", qc.date)
        val ny = ReceiptRules.read("Duane Reade\n10-19-2026\nSubtotal 42.50\nState tax 4% 1.70\nCity tax 4.875% 2.07\nTotal 46.27")!!
        assertTrue(ny.sure); assertEquals(2, ny.taxes.size); assertEquals("2026-10-19", ny.date)
        val paris = ReceiptRules.read("Monoprix Paris 11\n21/10/2026\nCafé 4,90\nCahiers 12,80\nTOTAL TTC 17,70 €\ndont TVA 20% 2,95")!!
        assertTrue(paris.sure); assertEquals(14.75, paris.subtotal); assertEquals("Monoprix Paris 11", paris.vendor)
        val oregon = ReceiptRules.read("Powell's Books\nBook 24.99\nSubtotal 24.99\nTotal 24.99")!!
        assertTrue(oregon.sure); assertTrue(oregon.taxes.isEmpty())
        val messy = ReceiptRules.read("Shop\nItem 10.00\nOther 3.00\nTotal 20.00")!!
        assertTrue(!messy.sure, "the sums don't agree: the AI checks it")
    }

    @Test
    fun quickCommandsNeedOneClearMatch() {
        val b = Planner.plan(BoardOps.add(BoardOps.add(Board(settings = PlannerSettings(bigPerDay = 3)), NewTask(title = "Call the notary"), today).board, NewTask(title = "Send the offer"), today).board, today).board
        val move = QuickCommands.parse(b, "move notary to friday", today)
        assertNotNull(move)
        val moved = move.apply(b)
        assertEquals("2026-10-30", moved.tasks.first { it.title == "Call the notary" }.steps.first().date)
        assertNotNull(QuickCommands.parse(b, "offer done", today))
        assertNull(QuickCommands.parse(b, "move the unknown thing to friday", today), "nothing matches: the AI")
        assertNull(QuickCommands.parse(b, "can you look at my week and tell me what to drop", today))
        assertEquals(1, QuickCommands.parse(b, "call Marc tomorrow", today)!!.apply(b).quick.size)
        assertEquals(3, QuickCommands.parse(b, "buy milk, eggs and bread", today)!!.apply(b).buy.size)
    }

    @Test
    fun filteringNeverDropsAndLearnsFromTaps() {
        val scam = Incoming("1", "outlook", "Desjardins <alerte@desjardlns-secure.co>", "Votre compte est suspendu: confirmer votre identité", "2026-10-26T08:00")
        val ad = Incoming("2", "outlook", "Sail <promo@sail.ca>", "30% off this weekend. Unsubscribe", "2026-10-26T08:10")
        val real = Incoming("3", "outlook", "Me Dubé <dube@cabinet.ca>", "La mise en demeure est prête", "2026-10-26T09:00")
        val b = Board()
        assertEquals("scam", Rules.filter(b, scam)?.first)
        assertEquals("ad", Rules.filter(b, ad)?.first)
        assertNull(Rules.filter(b, real))
        val shown = Rules.learn(b, "filter", Rules.senderKey(ad.from), "show", "you tapped Show", today)
        assertNull(Rules.filter(shown, ad), "the user's tap wins")
        val kept = Rules.keepFiltered(b, listOf(scam to Rules.filter(b, scam)!!, ad to Rules.filter(b, ad)!!), today)
        assertEquals(2, kept.filtered.size)
        assertEquals(2, Rules.keepFiltered(kept, listOf(ad to Rules.filter(b, ad)!!), today).filtered.size, "never twice")
        val store = Rules.learn(b, "stores", "Canadian Tire #117", "Supplies|re", "your expense", today)
        assertEquals("Supplies" to "re", Rules.store(store, "Canadian Tire #117"))
    }
}
