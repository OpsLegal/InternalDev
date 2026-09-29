package com.opslegal.tda.core

import com.opslegal.tda.core.agent.AgentState
import com.opslegal.tda.core.agent.AgentTools
import com.opslegal.tda.core.agent.BoardStore
import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.agent.CalendarSource
import com.opslegal.tda.core.agent.ToolCall
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.model.Effort
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.BoardOps.NewTask
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Slots
import com.opslegal.tda.core.plan.Values
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValuesTest {
    // A Monday.
    private val monday = LocalDate.parse("2026-09-21")

    private fun Board.add(spec: NewTask): Board = BoardOps.addTask(this, spec, monday).first

    @Test
    fun whatMattersMoreGetsTheEarlierCell() {
        var b = Board(values = listOf(Value("Credit", 3), Value("Pleasure", 1)))
        repeat(5) { b = b.add(NewTask("Filler $it")) }
        b = b.add(NewTask("Movie night", values = listOf("pleasure")))
        b = b.add(NewTask("Pay the Visa bill", values = listOf("Credit")))
        val planned = Planner.plan(b, monday).board
        val visa = planned.tasks.first { it.title == "Pay the Visa bill" }.steps.single()
        val movie = planned.tasks.first { it.title == "Movie night" }.steps.single()
        assertEquals(monday.toString(), visa.date)
        assertTrue(movie.date!! > visa.date!! || movie.slot!! > visa.slot!!)
    }

    @Test
    fun atMostTwoHeavyCellsADayAndNotSideBySide() {
        var b = Board()
        repeat(4) { b = b.add(NewTask("Heavy $it", effort = Effort.HEAVY)) }
        repeat(3) { b = b.add(NewTask("Light $it", effort = Effort.LIGHT)) }
        val planned = Planner.plan(b, monday).board
        val heavyMonday = planned.tasks.filter { it.effort == Effort.HEAVY }.flatMap { it.steps }.filter { it.date == monday.toString() }
        assertEquals(2, heavyMonday.size)
        val slots = heavyMonday.map { it.slot!! }.sorted()
        assertTrue(slots[1] - slots[0] > 1, "heavy cells $slots are side by side")
    }

    @Test
    fun pushedTwiceBecomesHeavyUnlessTheUserChose() {
        var b = Planner.plan(Board().add(NewTask("Tax report")).add(NewTask("Car repair", effort = Effort.LIGHT, effortByUser = true)), monday).board
        repeat(2) {
            b.tasks.forEach { t ->
                val open = t.steps.first { !it.closed }
                b = Planner.plan(BoardOps.pushStep(b, open.id, monday), monday).board
            }
        }
        assertEquals(Effort.HEAVY, b.tasks.first { it.title == "Tax report" }.effort)
        assertEquals(Effort.LIGHT, b.tasks.first { it.title == "Car repair" }.effort)
        assertEquals(2, b.tasks.first().pushes)
    }

    @Test
    fun weeklyMinimumsShowTheGaps() {
        val b = Board(values = listOf(Value("Family", 2, minPerWeek = 2), Value("Money", 3)))
            .add(NewTask("Call my brother", values = listOf("Family")))
        val gaps = Values.gaps(Planner.plan(b, monday).board, monday)
        assertEquals(listOf("Family" to 1), gaps.map { it.value.name to it.count })
    }

    @Test
    fun slotsAvoidBusyDaysDeadlinesAndCalendar() {
        var b = Board()
        // Tuesday is full, Thursday has a delivery.
        repeat(5) { b = BoardOps.addTaskOn(b, NewTask("Tue $it"), monday.plusDays(1), monday).first }
        b = BoardOps.addTaskOn(b, NewTask("File the report", kind = TaskKind.DEADLINE), monday.plusDays(3), monday).first
        val events = listOf(CalendarEvent("Dentist", "2026-09-21T09:00", "2026-09-21T11:30"))
        val slots = Slots.find(b, events, monday, 7, 3, monday.atTime(7, 0))
        // Monday: after the dentist plus buffer, in the morning window. Tuesday full. Wed/Thu next to the deadline.
        assertEquals(LocalTime.parse("14:00"), slots.first().start)
        assertEquals(monday, slots.first().date)
        assertTrue(slots.none { it.date == monday.plusDays(1) })
        assertEquals(3, slots.size)
    }

    @Test
    fun bookingAddsAMeetingCellAndACalendarEvent() = runTest {
        var board = Board(conversation = ConversationSettings(confirmation = ConfirmationPolicy.NEVER))
        val store = object : BoardStore {
            override suspend fun read() = board
            override suspend fun update(change: (Board) -> Board) = change(board).also { board = it }
        }
        val written = mutableListOf<String>()
        val calendar = object : CalendarSource {
            override suspend fun events(from: LocalDate, to: LocalDate) = emptyList<CalendarEvent>()
            override suspend fun addEvent(title: String, start: LocalDateTime, end: LocalDateTime, description: String): String {
                written += "$title $start $end"
                return "Work"
            }
        }
        val state = AgentState()
        val tools = AgentTools(store, { monday }, state, calendar)
        val slots = tools.execute(ToolCall("1", "find_slots", buildJsonObject { put("count", 2) }))
        assertTrue(slots.content.contains("start"), slots.content)
        tools.execute(ToolCall("2", "draft_message", buildJsonObject { put("to", "Jean"); put("text", "Mardi 10h ou mercredi 14h ?") }))
        assertEquals("Jean", state.draft.value?.to)
        val booked = tools.execute(ToolCall("3", "book_meeting", buildJsonObject {
            put("title", "Intro call"); put("with", "Jean"); put("date", "2026-09-23"); put("start", "10:00")
        }))
        assertTrue(booked.content.contains("Work"), booked.content)
        assertEquals(listOf("Intro call 2026-09-23T10:00 2026-09-23T11:00"), written)
        val task = board.tasks.single()
        assertEquals(TaskKind.MEETING, task.kind)
        assertEquals("2026-09-23", task.steps.single().date)

        tools.execute(ToolCall("4", "set_value", buildJsonObject { put("name", "Brand"); put("weight", 3) }))
        tools.execute(ToolCall("5", "set_about", buildJsonObject { putJsonArray("easy") { add("repairs"); add("cars") } }))
        assertEquals(listOf("Brand"), board.values.map { it.name })
        assertEquals(listOf("repairs", "cars"), board.about.easy)
    }
}
