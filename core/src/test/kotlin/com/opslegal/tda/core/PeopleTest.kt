package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.plan.People
import com.opslegal.tda.core.plan.Quick
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 💬 People first: a promise to tell someone, ready answers in their language, how long they have waited. */
class PeopleTest {
    private fun msg(text: String, source: String = "whatsapp", from: String = "Maman", at: String = "") = Update("u1", source, from, text, "", at = at)

    @Test
    fun aRequestToBeToldIsAPromiseWithItsLineReady() {
        val p = People.promise(msg("Bon voyage mon fils ❤️ Préviens-moi quand tu arrives."))!!
        assertEquals("arrive", p.event); assertEquals("Bien arrivé ❤️", p.line)
        assertEquals("Home safe ❤️", People.promise(msg("Text me when you get home"))!!.line)
        assertNull(People.promise(msg("Let me know when you arrive", source = "outlook")), "an email is not a chat")
        assertNull(People.promise(msg("Thursday or Friday for that drink?")))
    }

    @Test
    fun readyAnswersFollowTheLanguageAndTonightPlansTheRealAnswer() {
        val fr = People.answers(msg("Est-ce que la clause 12 est légale ?", from = "Sophie (client)"))
        assertEquals("Bien reçu Sophie 👍", fr.first().text)
        assertTrue(fr.last().later)
        assertEquals("Got it Fares 👍", People.answers(msg("Thursday or Friday for that drink?", from = "Fares")).first().text)
        val today = LocalDate.parse("2026-11-25")
        val b = People.answerLater(Board(), msg("x", from = "Fares"), today)
        assertEquals("↩ Answer Fares properly", Quick.onDay(b, today, today).single().title)
    }

    @Test
    fun howLongTheyHaveWaited() {
        val now = LocalDateTime.parse("2026-11-25T12:00:00")
        assertEquals("waiting 3 h", People.waitLabel(People.waitedHours(msg("hi", at = "2026-11-25T09:00:00"), now)))
        assertEquals("waiting 5 days", People.waitLabel(People.waitedHours(msg("hi", at = "2026-11-20T11:00:00"), now)))
        assertEquals("new", People.waitLabel(People.waitedHours(msg("hi"), now)))
    }
}
