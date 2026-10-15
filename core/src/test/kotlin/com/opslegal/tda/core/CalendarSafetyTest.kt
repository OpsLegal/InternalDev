package com.opslegal.tda.core

import com.opslegal.tda.core.agent.UpdateCheck
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.model.UpdateAction
import com.opslegal.tda.core.plan.Updates
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rachel's case: "on passe au mardi dans deux semaines" must stay a Tuesday, an appointment stays off the table, nothing future is "done". */
class CalendarSafetyTest {
    private val today = LocalDate.parse("2026-10-15") // a Thursday

    @Test
    fun aNamedWeekdayWinsOverASlip() {
        val a = UpdateAction("event", title = "Mia's session", date = "2026-10-29", repeat = "biweekly") // a Thursday
        assertEquals("2026-10-27", UpdateCheck.fixWeekday(a, "On garde aujourd'hui et on passe au mardi dans deux semaines").date)
        assertEquals("2026-10-29", UpdateCheck.fixWeekday(a, "See you then").date, "no weekday named: unchanged")
        assertEquals("2026-10-29", UpdateCheck.fixWeekday(a, "mardi ou jeudi").date, "two weekdays named: unchanged")
    }

    @Test
    fun aFutureCellIsNeverDoneAndAnEventStaysOffTheTable() {
        val b = Board(tasks = listOf(Task("t", "Mia session", steps = listOf(Step("s", "Mia session", date = "2026-10-20", slot = 0)))))
        val done = Update("u", "sms", "Rachel", "…", "…", actions = listOf(UpdateAction("done", step = "s")))
        assertNull(Updates.apply(b, done, today), "the 20th is in the future")
        val ev = Update("v", "sms", "Rachel", "…", "…", actions = listOf(UpdateAction("event", title = "Mia's session", date = "2026-10-27", time = "16:00", repeat = "biweekly")))
        assertEquals(b, Updates.apply(b, ev, today))
        assertTrue(Updates.describe(b, ev, today).single().contains("every 2 weeks"))
    }
}
