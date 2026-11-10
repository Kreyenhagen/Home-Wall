package com.kreyenhagen.homewall.data.backup

import com.kreyenhagen.homewall.data.layout.LayoutJson
import com.kreyenhagen.homewall.data.layout.MappingSummary
import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.data.photo.PhotoAlignment
import com.kreyenhagen.homewall.domain.Grades
import com.kreyenhagen.homewall.domain.Route
import com.kreyenhagen.homewall.domain.RouteHold
import com.kreyenhagen.homewall.domain.RouteNames
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `manifest.json` in a backup (§13). */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = BackupArchive.SCHEMA_VERSION,
    val appVersion: String,
    /** ISO-8601 with the offset, e.g. `2026-11-10T14:30:00+01:00`. */
    val createdAt: String,
)

/** One route in `routes.json`. Field names match the database (§6.3). */
@Serializable
data class BackupRoute(
    val id: Long,
    val name: String,
    val grade: Int? = null,
    val holds: List<RouteHold>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Everything a backup holds. The straightened image isn't in it; it's regenerated (§13). */
class BackupContents(
    val manifest: BackupManifest,
    val routes: List<Route>,
    val layout: WallLayout,
    /** `wall_photo.jpg` as stored, or null if there was no photo. */
    val photo: ByteArray?,
    val alignment: PhotoAlignment?,
) {
    /** For the confirmation, e.g. "48 routes · 550 LEDs · 380 holds (402 LEDs) · 148 hidden · photo, aligned". */
    val summary: String
        get() = buildString {
            append(routes.size).append(if (routes.size == 1) " route" else " routes")
            append(" · ").append(MappingSummary.of(layout).text)
            append(" · ")
            append(
                when {
                    photo == null -> "no photo"
                    alignment == null -> "photo, not aligned"
                    else -> "photo, aligned"
                },
            )
        }
}

/**
 * Reads and writes the backup zip (§13): `manifest.json`, `routes.json`, `wall_layout.json`, and
 * `wall_photo.jpg` and `wall_photo.json` when there is a photo. Reading checks everything before
 * anything is replaced, and lists every problem found.
 */
object BackupArchive {
    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/zip"

    const val MANIFEST = "manifest.json"
    const val ROUTES = "routes.json"
    const val LAYOUT = "wall_layout.json"
    const val PHOTO = "wall_photo.jpg"
    const val ALIGNMENT = "wall_photo.json"

    /** Bigger than any photo the app saves (4096 px long side), small enough to read into memory. */
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Lets a later app version add fields without breaking older backups' import.
        ignoreUnknownKeys = true
    }

    private val FILE_NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** `homewall-backup-YYYYMMDD-HHmm.zip` (§13). */
    fun fileName(time: ZonedDateTime): String = "homewall-backup-${FILE_NAME_TIME.format(time)}.zip"

    fun write(out: OutputStream, contents: BackupContents) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, json.encodeToString(BackupManifest.serializer(), contents.manifest).toByteArray())
            put(ROUTES, encodeRoutes(contents.routes).toByteArray())
            put(LAYOUT, LayoutJson.encode(contents.layout).toByteArray())
            contents.photo?.let { put(PHOTO, it) }
            contents.alignment?.let { put(ALIGNMENT, PhotoAlignment.encode(it).toByteArray()) }
        }
    }

    sealed interface ReadResult {
        class Ok(val contents: BackupContents) : ReadResult
        data class Failed(val problems: List<String>) : ReadResult
    }

    fun read(input: InputStream): ReadResult {
        val files = try {
            unzip(input)
        } catch (e: TooLarge) {
            return ReadResult.Failed(listOf(e.message ?: "The backup is too large."))
        } catch (e: ZipException) {
            return ReadResult.Failed(listOf("This isn't a Home Wall backup (not a readable zip file)."))
        } catch (e: IOException) {
            return ReadResult.Failed(listOf("Couldn't read the backup: ${e.message}"))
        }
        if (MANIFEST !in files) {
            return ReadResult.Failed(listOf("This isn't a Home Wall backup (it has no $MANIFEST)."))
        }

        val problems = mutableListOf<String>()
        val manifest = decode(files.getValue(MANIFEST), BackupManifest.serializer(), MANIFEST, problems)
        if (manifest != null && manifest.schemaVersion != SCHEMA_VERSION) {
            val newer = manifest.schemaVersion > SCHEMA_VERSION
            return ReadResult.Failed(
                listOf(
                    "This backup has schema version ${manifest.schemaVersion}, but this app reads version $SCHEMA_VERSION." +
                        if (newer) " Update the app to import it." else "",
                ),
            )
        }

        val routes = files[ROUTES]?.let { decode(it, routeList, ROUTES, problems) }
        if (ROUTES !in files) problems += "The backup has no $ROUTES."
        routes?.let { problems += routeProblems(it) }

        val layout = when (val bytes = files[LAYOUT]) {
            null -> null.also { problems += "The backup has no $LAYOUT." }
            else -> when (val r = LayoutJson.parse(bytes.decodeToString())) {
                is LayoutJson.ParseResult.Ok -> r.layout
                is LayoutJson.ParseResult.Failed -> null.also { problems += r.problems.map { "$LAYOUT: $it" } }
            }
        }

        val photo = files[PHOTO]
        if (photo != null && !isJpeg(photo)) problems += "$PHOTO isn't a JPEG photo."

        val alignment = files[ALIGNMENT]?.let { bytes ->
            PhotoAlignment.parse(bytes.decodeToString()).also { if (it == null) problems += "$ALIGNMENT isn't a valid alignment file." }
        }
        if (alignment != null && layout != null && alignment.edges.size != layout.wall.panels.size + 1) {
            problems += "$ALIGNMENT has ${alignment.edges.size} edges, but a wall with ${layout.wall.panels.size} panels needs ${layout.wall.panels.size + 1}."
        }

        if (problems.isNotEmpty() || manifest == null || routes == null || layout == null) return ReadResult.Failed(problems)
        return ReadResult.Ok(
            BackupContents(
                manifest = manifest,
                routes = routes.map { Route(it.id, it.name, it.grade, it.holds, it.createdAt, it.updatedAt) },
                layout = layout,
                photo = photo,
                alignment = alignment,
            ),
        )
    }

    fun encodeRoutes(routes: List<Route>): String = json.encodeToString(
        routeList,
        routes.sortedBy { it.id }.map { BackupRoute(it.id, it.name, it.grade, it.holds, it.createdAt, it.updatedAt) },
    )

    private val routeList = ListSerializer(BackupRoute.serializer())

    /** The same rules the editor enforces (§6.3, §8.3), so a restored route can be opened and saved. */
    private fun routeProblems(routes: List<BackupRoute>): List<String> {
        val problems = mutableListOf<String>()
        val seen = HashSet<Long>()
        for (route in routes) {
            val label = "$ROUTES: route ${route.id}"
            if (route.id <= 0) problems += "$label: the id must be 1 or more."
            if (!seen.add(route.id)) problems += "$label is listed more than once."
            if (RouteNames.clean(route.name) == null) {
                problems += "$label: the name must be 1 to ${RouteNames.MAX_LENGTH} characters."
            }
            if (route.grade != null && route.grade !in Grades.MIN..Grades.MAX) {
                problems += "$label: grade ${route.grade} isn't V${Grades.MIN}–V${Grades.MAX} or Project."
            }
            val holdIds = route.holds.map { it.holdId }
            if (holdIds.size != holdIds.toSet().size) problems += "$label uses a hold more than once."
        }
        return problems
    }

    private fun <T> decode(bytes: ByteArray, serializer: KSerializer<T>, name: String, problems: MutableList<String>): T? =
        try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: SerializationException) {
            problems += "$name isn't valid: ${e.message}"
            null
        } catch (e: IllegalArgumentException) {
            problems += "$name isn't valid: ${e.message}"
            null
        }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private class TooLarge(message: String) : IOException(message)

    /** Reads every entry into memory, by name. Nothing is written to disk, so entry paths don't matter. */
    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    total += n
                    if (size > MAX_ENTRY_BYTES) throw TooLarge("${entry.name} in the backup is larger than ${MAX_ENTRY_BYTES / 1024 / 1024} MB.")
                    if (total > MAX_TOTAL_BYTES) throw TooLarge("The backup is larger than ${MAX_TOTAL_BYTES / 1024 / 1024} MB.")
                    out.write(buffer, 0, n)
                }
                // A backup made by the app has no folders; take the file name so one re-zipped by hand still works.
                files.putIfAbsent(entry.name.substringAfterLast('/'), out.toByteArray())
            }
        }
        return files
    }
}
