package com.kreyenhagen.homewall.data.layout

import android.content.ContentResolver
import android.net.Uri
import com.kreyenhagen.homewall.data.photo.PhotoResult
import com.kreyenhagen.homewall.data.photo.WallPhotoRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

enum class LayoutFormat(val mimeType: String, val fileName: String) {
    JSON("application/json", "wall_layout.json"),
    CSV("text/csv", "wall_layout.csv"),
}

/** A layout read from a file, waiting for me to confirm the import (§12.3). */
data class LayoutImport(val layout: WallLayout, val format: LayoutFormat) {
    val summary: MappingSummary get() = MappingSummary.of(layout)
}

/**
 * Layout import and export through the system file pickers (§8.5, §12.2, §12.3). Imports are
 * validated before anything is replaced.
 */
class LayoutTransfer(
    private val resolver: ContentResolver,
    private val layoutRepo: LayoutRepository,
    private val photoRepo: WallPhotoRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface ReadResult {
        data class Ok(val import: LayoutImport) : ReadResult
        data class Failed(val problems: List<String>) : ReadResult
    }

    /** Reads a JSON or CSV layout. JSON is recognized by its content, so the file name doesn't matter. */
    suspend fun read(uri: Uri): ReadResult {
        val text = try {
            withContext(io) {
                resolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= MAX_FILE_BYTES }?.decodeToString()
            }
        } catch (e: IOException) {
            return ReadResult.Failed(listOf("Couldn't read the file: ${e.message}"))
        } catch (e: SecurityException) {
            return ReadResult.Failed(listOf("Couldn't read the file: ${e.message}"))
        } ?: return ReadResult.Failed(listOf("Couldn't read the file (missing, or larger than 5 MB)."))
        return parse(text, layoutRepo.state.value.layoutOrNull?.wall ?: PlaceholderLayout.create().wall)
    }

    /** Replaces the layout and, if the wall's size changed, regenerates the straightened image. */
    suspend fun apply(import: LayoutImport): List<String> {
        val oldWall = layoutRepo.state.value.layoutOrNull?.wall
        val result = layoutRepo.replace(import.layout)
        if (result is LayoutRepository.UpdateResult.Rejected) return result.problems
        if (oldWall != import.layout.wall) {
            val photo = photoRepo.regenerate(import.layout.wall)
            if (photo is PhotoResult.Failed) return listOf("The layout was imported, but the photo couldn't be straightened again: ${photo.message}")
        }
        return emptyList()
    }

    /** Writes the current layout. Returns a problem to show, or null when it's saved. */
    suspend fun export(uri: Uri, format: LayoutFormat): String? {
        val layout = layoutRepo.state.value.layoutOrNull ?: return "The wall layout has problems, so there's nothing to export."
        val text = encode(layout, format)
        return try {
            withContext(io) {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("the file couldn't be opened")
                out.use { it.write(text.toByteArray()) }
            }
            null
        } catch (e: IOException) {
            "Couldn't save the file: ${e.message}"
        } catch (e: SecurityException) {
            "Couldn't save the file: ${e.message}"
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024

        fun parse(raw: String, wall: WallGeometry): ReadResult {
            // Files saved by some editors start with a byte-order mark, which the JSON parser rejects.
            val text = raw.removePrefix(CsvLayout.BYTE_ORDER_MARK)
            return if (text.trimStart().startsWith("{")) {
                when (val r = LayoutJson.parse(text)) {
                    is LayoutJson.ParseResult.Ok -> ReadResult.Ok(LayoutImport(r.layout, LayoutFormat.JSON))
                    is LayoutJson.ParseResult.Failed -> ReadResult.Failed(r.problems)
                }
            } else {
                when (val r = CsvLayout.parse(text, wall)) {
                    is CsvLayout.ParseResult.Ok -> ReadResult.Ok(LayoutImport(r.layout, LayoutFormat.CSV))
                    is CsvLayout.ParseResult.Failed -> ReadResult.Failed(r.problems)
                }
            }
        }

        fun encode(layout: WallLayout, format: LayoutFormat): String = when (format) {
            LayoutFormat.JSON -> LayoutJson.encode(layout)
            LayoutFormat.CSV -> CsvLayout.encode(layout)
        }
    }
}
