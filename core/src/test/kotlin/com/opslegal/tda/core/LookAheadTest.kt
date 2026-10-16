package com.opslegal.tda.core

import com.opslegal.tda.core.agent.LookAhead
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The next 60 days reviewed: the same thing twice is found and merged only on the user's tap; "not the same" is remembered. */
class LookAheadTest {
    private val today = LocalDate.parse("2026-10-16")

    private fun board(): Board {
        var b = BoardOps.add(Board(), NewTask("Appeler l'arrondissement du Plateau (Ville de Montréal)"), today).board
        b = BoardOps.add(b, NewTask("Appeler arrondissement Plateau"), today).board
        b = BoardOps.add(b, NewTask("Eviction Daniel Tardif", project = "Eviction Daniel Tardif", stepTitles = listOf("Envoyer la mise en demeure", "Notifier le locataire")), today).board
        return b
    }

    @Test
    fun findsTheSameCellTwiceButNotTwoStepsOfAProject() {
        val dups = LookAhead.duplicates(board(), today)
        assertEquals(1, dups.size)
        assertTrue(dups.single().keep.title.contains("arrondissement", true))
    }

    @Test
    fun mergeKeepsOneWithTheOthersWordsAndNotSameIsRemembered() {
        val b = board(); val d = LookAhead.duplicates(b, today).single()
        val merged = LookAhead.merge(b, d.keep.id, d.other.id, today)
        assertTrue(LookAhead.duplicates(merged, today).isEmpty())
        assertTrue(BoardOps.findStep(merged, d.keep.id)!!.second.description.contains("Merged"))
        assertEquals(1, LookAhead.cells(merged, today).count { it.title.contains("arrondissement", true) })
        assertTrue(LookAhead.duplicates(LookAhead.notSame(b, d.keep.id, d.other.id), today).isEmpty())
    }

    @Test
    fun theAssistantsIdeasAreCheckedBeforeTheyShow() {
        val b = board(); val id = LookAhead.cells(b, today).first().id
        val reply = """{"ideas":[{"type":"rename","step":"$id","title":"Call the Plateau borough office","why":"clearer"},
            {"type":"move","step":"nope","date":"2026-10-20","why":"x"},{"type":"move","step":"$id","date":"2020-01-01","why":"past"}]}"""
        val ideas = LookAhead.parse(reply, b, today)
        assertEquals(listOf("rename"), ideas.map { it.type })
        assertEquals("Call the Plateau borough office", BoardOps.findStep(LookAhead.apply(b, ideas.single(), today)!!, id)!!.second.title)
    }
}
