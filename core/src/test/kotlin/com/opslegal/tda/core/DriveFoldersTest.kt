package com.opslegal.tda.core

import com.opslegal.tda.core.model.Area
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.PlannerSettings
import com.opslegal.tda.core.plan.DriveFolders
import kotlin.test.Test
import kotlin.test.assertEquals

/** Files land in Apps › Docket 5 › area › project › kind, with names every drive accepts, in the drive the area chose. */
class DriveFoldersTest {
    private val board = Board(settings = PlannerSettings(areas = listOf(Area("work", "Work", listOf(1, 2, 3, 4, 5)), Area("re", "Real estate", listOf(6, 7), work = false))))

    @Test
    fun theStructureFollowsAreasAndProjects() {
        assertEquals("Real estate/Duplex Laval/Receipts/2026-10-15 Rona 212.40.jpg", DriveFolders.path(board, "re", "Duplex Laval", "Receipts", "2026-10-15", "Rona 212.40", "jpg"))
        assertEquals("Work/Tasks/Briefs/2026-10-18 Appeler l'arrondissement  Plateau.md".replace("  ", " "), DriveFolders.path(board, "work", null, "Briefs", "2026-10-18", "Appeler l'arrondissement / Plateau", "md"))
        assertEquals("Not sorted/Tasks/Receipts/2026-10-18 x.jpg", DriveFolders.path(board, "", "", "Receipts", "2026-10-18", "x", "jpg"))
    }

    @Test
    fun eachAreaSavesToItsChosenDrive() {
        val b = DriveFolders.choose(board, "re", "perso")
        assertEquals("perso", DriveFolders.driveFor(b, "re", listOf("pro", "perso")))
        assertEquals("pro", DriveFolders.driveFor(b, "work", listOf("pro", "perso")))
        assertEquals("pro", DriveFolders.driveFor(b, "re", listOf("pro")), "the chosen drive is not connected: the first one")
        assertEquals(null, DriveFolders.driveFor(b, "re", emptyList()))
    }
}
