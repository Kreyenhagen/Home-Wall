package com.kreyenhagen.homewall.data.layout

/**
 * The LEDs on this wall that sit behind it (§2, wire-length constraints), found by hand. Start
 * mapping marks them Hidden (§12.1). They're ordinary Hidden LEDs after that, so any of them can
 * still be changed in Map LEDs, one at a time or by editing the Hidden list.
 */
val KNOWN_HIDDEN_LEDS: List<Int> = (0..40).toList() + listOf(
    45, 54, 59, 64, 69, 74, 79, 84, 85, 86, 93, 106, 113, 120, 127, 134, 141, 148, 155, 162, 169,
    200, 231, 262, 293, 325, 356, 371, 388, 420, 452, 484, 485, 517,
)

/**
 * The known hidden LEDs the Hidden list can take: in range, and not in a tile or the Unused list.
 * The Hidden list editor offers to add them back.
 */
fun WallLayout.knownHiddenLedsAvailable(known: List<Int> = KNOWN_HIDDEN_LEDS): List<Int> {
    val taken = unusedLeds.toHashSet()
    holds.forEach { taken.addAll(it.leds) }
    return known.distinct().filter { it in 0 until ledCount && it !in taken }.sorted()
}

/** LED numbers with runs shortened, e.g. "0–40, 45, 84–86". [parseLedList] reads it back. */
fun ledRanges(leds: Collection<Int>): String {
    val sorted = leds.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    fun flush() {
        parts += when (end) {
            start -> "$start"
            start + 1 -> "$start, $end"
            else -> "$start–$end"
        }
    }
    for (led in sorted.drop(1)) {
        if (led == end + 1) {
            end = led
        } else {
            flush()
            start = led
            end = led
        }
    }
    flush()
    return parts.joinToString(", ")
}

/** A range can't cover more LEDs than the protocol can address (§11.3). */
private const val MAX_RANGE = 65_536

/**
 * LED numbers and ranges, e.g. "0–40, 45, 84-86", separated by commas or spaces; a range's dash may
 * be -, – or —. Returns the LEDs sorted without repeats, or null if any part isn't a number or a
 * low-to-high range. Blank text is an empty list.
 */
fun parseLedList(text: String): List<Int>? {
    val normalized = text.replace('–', '-').replace('—', '-').replace(Regex("\\s*-\\s*"), "-")
    val leds = sortedSetOf<Int>()
    for (part in normalized.split(',', ';', ' ', '\n', '\t').filter { it.isNotBlank() }) {
        val ends = part.split('-')
        when (ends.size) {
            1 -> leds += ends[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            2 -> {
                val low = ends[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val high = ends[1].toIntOrNull() ?: return null
                if (high < low || high - low > MAX_RANGE) return null
                leds.addAll(low..high)
            }
            else -> return null
        }
    }
    return leds.toList()
}
