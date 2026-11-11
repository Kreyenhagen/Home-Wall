package com.kreyenhagen.homewall.data.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.kreyenhagen.homewall.data.layout.WallGeometry
import com.kreyenhagen.homewall.util.writeAtomically
import com.kreyenhagen.homewall.util.writeTextAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

data class WallPhotoState(
    /** False until [WallPhotoRepository.load] has read what's on disk. */
    val loaded: Boolean = false,
    val hasPhoto: Boolean = false,
    val alignment: PhotoAlignment? = null,
    /** The straightened wall (§7.1), decoded once and shared by every screen (§7.6). */
    val rectified: ImageBitmap? = null,
    /** Importing a photo or straightening it. */
    val working: Boolean = false,
)

sealed interface PhotoResult {
    data object Done : PhotoResult
    data class Failed(val message: String) : PhotoResult
}

/** The single owner of the wall photo, its alignment and the straightened image (§4, §6.2). */
class WallPhotoRepository(
    dir: File,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val photoFile = File(dir, "wall_photo.jpg")
    private val alignmentFile = File(dir, "wall_photo.json")
    private val rectifiedFile = File(dir, "wall_rectified.jpg")

    private val _state = MutableStateFlow(WallPhotoState())
    val state: StateFlow<WallPhotoState> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * Reads what's on disk at startup. Regenerates the straightened image if it's missing or
     * doesn't match the wall's size (e.g. the layout was edited by hand).
     */
    suspend fun load(geometry: WallGeometry?) = mutex.withLock {
        withContext(io) {
            val alignment = if (alignmentFile.exists()) {
                try {
                    PhotoAlignment.parse(alignmentFile.readText())
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }
            var rectified = if (rectifiedFile.exists()) BitmapFactory.decodeFile(rectifiedFile.path) else null
            val stale = geometry != null && rectified != null &&
                (rectified.width != WallRectifier.outputWidth(geometry) || rectified.height != WallRectifier.outputHeight(geometry))
            if (geometry != null && alignment != null && photoFile.exists() && (rectified == null || stale)) {
                rectified = when (val result = straighten(alignment, geometry)) {
                    is Straightened.Ok -> result.bitmap
                    is Straightened.Failed -> rectified
                }
            }
            _state.value = WallPhotoState(
                loaded = true,
                hasPhoto = photoFile.exists(),
                alignment = alignment,
                rectified = rectified?.toImageBitmap(),
            )
        }
    }

    /** The original photo, full size, for the Align step. */
    suspend fun loadPhoto(): ImageBitmap? = withContext(io) {
        if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.path)?.toImageBitmap() else null
    }

    /**
     * Saves the photo at [uri] as `wall_photo.jpg` (§6.2): EXIF rotation applied, long side at most
     * [MAX_PHOTO_SIDE]. A saved alignment is kept as the starting point for re-aligning, and the
     * straightened image is regenerated with it.
     */
    suspend fun importPhoto(uri: Uri, geometry: WallGeometry?): PhotoResult = mutex.withLock {
        working {
            val photo = decodeUpright(uri) ?: return@working PhotoResult.Failed("That file couldn't be read as a photo.")
            try {
                writeAtomically(photoFile) { photo.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
            } finally {
                photo.recycle()
            }

            val alignment = _state.value.alignment
            var rectified: ImageBitmap? = null
            if (alignment != null && geometry != null) {
                when (val result = straighten(alignment, geometry)) {
                    is Straightened.Ok -> rectified = result.bitmap.toImageBitmap()
                    is Straightened.Failed -> rectifiedFile.delete()
                }
            } else {
                rectifiedFile.delete()
            }
            _state.update { it.copy(hasPhoto = true, rectified = rectified) }
            PhotoResult.Done
        }
    }

    /** Saves the alignment points and regenerates the straightened image (§7.1). */
    suspend fun saveAlignment(alignment: PhotoAlignment, geometry: WallGeometry): PhotoResult = mutex.withLock {
        working {
            when (val result = straighten(alignment, geometry)) {
                is Straightened.Failed -> PhotoResult.Failed(result.message)
                is Straightened.Ok -> {
                    writeTextAtomically(alignmentFile, PhotoAlignment.encode(alignment))
                    _state.update { it.copy(alignment = alignment, rectified = result.bitmap.toImageBitmap()) }
                    PhotoResult.Done
                }
            }
        }
    }

    /** Regenerates the straightened image after the wall was resized (§6.1). */
    suspend fun regenerate(geometry: WallGeometry): PhotoResult {
        val alignment = _state.value.alignment ?: return PhotoResult.Done
        if (!_state.value.hasPhoto) return PhotoResult.Done
        return saveAlignment(alignment, geometry)
    }

    /** The photo as stored and its alignment, for a backup (§13). */
    suspend fun backupFiles(): Pair<ByteArray?, PhotoAlignment?> = mutex.withLock {
        withContext(io) {
            val photo = if (photoFile.exists()) photoFile.readBytes() else null
            photo to _state.value.alignment
        }
    }

    /**
     * Restoring a backup (§13): replaces the photo and its alignment (either may be missing) and
     * regenerates the straightened image. The files are replaced even if straightening fails.
     */
    suspend fun restore(photo: ByteArray?, alignment: PhotoAlignment?, geometry: WallGeometry): PhotoResult = mutex.withLock {
        working {
            if (photo != null) writeAtomically(photoFile) { it.write(photo) } else photoFile.delete()
            if (alignment != null) writeTextAtomically(alignmentFile, PhotoAlignment.encode(alignment)) else alignmentFile.delete()
            rectifiedFile.delete()
            var result: PhotoResult = PhotoResult.Done
            var rectified: ImageBitmap? = null
            if (photo != null && alignment != null) {
                when (val s = straighten(alignment, geometry)) {
                    is Straightened.Ok -> rectified = s.bitmap.toImageBitmap()
                    is Straightened.Failed -> result = PhotoResult.Failed(s.message)
                }
            }
            _state.update { it.copy(loaded = true, hasPhoto = photo != null, alignment = alignment, rectified = rectified) }
            result
        }
    }

    private sealed interface Straightened {
        class Ok(val bitmap: Bitmap) : Straightened
        class Failed(val message: String) : Straightened
    }

    /** Runs the rectifier and writes `wall_rectified.jpg`. Call on [io]. */
    private fun straighten(alignment: PhotoAlignment, geometry: WallGeometry): Straightened {
        val photo = BitmapFactory.decodeFile(photoFile.path) ?: return Straightened.Failed("The wall photo couldn't be read.")
        try {
            val plan = when (val p = WallRectifier.plan(geometry, alignment, photo.width, photo.height)) {
                is WallRectifier.PlanResult.Failed -> return Straightened.Failed(p.message)
                is WallRectifier.PlanResult.Ok -> p.plan
            }
            val out = WallRectifier.render(photo, plan)
            writeAtomically(rectifiedFile) { out.compress(Bitmap.CompressFormat.JPEG, RECTIFIED_QUALITY, it) }
            return Straightened.Ok(out)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't save the straightened image", e)
            return Straightened.Failed("Couldn't save the straightened image: ${e.message}")
        } finally {
            photo.recycle()
        }
    }

    private suspend fun working(block: suspend () -> PhotoResult): PhotoResult {
        _state.update { it.copy(working = true) }
        return try {
            withContext(io) {
                try {
                    block()
                } catch (e: IOException) {
                    Log.w(TAG, "Photo operation failed", e)
                    PhotoResult.Failed("Couldn't read or save the photo: ${e.message}")
                } catch (e: SecurityException) {
                    Log.w(TAG, "No access to the photo", e)
                    PhotoResult.Failed("The app wasn't allowed to read that photo.")
                } catch (e: OutOfMemoryError) {
                    PhotoResult.Failed("The photo is too large to process.")
                }
            }
        } finally {
            _state.update { it.copy(working = false) }
        }
    }

    /** Decodes [uri] with EXIF orientation applied and the long side scaled to at most [MAX_PHOTO_SIDE]. */
    private fun decodeUpright(uri: Uri): Bitmap? {
        // With inJustDecodeBounds, decodeStream always returns null and only fills in the size, so
        // its result can't tell us whether the file opened; the size can.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        // Subsample while that still leaves the long side above the limit, to keep memory down.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val scale = min(1f, MAX_PHOTO_SIDE.toFloat() / max(decoded.width, decoded.height))
        val matrix = exifMatrix(orientation).apply { postScale(scale, scale) }
        if (matrix.isIdentity) return decoded
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    private fun exifMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private fun Bitmap.toImageBitmap(): ImageBitmap {
        prepareToDraw()
        return asImageBitmap()
    }

    companion object {
        const val MAX_PHOTO_SIDE = 4096
        private const val PHOTO_QUALITY = 92
        private const val RECTIFIED_QUALITY = 90
        private const val TAG = "HomeWall/Photo"
    }
}
