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

    @Test
    fun receiptsAndScansOnThePhoneMoveToTheCloudAndPointThere() {
        val local = "content://com.opslegal.tda.files/docs/a1.jpg"
        val b = board.copy(
            projects = listOf(com.opslegal.tda.core.model.Project("Duplex Laval", area = "re",
                expenses = listOf(com.opslegal.tda.core.model.Expense("e1", 212.4, vendor = "Rona", date = "2026-10-15", photo = local)),
                docs = listOf(com.opslegal.tda.core.model.Doc("d1", "Lease.pdf", "file", "content://com.android.providers/1")))),
            expenses = listOf(com.opslegal.tda.core.model.Expense("e2", 9.0, vendor = "Café", photo = "https://onedrive/x")),
        )
        val files = DriveFolders.localFiles(b) { it.startsWith("content://com.opslegal.tda.files/") }
        assertEquals(listOf("e1"), files.map { it.id }, "a file the user picked stays where it is; a cloud link is already moved")
        assertEquals("Real estate/Duplex Laval/Receipts/2026-10-15 Rona 212.40.jpg", files.single().let { DriveFolders.path(b, it.area, it.project, it.kind, it.date, it.what, "jpg") })
        val after = DriveFolders.moved(b, "e1", "https://onedrive/rona")
        assertEquals("https://onedrive/rona", after.projects.single().expenses.single().photo)
        assertEquals(emptyList(), DriveFolders.localFiles(after) { it.startsWith("content://com.opslegal.tda.files/") })
    }
}
