package com.kreyenhagen.homewall.led

/** One LED set to a palette slot (§9.1). */
data class LedEntry(val index: Int, val slot: Int)

/** What the wall shows. Entries are sorted by index, with no duplicates (§9.1). */
data class LedFrame(val entries: List<LedEntry>) {
    companion object {
        val OFF = LedFrame(emptyList())
    }
}
