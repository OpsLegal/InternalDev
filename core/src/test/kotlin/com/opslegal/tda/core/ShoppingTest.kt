package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Shopping
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoppingTest {
    private val today = LocalDate.parse("2026-10-02")

    @Test
    fun thingsToBuyNeverTakeCellsUntilATripIsPlanned() {
        var (b, added) = Shopping.add(Board(), listOf("Lait", "Pain", "lait", " "), work = false)
        assertEquals(listOf("Lait", "Pain"), added)
        assertTrue(b.tasks.isEmpty())
        assertFalse(Shopping.due(b, today))
        b = Shopping.add(b, listOf("Printer toner"), work = true, needBy = today.plusDays(1).toString()).first
        assertTrue(Shopping.due(b, today), "Something needed tomorrow makes the list worth a trip")
        val (withTrip, id) = Shopping.planTrip(b, today)!!
        val planned = Planner.plan(withTrip, today).board
        val trip = planned.tasks.single()
        assertTrue(trip.errands)
        assertEquals(today.toString(), trip.steps.single().date, "The day before the toner is needed (today)")
        assertTrue(planned.buy.all { it.errand == id })
        assertFalse(Shopping.due(planned, today), "A planned trip is not due again")
        val done = BoardOps.setStepDone(planned, trip.steps.single().id, true)
        assertTrue(done.buy.all { it.done }, "Done on the Errands cell ticks everything it held")
    }
}
