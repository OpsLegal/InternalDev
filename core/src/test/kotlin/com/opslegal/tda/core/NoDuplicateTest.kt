package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One thing, one card, one cell: the same email seen twice, or the same task in other words, is never added twice. */
class NoDuplicateTest {
    private val today = LocalDate.parse("2026-10-16")
    private fun cells(b: Board, w: String) = b.tasks.flatMap { t -> t.steps.filter { !it.closed }.map { t.title + " " + it.title } }.count { it.contains(w, ignoreCase = true) }

    @Test
    fun theSameEmailFromTheNotificationAndTheMailCheckIsOneCard() {
        val a = Update("n1", "outlook", "Ville de Montréal", "Votre demande au bureau d'arrondissement du Plateau: rappelez-nous lundi.", "",
            actions = listOf(UpdateAction("add", title = "Appeler l'arrondissement du Plateau (Ville de Montréal)", date = "2026-10-19")))
        val b = a.copy(id = "m1", source = "email", text = "Bonjour, votre demande au bureau d'arrondissement du Plateau Mont-Royal : rappelez-nous lundi svp.",
            actions = listOf(UpdateAction("add", title = "Appeler l'arrondissement du Plateau", date = "2026-10-19")))
        val board = Updates.add(Updates.add(Board(), listOf(a)), listOf(b))
        assertEquals(1, board.updates.size, "one card")
        assertEquals(1, board.updates.single().actions.size, "one cell proposed")
    }

    @Test
    fun applyingTwoCardsForOneTaskAddsItOnce() {
        val a = Update("n1", "outlook", "Ville", "x", "", actions = listOf(UpdateAction("add", title = "Appeler l'arrondissement du Plateau (Ville de Montréal)", date = "2026-10-19")))
        val b = Update("m2", "teams", "Julie", "y", "", actions = listOf(UpdateAction("add", title = "Appeler arrondissement Plateau", date = "2026-10-19")))
        var board = Updates.apply(Board(), a, today)!!
        board = Updates.apply(board, b, today)!!
        assertEquals(1, cells(board, "arrondissement"))
    }

    @Test
    fun alreadyOnTheTableEvenWeeksAwayIsNotProposedAgain() {
        val board = BoardOps.add(Board(), NewTask("Appeler l'arrondissement du Plateau"), today).board
        assertNotNull(Updates.alreadyOnTable(board, "Rappeler l'arrondissement du Plateau (Ville de Montréal)"))
        val u = Update("n9", "outlook", "Ville", "rappel", "", actions = listOf(UpdateAction("add", title = "Appeler arrondissement du Plateau", date = "2026-11-20")))
        assertTrue(Updates.add(board, listOf(u)).updates.isEmpty())
    }

    @Test
    fun twoStepsOfOneProjectAreNotTheSameThing() {
        val board = BoardOps.add(Board(), NewTask("Eviction Daniel Tardif", project = "Eviction Daniel Tardif", stepTitles = listOf("Envoyer la mise en demeure", "Préparer l'audience")), today).board
        assertNull(Updates.alreadyOnTable(board, "Notifier le locataire", "Eviction Daniel Tardif"))
        assertFalse(Updates.sameThing("Call Rachel about Mia", "Call the notary about the lease"))
        assertFalse(Updates.sameThing("Send the draft to ACT for feedback", "Integrate ACT's feedback"))
    }
}
