package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Gbn
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GbnTest {
    private val today = LocalDate.parse("2026-10-07")

    @Test
    fun turningOnAndOffKeepsTheValuesUsedBefore() {
        val mine = listOf(Value("Clients", 3), Value("Family", 2))
        val on = Gbn.turnOn(Board(values = mine))
        assertTrue(on.gbn)
        assertEquals(listOf("Home", "Admin", "Career", "Money", "Invest", "Relations", "Health", "Joy"), on.values.map { it.name })
        assertTrue(on.values.all { it.bucket in Gbn.buckets })
        val off = Gbn.turnOff(on)
        assertFalse(off.gbn)
        assertEquals(mine, off.values)
        assertTrue(Gbn.profiles.getValue("gbn-lawyer").second.any { it.name == "Learning" }, "A career profile adds Learning")
    }

    @Test
    fun aTaskSharesValueAndLessons() {
        var b = Gbn.turnOn(Board())
        val levels = Gbn.parseLevels(b, mapOf("relations" to "3", "Joy" to "1", "Unknown" to "2", "Health" to "0"))
        assertEquals(mapOf("Relations" to 3, "Joy" to 1), levels)
        assertEquals(mapOf(Gbn.GROUND to 0, Gbn.BUILD to 0, Gbn.NOURISH to 100), Gbn.share(b, levels), "Only Nourish for this task")
        assertEquals(2, Gbn.valueFor(b, levels), "3x2 + 1x2 = 8: medium")
        b = BoardOps.add(b, BoardOps.NewTask("Dinner with my brother", intention = "To be there for him.", serve = levels), today).board
        val task = b.tasks.single()
        assertEquals("To be there for him.", task.intention)
        assertEquals(levels, Gbn.levelsOf(b, task))
        assertTrue("Relations" in task.values, "The planner counts what it serves")
        // A correction becomes a lesson for similar tasks.
        b = Gbn.learn(b, "Dinner with my brother", mapOf("Relations" to 3, "Joy" to 3))
        assertEquals(mapOf("Relations" to 3, "Joy" to 3), Gbn.lessonFor(b, "dinner with Sophie")?.serve)
        assertTrue(Gbn.prompt(b).contains("Dinner with my brother: Relations 3, Joy 3"))
    }
}
