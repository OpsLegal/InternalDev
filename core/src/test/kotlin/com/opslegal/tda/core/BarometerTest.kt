package com.opslegal.tda.core

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.model.Routine
import com.opslegal.tda.core.model.Moment
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.Balance
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Flags
import com.opslegal.tda.core.plan.Gbn
import com.opslegal.tda.core.plan.Moves
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The week's barometer on whole weeks: done cells, pushes, red cells, records, moves, routines, recovery. */
class BarometerTest {
    private val mon = LocalDate.parse("2026-10-19")
    private val sat = mon.plusDays(5)
    private fun d(i: Int) = mon.plusDays(i.toLong()).toString()
    private var n = 0
    private val serves = listOf("Home", "Organized", "Methodic", "Reputation", "Money", "Invest", "Relations", "Health", "Joy").map { mapOf(it to 2) }

    private fun cell(serve: Map<String, Int>, day: Int, done: Boolean = true, pushes: List<Int> = emptyList()): Task {
        n++
        return Task("t$n", "Task $n", serve = serve, steps = listOf(Step("s$n", "Task $n", done = done, date = d(day), slot = 0, pushLog = pushes.map(::d))))
    }

    private fun board(tasks: List<Task>) = Gbn.turnOn(Board(settings = PlannerSettings(holidays = ""))).copy(tasks = tasks)

    /** Three done cells per value, three or four a day: the aims of a 25-cell week. */
    private fun aligned() = serves.flatMapIndexed { k, s -> (0..2).map { cell(s, (k + it * 2) % 5) } }

    private fun Board.mapStep(i: Int, f: (Step) -> Step) = copy(tasks = tasks.mapIndexed { k, t -> if (k == i) t.copy(steps = t.steps.map(f)) else t })

    @Test
    fun anAlignedWeekIsNearlyFull() {
        val w = Balance.of(board(aligned()), sat)
        assertEquals(25, w.capacity, "70% of 35 cells (Work and Personal every day)")
        assertTrue(w.week >= 90, "week ${w.week}")
        assertTrue(w.pillar.values.all { it >= 85 }, "${w.pillar}")
    }

    @Test
    fun overloadEarnsNothingAndMisalignmentShows() {
        val work = board((0 until 25).map { cell(if (it % 3 == 0) mapOf("Organized" to 2) else mapOf("Reputation" to 2, "Money" to 1), it % 5) })
        val w = Balance.of(work, sat)
        assertTrue(w.pillar.getValue(Gbn.NOURISH) < 15, "no Nourish cell: ${w.pillar}")
        assertTrue(w.week < 50, "busy is not balanced: ${w.week}")
        assertTrue(w.lines.getValue("Reputation").over > 0, "beyond the aim is shown")
        assertTrue(Balance.gainOf(work, cell(mapOf("Health" to 2), 0), w) > Balance.gainOf(work, cell(mapOf("Reputation" to 2), 0), w))
    }

    @Test
    fun aPushIsFeltFromTheFirstEvenBeyondTheAim() {
        val b = board(aligned() + cell(mapOf("Money" to 2), 1))
        val before = Balance.of(b, sat).lines.getValue("Money")
        val once = Balance.of(b.mapStep(12) { it.copy(pushLog = listOf(d(0))) }, sat).lines.getValue("Money")
        assertTrue(before.pct == 1.0 && once.pct < 1.0 && once.pct > 0.9, "small but visible: ${once.pct}")
        val red = Balance.of(b.mapStep(12) { it.copy(done = false, pushLog = listOf(d(0), d(1))) }, sat)
        assertTrue(red.lines.getValue("Money").cost == 1.0, "½ red + ¼ × 2 pushes")
        assertTrue(red.lines.getValue("Organized").cost == 0.75, "½ red + ¼ for the second push")
    }

    @Test
    fun catchingUpGivesMostOfItBack() {
        var b = board(aligned())
        listOf(0, 3, 6, 9, 12, 15, 18, 21).forEach { i -> b = b.mapStep(i) { it.copy(done = false, pushLog = if (i % 2 == 0) listOf(d(1), d(2)) else emptyList()) } }
        val bad = Balance.of(b, sat)
        val cu = assertNotNull(Balance.catchUp(b, sat, bad))
        assertEquals(8, cu.count)
        val good = Balance.of(b.copy(tasks = b.tasks.map { t -> t.copy(steps = t.steps.map { it.copy(done = true) }) }), sat)
        assertTrue(bad.week < 70 && good.week >= 90, "${bad.week} → ${good.week}")
        assertTrue(cu.gain >= 20, "finishing them is worth it: +${cu.gain}")
        assertEquals(1.0, good.lines.getValue("Organized").pct, "done gives the open loops all back")
    }

