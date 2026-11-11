package com.kreyenhagen.homewall.data.layout

/**
 * The LEDs on this wall that sit behind it (§2, wire-length constraints), found by hand. Start
 * mapping marks them Hidden (§12.1). They're ordinary Hidden LEDs after that, so any of them can
 * still be changed in Map LEDs.
 */
val KNOWN_HIDDEN_LEDS: List<Int> = (0..40).toList() + listOf(
    45, 54, 59, 64, 69, 74, 79, 84, 85, 86, 93, 106, 113, 120, 127, 134, 141, 148, 155, 162, 169,
    200, 231, 262, 293, 325, 356, 371, 388, 420, 452, 484, 485, 517,
)

/** The LEDs in [known] that this layout could still mark Hidden: in range and not mapped yet. */
fun WallLayout.knownHiddenLedsToMark(known: List<Int> = KNOWN_HIDDEN_LEDS): List<Int> {
    val mapped = HashSet<Int>(hiddenLeds)
    holds.forEach { mapped.addAll(it.leds) }
    return known.distinct().filter { it in 0 until ledCount && it !in mapped }.sorted()
}

/** The LEDs in [known] that are in tiles, which marking them Hidden leaves alone. */
fun WallLayout.knownHiddenLedsInTiles(known: List<Int> = KNOWN_HIDDEN_LEDS): List<Int> {
    val inTiles = HashSet<Int>()
    holds.forEach { inTiles.addAll(it.leds) }
    return known.distinct().filter { it in inTiles }.sorted()
}

/** Marks the unmapped LEDs in [leds] Hidden. LEDs in tiles, or past the LED count, are left alone. */
fun WallLayout.withUnmappedLedsHidden(leds: List<Int>): WallLayout {
    val add = knownHiddenLedsToMark(leds)
    if (add.isEmpty()) return this
    return copy(hiddenLeds = (hiddenLeds + add).distinct().sorted())
}

/** LED numbers with runs shortened, e.g. "0–40, 45, 84–86". */
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
