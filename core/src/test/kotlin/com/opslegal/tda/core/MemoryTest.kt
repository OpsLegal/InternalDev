package com.opslegal.tda.core

import com.opslegal.tda.core.agent.Memory
import com.opslegal.tda.core.agent.MemoryFile
import com.opslegal.tda.core.model.Incoming
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Read once, never again: the memory keeps what arrived, filters the noise for free, and finds it back by a few words. */
class MemoryTest {
    private val today = LocalDate.parse("2026-10-20")
    private val mail = Incoming("1", "outlook", "Me Dubé", "Duplex Laval: la mise en demeure est prête, à signer avant vendredi", "2026-10-19T10:00", mailId = "AAA")
    private val promo = Incoming("2", "outlook", "Rona <newsletter@rona.ca>", "30% off this week only. Unsubscribe", "2026-10-19T11:00")
    private val invoice = Incoming("3", "outlook", "Hydro-Québec <noreply@hydroquebec.com>", "Votre facture de 142,37 $ est prête", "2026-10-18T08:00")

    @Test
    fun anItemIsReadOnceAndNoiseCostsNothing() {
        val (m1, fresh1) = Memory.remember(MemoryFile(), listOf(mail, promo, invoice), today)
        assertEquals(2, fresh1.size, "the promotion is not even written down")
        val (m2, fresh2) = Memory.remember(m1, listOf(mail.copy(id = "9"), invoice), today)
        assertTrue(fresh2.isEmpty(), "already read")
        assertEquals(2, m2.items.size)
        assertTrue(Memory.noise(promo)); assertFalse(Memory.noise(invoice), "an invoice is always worth it"); assertFalse(Memory.noise(mail))
    }

    @Test
    fun eachAnalysisSeesOnlyWhatIsNewToIt() {
        val (m, _) = Memory.remember(MemoryFile(), listOf(mail, invoice), today)
        val marked = Memory.mark(m, listOf(invoice), "money")
        assertEquals(listOf("1"), Memory.unseenBy(marked, listOf(mail, invoice), "money").map { it.id })
        assertEquals(2, Memory.unseenBy(marked, listOf(mail, invoice), "look").size)
    }

    @Test
    fun foundBackByAFewWords() {
        val (m, _) = Memory.remember(MemoryFile(), listOf(mail, promo, invoice), today)
        assertEquals("Me Dubé", Memory.search(m, "mise en demeure duplex").single().from)
        assertTrue(Memory.search(m, "podcast").isEmpty())
        assertEquals(1, Memory.prune(m.copy(items = m.items + m.items.first().copy(key = "old", at = "2025-01-01T00:00")), today).items.count { it.key == "old" }.let { if (it == 0) 1 else 0 })
    }

    @Test
    fun secretsAreNeverWrittenDown() {
        val code = Incoming("4", "outlook", "Desjardins", "Votre code de vérification est 482913", "2026-10-19T09:00")
        val reset = Incoming("5", "outlook", "Microsoft", "Reset your password: click here", "2026-10-19T09:01")
        val (m, _) = Memory.remember(MemoryFile(), listOf(code, reset, mail), today)
        assertEquals(listOf("1"), m.items.map { it.key.removePrefix("mail:").let { k -> if (k == "AAA") "1" else k } })
        val hidden = Memory.redact("Card 4520 1234 5678 9012, password: Tigre!2024, PIN 4821, NAS 123 456 789, the code review is Monday")
        assertFalse(hidden.contains("5678")); assertTrue(hidden.contains("…9012"))
        assertFalse(hidden.contains("Tigre")); assertFalse(hidden.contains("4821")); assertFalse(hidden.contains("456 789"))
        assertTrue(hidden.contains("code review is Monday"), "ordinary words stay")
        val old = MemoryFile(listOf(com.opslegal.tda.core.agent.MemItem("x", "outlook", "Bank", "Your verification code is 1234", "2026-10-01"),
            com.opslegal.tda.core.agent.MemItem("y", "outlook", "Ana", "card 4520123456789012 for the deposit", "2026-10-01")))
        assertEquals(listOf("card [number hidden …9012] for the deposit"), Memory.clean(old).items.map { it.text })
    }

    @Test
    fun turningOffAConnectionForgetsWhatItBrought() {
        val chat = Incoming("6", "whatsapp", "Sam", "On se voit jeudi pour le duplex?", "2026-10-19T12:00", chatId = "c1")
        val (m, _) = Memory.remember(MemoryFile(), listOf(mail, chat), today)
        assertEquals(listOf("mail:AAA"), Memory.forget(m) { it.chatId.isNotBlank() }.items.map { it.key })
    }
}
