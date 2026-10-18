package com.opslegal.tda.core

import com.opslegal.tda.core.agent.Prepare
import com.opslegal.tda.core.model.Board
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prepare: the right format per kind of action, the user's own format kept, and nothing that the sources don't back. */
class PrepareTest {
    @Test
    fun theKindComesFromTheWords() {
        assertEquals("call", Prepare.kindOf("Appeler l'arrondissement du Plateau").key)
        assertEquals("write", Prepare.kindOf("Envoyer la mise en demeure").key)
        assertEquals("meet", Prepare.kindOf("Rencontre de préparation CN").key)
        assertEquals("task", Prepare.kindOf("Kenza attend réponse Brésil").key)
    }

    @Test
    fun theUsersOwnFormatWinsAndCanGoBack() {
        val b = Prepare.setCanvas(Board(), "call", listOf("Who", "What I want", "Script"))
        assertEquals(listOf("Who", "What I want", "Script"), Prepare.canvas(b, Prepare.KINDS.first { it.key == "call" }))
        assertEquals(Prepare.KINDS.first().sections, Prepare.canvas(Prepare.setCanvas(b, "call", emptyList()), Prepare.KINDS.first()))
    }

    @Test
    fun aLineWithoutARealSourceIsDropped() {
        val call = Prepare.kindOf("Appeler l'arrondissement")
        val src = listOf(Prepare.Source("S1", "Email from Ville de Montréal, Oct 12", "Rappelez-nous au 514-872-0000 au sujet du permis 2026-118."))
        val reply = """{"sections":[{"title":"Who and how to reach them","lines":[
            {"text":"Ville de Montréal, 514-872-0000","src":["S1"]},
            {"text":"Agent: Mme Tremblay","src":["S9"]},
            {"text":"Mme Tremblay","src":[]},
            {"text":"Contact person [to confirm]","src":[]}]}],
            "missing":["The name of the agent"],"draft":null}"""
        val brief = Prepare.parse(reply, Board(), call, src)
        val who = brief.sections.first().lines
        assertEquals(listOf("Ville de Montréal, 514-872-0000", "Contact person [to confirm]"), who.map { it.text })
        assertTrue(who[1].toConfirm)
        assertEquals(call.sections, brief.sections.map { it.title }, "the format's order, every section shown")
        assertEquals(listOf("The name of the agent"), brief.missing)
        assertNull(brief.draft)
        assertTrue(Prepare.asNote(brief).contains("514-872-0000"))
    }
}