    @Test
    fun pushesAndMovesAreRecorded() {
        val today = mon.plusDays(2)
        val task = Task("p", "Refinancing", project = "Refinancing", serve = mapOf("Invest" to 2), steps = listOf(
            Step("a", "Call the notary", date = d(0), slot = 0), Step("b", "Send the papers", date = d(3), slot = 0), Step("c", "Sign", date = d(4), slot = 0)))
        val start = board(listOf(task)).copy(projects = listOf(Project("Refinancing", serve = mapOf("Invest" to 2))))
        val b = BoardOps.pushStep(start, "a", today)
        val record = b.tasks.single().steps.first { it.outcome == Outcome.MISSED }
        assertEquals("a", record.origin)
        assertEquals(listOf(today.toString()), BoardOps.findStep(b, "a")!!.second.pushLog)
        assertEquals(0.5, Balance.of(b, today).lines.getValue("Organized").cost, "the red record is an open loop")
        assertEquals(0.75, Balance.of(b, today).lines.getValue("Invest").cost, "½ red record + ¼ for the push")
        val doneA = BoardOps.setStepDone(b, "a", true)
        assertEquals(0.0, Balance.of(doneA, today).lines.getValue("Organized").cost, "done there: the record stops costing")
        // By hand: earlier is free, later is a push.
        val earlier = Moves.drop(start, "b", today, 2, today)!!
        assertTrue(BoardOps.findStep(earlier, "b")!!.second.pushLog.isEmpty())
        val later = Moves.drop(start, "b", today.plusDays(2), 1, today)!!
        assertEquals(1, BoardOps.findStep(later, "b")!!.second.pushLog.size)
    }

    @Test
    fun routinesCountALittleAndFeedMethodic() {
        val r = listOf(Routine("gym", "Gym", listOf(1, 3, 5), Moment.MORNING, mapOf("Health" to 2)))
        val b = board(aligned().filterIndexed { i, _ -> i / 3 != 7 }).copy(routines = r) // no Health cell
        val none = Balance.of(b, sat)
        val all = Balance.of(b.copy(routineDone = mapOf(d(0) to listOf("gym"), d(2) to listOf("gym"), d(4) to listOf("gym"))), sat)
        assertEquals(0.0, none.lines.getValue("Health").pct)
        assertTrue(all.lines.getValue("Health").pct in 0.2..0.26, "at most ¼ of the aim: ${all.lines.getValue("Health").pct}")
        assertTrue(all.lines.getValue("Methodic").pct > none.lines.getValue("Methodic").pct, "routines kept feed the flow")
    }

    @Test
    fun steadyDaysBeatASprint() {
        val steady = Balance.of(board(aligned()), sat)
        val sprint = Balance.of(board(aligned().mapIndexed { i, t -> t.copy(steps = t.steps.map { it.copy(date = d(i % 2)) }) }), sat)
        assertTrue(sprint.lines.getValue("Methodic").pct < steady.lines.getValue("Methodic").pct)
    }

    @Test
    fun capacityFollowsTheLastWeeks() {
        val hist = (1..4).flatMap { w -> (0 until 12).map { cell(mapOf("Organized" to 2), -7 * w + it % 5) } }
        assertEquals(13, Balance.of(board(hist), sat).capacity, "12 a week + 10%")
    }

    @Test
    fun oldNamesAreMigratedAndTheReviewComesAtItsTime() {
        val old = Board(gbn = true, values = listOf(Value("Home", 2, bucket = Gbn.GROUND), Value("Admin", 2, bucket = Gbn.GROUND), Value("Career", 3, bucket = Gbn.BUILD)),
            tasks = listOf(Task("x", "Brief", serve = mapOf("Career" to 2))), adjust = mapOf("Career" to 3))
        val m = Gbn.migrate(old)
        assertEquals(listOf("Home", "Organized", "Methodic", "Reputation"), m.values.map { it.name })
        assertEquals(mapOf("Reputation" to 2), m.tasks.single().serve)
        assertEquals(mapOf("Reputation" to 3), m.adjust)
        assertEquals(m, Gbn.migrate(m), "once")
        assertNull(Flags.review(m, sat, LocalTime.of(17, 0)))
        assertNotNull(Flags.review(m, sat, LocalTime.of(18, 30)))
        assertNull(Flags.review(m.copy(reviewed = sat.toString()), sat, LocalTime.of(20, 0)))
    }
}
