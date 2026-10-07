package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Rides
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RidesTest {
    private val today = LocalDate.parse("2026-10-07")

    @Test
    fun aQuickThingRidesAlongAndIsNeverLost() {
        var b = Planner.plan(BoardOps.add(Board(), BoardOps.NewTask("Contractor visit", where = "Home"), today).board, today).board
        val host = b.tasks.single().steps.single()
        assertEquals(listOf("Contractor visit"), Rides.hosts(b, today).map { it.title })
        b = Rides.add(b, host.id, "Ask about the tiles", where = "Home")
        b = Rides.add(b, host.id, "Cabinet price")
        assertEquals(1, b.tasks.size, "No cell of its own")
        assertEquals(2, Planner.rows(b, today, 1).single().cells.first { it != null }!!.extras)
        b = Rides.setDone(b, host.id, BoardOps.findStep(b, host.id)!!.second.riders.first().id, true)
        assertEquals(1, Planner.rows(b, today, 1).single().cells.first { it != null }!!.extras)
        // Cancelling the cell: the open extra becomes a small task of its own.
        b = BoardOps.cancelStep(b, host.id, today)
        assertTrue(b.tasks.any { it.title == "Cabinet price" && !it.isDone })
        assertTrue(b.tasks.none { it.title == "Ask about the tiles" }, "A done extra stays done with its cell")
    }

    @Test
    fun anErrandOnATripGoesOnItsListAndDistancesAreReal() {
        var b = BoardOps.add(Board(), BoardOps.NewTask("Errands"), today).board
        b = b.copy(tasks = b.tasks.map { it.copy(errands = true) })
        b = Planner.plan(b, today).board
        b = Rides.add(b, b.tasks.single().steps.single().id, "Batteries")
        assertEquals(listOf("Batteries"), b.buy.map { it.text })
        assertEquals(b.tasks.single().id, b.buy.single().errand)
        // Montreal (Plateau) to Laval (Costco): about 15 km.
        val km = Rides.km(45.5236 to -73.5800, 45.5650 to -73.7480)
        assertTrue(km in 12.0..16.0, "Got $km")
    }
}
