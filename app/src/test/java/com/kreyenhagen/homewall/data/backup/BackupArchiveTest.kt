package com.kreyenhagen.homewall.data.backup

import com.kreyenhagen.homewall.data.layout.Hold
import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.layoutOf
import com.kreyenhagen.homewall.data.photo.PhotoAlignment
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    private val layout = layoutOf(
        Hold(0, 4f, 6f, listOf(0), group = "kicker"),
        Hold(7, 12f, 22f, listOf(7, 8), ringIn = 2.5f),
        hidden = listOf(1, 2),
    )
    private val routes = listOf(
        Route(3, "Pinch and pray", 5, listOf(RouteHold(0, HoldRole.START), RouteHold(7, HoldRole.FINISH)), 1_000, 2_000),
        Route(1, "Big move project", null, listOf(RouteHold(7, HoldRole.MIDDLE)), 500, 500),
    )

    /** Starts like a JPEG; enough for the check, since straightening happens on the tablet. */
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun contents(photo: ByteArray? = this.photo, alignment: PhotoAlignment? = PhotoAlignment.defaultGuess(3)) = BackupContents(
        manifest = BackupManifest(appVersion = "1.0", createdAt = "2026-11-10T14:30:00+01:00"),
        routes = routes,
        layout = layout,
        photo = photo,
        alignment = alignment,
    )

    private fun zipOf(contents: BackupContents): ByteArray = ByteArrayOutputStream().also { BackupArchive.write(it, contents) }.toByteArray()

    private fun zipOf(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun entryNames(zip: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(zip)).use { input ->
        generateSequence { input.nextEntry?.name }.toList()
    }

    private fun read(zip: ByteArray) = BackupArchive.read(ByteArrayInputStream(zip))

    private fun ok(zip: ByteArray): BackupContents {
        val result = read(zip)
        assertTrue("Expected Ok, got $result", result is BackupArchive.ReadResult.Ok)
        return (result as BackupArchive.ReadResult.Ok).contents
    }

    private fun problems(zip: ByteArray): List<String> {
        val result = read(zip)
        assertTrue("Expected Failed, got $result", result is BackupArchive.ReadResult.Failed)
        return (result as BackupArchive.ReadResult.Failed).problems
    }

    private val manifest = "manifest.json" to """{"schemaVersion": 1, "appVersion": "1.0", "createdAt": "2026-11-10T14:30:00Z"}"""
    private val goodLayout = "wall_layout.json" to LayoutJson.encode(layout)
    private val goodRoutes = "routes.json" to BackupArchive.encodeRoutes(routes)

    @Test
    fun theFileNameHasTheDateAndTime() {
        val time = ZonedDateTime.of(2026, 3, 7, 9, 5, 59, 0, ZoneOffset.UTC)
        assertEquals("homewall-backup-20260307-0905.zip", BackupArchive.fileName(time))
    }

    @Test
    fun everythingComesBackFromABackup() {
        val zip = zipOf(contents())
        // §13: the straightened image isn't included.
        assertEquals(listOf("manifest.json", "routes.json", "wall_layout.json", "wall_photo.jpg", "wall_photo.json"), entryNames(zip))

        val back = ok(zip)
        assertEquals(contents().manifest, back.manifest)
        assertEquals(routes.sortedBy { it.id }, back.routes) // ids and dates kept
        assertEquals(layout, back.layout) // groups and ring sizes included
        assertArrayEquals(photo, back.photo)
        assertEquals(PhotoAlignment.defaultGuess(3), back.alignment)
        assertEquals("2 routes · 550 LEDs · 2 holds (3 LEDs) · 2 hidden · photo, aligned", back.summary)
    }

    @Test
    fun aWallWithoutAPhotoBacksUpWithoutOne() {
        val zip = zipOf(contents(photo = null, alignment = null))
        assertEquals(listOf("manifest.json", "routes.json", "wall_layout.json"), entryNames(zip))
        val back = ok(zip)
        assertNull(back.photo)
        assertNull(back.alignment)
        assertTrue(back.summary.endsWith("· no photo"))
    }

    @Test
    fun aBackupZippedAgainInsideAFolderStillReads() {
        val zip = zipOf(
            "homewall-backup/manifest.json" to manifest.second,
            "homewall-backup/routes.json" to goodRoutes.second,
            "homewall-backup/wall_layout.json" to goodLayout.second,
        )
        assertEquals(2, ok(zip).routes.size)
    }

    @Test
    fun somethingElseIsNotABackup() {
        assertEquals(listOf("This isn't a Home Wall backup (it has no manifest.json)."), problems("hello".toByteArray()))
        assertEquals(listOf("This isn't a Home Wall backup (it has no manifest.json)."), problems(zipOf(goodLayout)))
    }

    @Test
    fun aBackupFromANewerAppIsRefused() {
        val zip = zipOf("manifest.json" to """{"schemaVersion": 2, "appVersion": "9.0", "createdAt": "x"}""", goodRoutes, goodLayout)
        assertEquals(
            listOf("This backup has schema version 2, but this app reads version 1. Update the app to import it."),
            problems(zip),
        )
    }

    @Test
    fun missingFilesAreListed() {
        assertEquals(listOf("The backup has no routes.json.", "The backup has no wall_layout.json."), problems(zipOf(manifest)))
    }

    @Test
    fun everyProblemIsListedBeforeAnythingIsReplaced() {
        val badRoutes = """[
            {"id": 1, "name": "  ", "grade": 3, "holds": [], "createdAt": 0, "updatedAt": 0},
            {"id": 2, "name": "Too hard", "grade": 20, "holds": [{"holdId": 0, "role": "START"}, {"holdId": 0, "role": "FINISH"}], "createdAt": 0, "updatedAt": 0},
            {"id": 2, "name": "Twin", "holds": [], "createdAt": 0, "updatedAt": 0}
        ]"""
        val badLayout = LayoutJson.encode(layout).replace("\"hiddenLeds\": [1,2]", "\"hiddenLeds\": [1,2,7]")
        val zip = zipOf(
            manifest,
            "routes.json" to badRoutes,
            "wall_layout.json" to badLayout,
            "wall_photo.jpg" to "not a photo",
            "wall_photo.json" to PhotoAlignment.encode(PhotoAlignment.defaultGuess(4)),
        )
        assertEquals(
            listOf(
                "routes.json: route 1: the name must be 1 to 40 characters.",
                "routes.json: route 2: grade 20 isn't V0–V17 or Project.",
                "routes.json: route 2 uses a hold more than once.",
                "routes.json: route 2 is listed more than once.",
                "wall_layout.json: LED 7 is in both hold 7 and hiddenLeds.",
                "wall_photo.jpg isn't a JPEG photo.",
            ),
            problems(zip),
        )
    }

    @Test
    fun anAlignmentMustMatchThePanels() {
        val zip = zipOf(manifest, goodRoutes, goodLayout, "wall_photo.json" to PhotoAlignment.encode(PhotoAlignment.defaultGuess(4)))
        assertEquals(listOf("wall_photo.json has 4 edges, but a wall with 2 panels needs 3."), problems(zip))
    }

    @Test
    fun brokenJsonIsReported() {
        val found = problems(zipOf(manifest, "routes.json" to "[{", goodLayout))
        assertEquals(1, found.size)
        assertTrue(found.single(), found.single().startsWith("routes.json isn't valid: "))
    }
}
