package com.opslegal.tda.core

import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.voice.Reply
import com.opslegal.tda.core.voice.ReplyClassifier
import com.opslegal.tda.core.voice.TurnDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TurnsTest {
    private val settings = ConversationSettings()

    @Test
    fun endPhraseHandsOverTheTurn() {
        assertEquals("Add the ACME meeting on Thursday", TurnDetector.stripEndPhrase("Add the ACME meeting on Thursday, go ahead", settings.endPhrases))
        assertEquals("Ajoute le dentiste vendredi", TurnDetector.stripEndPhrase("Ajoute le dentiste vendredi. C'est tout.", settings.endPhrases))
        assertEquals("", TurnDetector.stripEndPhrase("vas-y", settings.endPhrases))
        assertNull(TurnDetector.stripEndPhrase("I want to go ahead with the refinancing next week", settings.endPhrases))
    }

    @Test
    fun unfinishedSentencesGetMoreTime() {
        assertTrue(TurnDetector.looksUnfinished("I need to file the tax report and"))
        assertTrue(TurnDetector.looksUnfinished("Il faut appeler la banque parce que"))
        assertTrue(TurnDetector.looksUnfinished("Tomorrow,"))
        assertFalse(TurnDetector.looksUnfinished("Add a meeting with ACME on Thursday"))
        assertEquals(6000, TurnDetector.pauseMillis("call the bank because", settings))
        assertEquals(3000, TurnDetector.pauseMillis("call the bank", settings))
        assertEquals(3000, TurnDetector.pauseMillis("call the bank because", settings.copy(waitWhenUnfinished = false)))
    }

    @Test
    fun yesNoAnswers() {
        assertEquals(Reply.YES, ReplyClassifier.classify("Yes, do it.", settings))
        assertEquals(Reply.YES, ReplyClassifier.classify("Oui c'est ça", settings))
        assertEquals(Reply.YES, ReplyClassifier.classify("no problem", settings))
        assertEquals(Reply.YES, ReplyClassifier.classify("pourquoi pas", settings))
        assertEquals(Reply.NO, ReplyClassifier.classify("No, not Thursday", settings))
        assertEquals(Reply.NO, ReplyClassifier.classify("that's not right", settings))
        assertEquals(Reply.OTHER, ReplyClassifier.classify("yes but put it on Friday", settings))
        assertEquals(Reply.OTHER, ReplyClassifier.classify("Actually make it three blocks instead of two please ok", settings))
    }
}
