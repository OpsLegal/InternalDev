package com.opslegal.tda.core

import com.opslegal.tda.core.agent.TdaAgent
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.voice.LanguageGuess
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageTest {
    private val quebec = listOf("fr-CA", "en-CA")

    @Test
    fun guessesTheLanguageOfAnAnswer() {
        assertEquals("fr-CA", LanguageGuess.guess("Parfait, j'ai ajouté le rendez-vous chez le dentiste pour demain.", quebec))
        assertEquals("en-CA", LanguageGuess.guess("Done, I added the dentist appointment for tomorrow.", quebec))
        assertEquals("fr-CA", LanguageGuess.guess("OK", quebec), "unclear text keeps the main language")
        assertEquals("en-US", LanguageGuess.guess("Bonjour et merci", listOf("en-US")), "one language: nothing to guess")
    }

    @Test
    fun assistantIsToldToFollowTheUsersLanguage() {
        val two = Board(conversation = ConversationSettings(voiceLanguage = "fr-CA", otherLanguages = listOf("en-CA")))
        val prompt = TdaAgent.systemPrompt(two, LocalDate.parse("2026-09-21"))
        assertTrue(prompt.contains("Français (Canada), English (Canada)"))
        assertTrue(prompt.contains("Always answer in the language of their latest message"))
        assertFalse(TdaAgent.systemPrompt(Board(), LocalDate.parse("2026-09-21")).contains("LANGUAGES:"))
    }
}
