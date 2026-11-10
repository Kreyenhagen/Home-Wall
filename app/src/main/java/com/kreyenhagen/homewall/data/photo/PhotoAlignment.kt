package com.kreyenhagen.homewall.data.photo

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** A point in normalized photo coordinates: (0, 0) is the top-left of the photo, (1, 1) the bottom-right. */
@Serializable(with = NormPointSerializer::class)
data class NormPoint(val x: Float, val y: Float)

/** The left and right ends of one panel edge in the photo. */
@Serializable
data class AlignmentEdge(val left: NormPoint, val right: NormPoint)

/**
 * `wall_photo.json` (§6.2): one left/right pair per panel edge, bottom to top. For the
 * two-panel wall that's the floor line, the fold, and the top edge: 6 points.
 */
@Serializable
data class PhotoAlignment(val edges: List<AlignmentEdge>) {

    companion object {
        private val json = Json { prettyPrint = true }

        /**
         * The starting guess (§8.6): floor at 95 % of the photo height, fold at 85 %, top at 5 %,
         * each inset 10 % from the sides. Other panel counts spread the folds evenly.
         */
        fun defaultGuess(edgeCount: Int): PhotoAlignment {
            require(edgeCount >= 2) { "Need at least two edges" }
            val ys = if (edgeCount == 3) {
                listOf(0.95f, 0.85f, 0.05f)
            } else {
                (0 until edgeCount).map { 0.95f - 0.9f * it / (edgeCount - 1) }
            }
            return PhotoAlignment(ys.map { AlignmentEdge(NormPoint(0.1f, it), NormPoint(0.9f, it)) })
        }

        fun parse(text: String): PhotoAlignment? = try {
            json.decodeFromString<PhotoAlignment>(text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        fun encode(alignment: PhotoAlignment): String = json.encodeToString(serializer(), alignment)
    }
}

/** Writes a point as a two-number array, `[x, y]`, as in §6.2. */
object NormPointSerializer : KSerializer<NormPoint> {
    private val delegate = ListSerializer(Float.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("com.kreyenhagen.homewall.NormPoint", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: NormPoint) {
        encoder.encodeSerializableValue(delegate, listOf(value.x, value.y))
    }

    override fun deserialize(decoder: Decoder): NormPoint {
        val values = decoder.decodeSerializableValue(delegate)
        if (values.size != 2) throw SerializationException("A point needs exactly 2 numbers, got ${values.size}")
        return NormPoint(values[0], values[1])
    }
}
