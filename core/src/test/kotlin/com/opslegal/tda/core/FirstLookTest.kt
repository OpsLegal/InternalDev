package com.opslegal.tda.core

import com.opslegal.tda.core.agent.FirstLook
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The first look: the AI's list read safely; nothing added twice; a note's day honoured; a look-alike project joined. */
class FirstLookTest {
    private val today = LocalDate.parse("2026-10-15")
    private val reply = """{"projects":[{"name":"Mortgage refinancing","from":"4 emails with Sophie","deadline":"2026-11-14","intention":"Lower the payment.","steps":["Send the pay stubs","Review the offer"]},
        {"name":"Kitchen renovation","from":"6 emails with Marc","deadline":"","intention":"A kitchen by the holidays.","steps":["Compare the quotes","Sign the quote"]}],
        "tasks":[{"title":"Answer Luc about the lease file","from":"Luc","project":"","date":""}],
        "bills":[{"vendor":"Hydro-Québec","amount":"142,37","due":"2026-10-24","category":"Home","note":"Electricity","from":"e-bill"}],"skipped":171}"""

    private fun board(): Board {
        var b = Board(settings = PlannerSettings(holidays = ""))
        b = BoardOps.saveProject(b, Project("Refinancing"))
        return com.opslegal.tda.core.plan.Projects.save(b, Project("Refinancing"), "Refinancing", listOf(BoardOps.EditedStep(null, "Call the notary"), BoardOps.EditedStep(null, "Send the pay stubs")), today).board
    }

    @Test
    fun theListIsReadAndLookAlikesAreFound() {
        val f = FirstLook.parse(reply, 300, today)
        assertEquals(2, f.projects.size); assertEquals(1, f.tasks.size); assertEquals(142.37, f.bills.single().amount); assertEquals(171, f.skipped)
        val b = board()
        assertEquals("Refinancing", FirstLook.lookalikeProject(b, "Mortgage refinancing")?.name)
        assertNull(FirstLook.lookalikeProject(b, "Kitchen renovation"))
    }

    @Test
    fun joiningAddsOnlyTheMissingStepsAndANoteDayIsKept() {
        val f = FirstLook.parse(reply, 300, today)
        var b = FirstLook.addProject(board(), f.projects[0], "Refinancing", "", today)
        val steps = BoardOps.projectTask(b, "Refinancing")!!.steps.map { it.title }
        assertEquals(listOf("Call the notary", "Send the pay stubs", "Review the offer"), steps, "the pay stubs are not added twice")
        assertTrue(BoardOps.findProject(b, "Refinancing")!!.history.last().contains("Sophie"))
        b = FirstLook.addProject(b, f.projects[1], null, "", today)
        assertNotNull(BoardOps.findProject(b, "Kitchen renovation"))
        b = FirstLook.addTask(b, f.tasks[0], "Friday, tell him it's ready", today)
        assertEquals("2026-10-16", b.tasks.first { it.title.startsWith("Answer Luc") }.steps.single().date)
        assertNotNull(FirstLook.lookalikeCell(b, "Answer Luc about the lease"))
        b = FirstLook.addBill(b, f.bills[0], "", today)
        assertTrue(FirstLook.billThere(b, "hydro-québec"))
    }
}
