package com.kreyenhagen.homewall.data.backup

import android.content.ContentResolver
import android.database.SQLException
import android.net.Uri
import android.util.Log
import com.kreyenhagen.homewall.data.layout.LayoutRepository
import com.kreyenhagen.homewall.data.layout.layoutOrNull
import com.kreyenhagen.homewall.data.photo.PhotoResult
import com.kreyenhagen.homewall.data.photo.WallPhotoRepository
import com.kreyenhagen.homewall.data.route.RouteRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Settings → Export / Import backup (§13), through the system file pickers. The zip format lives
 * in [BackupArchive]; this moves it between the files and the repositories.
 */
class BackupManager(
    private val resolver: ContentResolver,
    private val routes: RouteRepository,
    private val layout: LayoutRepository,
    private val photo: WallPhotoRepository,
    private val appVersion: String,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** `homewall-backup-YYYYMMDD-HHmm.zip` for right now. */
    fun fileName(): String = BackupArchive.fileName(now())

    /** Writes a backup to [uri]. Returns a problem to show, or null when it's saved. */
    suspend fun export(uri: Uri): String? {
        val wallLayout = layout.state.value.layoutOrNull
            ?: return "The wall layout has problems, so a backup made now couldn't be restored. Fix it first (import a layout)."
        return try {
            val (photoBytes, alignment) = photo.backupFiles()
            val contents = BackupContents(
                manifest = BackupManifest(
                    appVersion = appVersion,
                    createdAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now().truncatedTo(ChronoUnit.SECONDS)),
                ),
                routes = routes.all(),
                layout = wallLayout,
                photo = photoBytes,
                alignment = alignment,
            )
            withContext(io) {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("the file couldn't be opened")
                out.use { BackupArchive.write(it, contents) }
            }
            null
        } catch (e: IOException) {
            Log.w(TAG, "Backup export failed", e)
            "Couldn't save the backup: ${e.message}"
        } catch (e: SecurityException) {
            "Couldn't save the backup: ${e.message}"
        }
    }

    /** Reads and checks a backup. Nothing changes until [restore]. */
    suspend fun read(uri: Uri): BackupArchive.ReadResult = try {
        withContext(io) {
            val input = resolver.openInputStream(uri) ?: throw IOException("the file couldn't be opened")
            input.use { BackupArchive.read(it) }
        }
    } catch (e: IOException) {
        BackupArchive.ReadResult.Failed(listOf("Couldn't read the backup: ${e.message}"))
    } catch (e: SecurityException) {
        BackupArchive.ReadResult.Failed(listOf("Couldn't read the backup: ${e.message}"))
    }

    /**
     * Replaces the wall layout, every route, and the photo and alignment, then regenerates the
     * straightened image (§13). Settings stay as they are. Returns problems to show; empty means
     * it all worked. The contents were checked by [read], so a failure here is a storage error.
     */
    suspend fun restore(contents: BackupContents): List<String> {
        val result = layout.replace(contents.layout)
        if (result is LayoutRepository.UpdateResult.Rejected) return result.problems
        try {
            routes.replaceAll(contents.routes)
        } catch (e: SQLException) {
            Log.w(TAG, "Restoring routes failed", e)
            return listOf("The wall layout was restored, but the routes couldn't be saved: ${e.message}")
        }
        return when (val p = photo.restore(contents.photo, contents.alignment, contents.layout.wall)) {
            PhotoResult.Done -> emptyList()
            is PhotoResult.Failed -> listOf("Routes and layout were restored, but the photo couldn't be straightened: ${p.message}")
        }
    }

    private companion object {
        const val TAG = "HomeWall/Backup"
    }
}
