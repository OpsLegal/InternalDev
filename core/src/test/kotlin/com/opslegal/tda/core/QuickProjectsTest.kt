package com.opslegal.tda.core

import com.opslegal.tda.core.plan.QuickProjects
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** A list said in a meeting becomes several projects, each with the end date its words give. */
class QuickProjectsTest {
    private val today = LocalDate.parse("2026-10-20")

    @Test
    fun oneProjectPerLineWithItsEndDate() {
        val items = QuickProjects.parse("""
            - Contacter LIA by Nov 30
            • Site web – fin d'année
            3. Bolton renouvellement: 2026-12-15
            Recrutement stagiaire pour le 15 décembre
            Podcast juridique
            Fermer Q3 30/11
        """.trimIndent(), today)
        assertEquals(listOf("Contacter LIA", "Site web", "Bolton renouvellement", "Recrutement stagiaire", "Podcast juridique", "Fermer Q3"), items.map { it.name })
        assertEquals(listOf("2026-11-30", "2026-12-31", "2026-12-15", "2026-12-15", null, "2026-11-30"), items.map { it.deadline?.toString() })
    }

    @Test
    fun aDatePastThisYearMeansNextYear() {
        assertEquals("2027-03-01", QuickProjects.parse("Bilan annuel by March 1", today).single().deadline.toString())
    }
}
