package com.kreyenhagen.homewall.data.layout

import kotlin.math.roundToInt

/**
 * The edits Wall Setup makes to a layout (§12.1, §12.2). Pure functions: each returns a new
 * layout and leaves the old one alone. Hold ids never change; a hold left with no LEDs is deleted.
 */

/** Where one LED is assigned (§6.1). */
sealed interface LedAssignment {
    data class InHold(val hold: Hold) : LedAssignment
    data object Hidden : LedAssignment
    data object Unmapped : LedAssignment
}

fun WallLayout.assignmentOf(led: Int): LedAssignment {
    holds.firstOrNull { led in it.leds }?.let { return LedAssignment.InHold(it) }
    return if (led in hiddenLeds) LedAssignment.Hidden else LedAssignment.Unmapped
}

/** Unassigns [led]: takes it out of its hold (deleting the hold if that empties it) and out of hiddenLeds. */
fun WallLayout.withoutLed(led: Int): WallLayout = copy(
    holds = holds.mapNotNull { hold ->
        when {
            led !in hold.leds -> hold
            hold.leds.size == 1 -> null
            else -> hold.copy(leds = hold.leds - led)
        }
    },
    hiddenLeds = hiddenLeds - led,
)

/**
 * Map LEDs → tap an empty spot (§12.1): a new hold at ([x], [y]) containing just [led]. Any old
 * assignment of the LED is replaced first, so a hold emptied by that frees its id for the new one.
 */
fun WallLayout.withLedInNewHold(led: Int, x: Float, y: Float): WallLayout {
    val base = withoutLed(led)
    val hold = Hold(id = base.newHoldId(listOf(led)), x = roundPosition(x), y = roundPosition(y), leds = listOf(led))
    return base.copy(holds = base.holds + hold)
}

/** Map LEDs → tap an existing hold (§12.1): pairs [led] with that hold. */
fun WallLayout.withLedAddedToHold(led: Int, holdId: Int): WallLayout {
    if (holds.none { it.id == holdId }) return this
    val base = withoutLed(led)
    return base.copy(
        holds = base.holds.map { if (it.id == holdId) it.copy(leds = (it.leds + led).sorted()) else it },
    )
}

/** Map LEDs → Hidden (§12.1). */
fun WallLayout.withLedHidden(led: Int): WallLayout {
    val base = withoutLed(led)
    return base.copy(hiddenLeds = (base.hiddenLeds + led).sorted())
}

/** Review → Move (§12.2). The position is kept on the wall. */
fun WallLayout.withHoldMoved(holdId: Int, x: Float, y: Float): WallLayout = copy(
    holds = holds.map {
        if (it.id == holdId) {
            it.copy(x = roundPosition(x.coerceIn(0f, wall.widthIn)), y = roundPosition(y.coerceIn(0f, wall.heightIn)))
        } else {
            it
        }
    },
)

/** Review → Remove LED from hold (§12.2): the LED becomes unmapped. */
fun WallLayout.withLedRemovedFromHold(holdId: Int, led: Int): WallLayout {
    val hold = holds.firstOrNull { it.id == holdId } ?: return this
    if (led !in hold.leds) return this
    return withoutLed(led)
}

/**
 * Why a tile (hold) can't have exactly [leds], or null if it can (Map LEDs → Edit LEDs, §12.1).
 * An LED already in another tile, or marked Hidden, is refused rather than moved.
 */
fun WallLayout.holdLedsProblem(holdId: Int, leds: List<Int>): String? {
    if (holds.none { it.id == holdId }) return "That tile no longer exists."
    if (leds.isEmpty()) return "A tile needs at least one LED. Use Delete tile to remove it."
    leds.firstOrNull { it !in 0 until ledCount }?.let { return "There's no LED $it. LEDs go from 0 to ${ledCount - 1}." }
    for (led in leds.distinct()) {
        when (val a = assignmentOf(led)) {
            is LedAssignment.InHold -> if (a.hold.id != holdId) return "LED $led is already in another tile."
            LedAssignment.Hidden -> return "LED $led is marked Hidden. To use it, go to LED $led and tap this tile."
            LedAssignment.Unmapped -> Unit
        }
    }
    return null
}

/** Map LEDs → Edit LEDs (§12.1): the tile keeps its id and position and gets [leds]. Check [holdLedsProblem] first. */
fun WallLayout.withHoldLeds(holdId: Int, leds: List<Int>): WallLayout =
    copy(holds = holds.map { if (it.id == holdId) it.copy(leds = leds.distinct().sorted()) else it })

/** What a tile shows (§7.3): its LED numbers, e.g. "37" or "37, 38". */
val Hold.ledsLabel: String get() = leds.joinToString(", ")

/** Review → Delete hold (§12.2): its LEDs become unmapped. */
fun WallLayout.withoutHold(holdId: Int): WallLayout = copy(holds = holds.filterNot { it.id == holdId })

/**
 * A new LED count (§12.1). LEDs past the new count are dropped from holds and hiddenLeds; holds
 * left with no LEDs are deleted. Raising the count just adds unmapped LEDs.
 */
fun WallLayout.withLedCount(count: Int): WallLayout {
    require(count >= 1) { "ledCount must be at least 1" }
    return copy(
        ledCount = count,
        holds = holds.mapNotNull { hold ->
            val kept = hold.leds.filter { it < count }
            if (kept.isEmpty()) null else hold.copy(leds = kept)
        },
        hiddenLeds = hiddenLeds.filter { it < count },
    )
}

/**
 * What lowering the LED count to [count] would unmap, e.g. "LEDs 500–549 lose their mapping
 * (12 in holds, 3 hidden)." Null if nothing would be lost.
 */
fun WallLayout.ledCountLossNote(count: Int): String? {
    if (count >= ledCount) return null
    val inHolds = holds.sumOf { h -> h.leds.count { it >= count } }
    val hidden = hiddenLeds.count { it >= count }
    if (inHolds + hidden == 0) return null
    return "LEDs $count–${ledCount - 1} lose their mapping ($inHolds in holds, $hidden hidden)."
}

/**
 * Start mapping on the placeholder (§6.4, §12.1): an empty real layout with the same panels. The
 * LEDs in [hidden] that are below [ledCount] start Hidden; every other LED starts unmapped.
 */
fun WallLayout.startedMapping(ledCount: Int, hidden: List<Int> = KNOWN_HIDDEN_LEDS): WallLayout {
    require(ledCount >= 1) { "ledCount must be at least 1" }
    return copy(
        placeholder = false,
        ledCount = ledCount,
        holds = emptyList(),
        hiddenLeds = hidden.filter { it in 0 until ledCount }.distinct().sorted(),
    )
}

/** The first LED that's in neither a hold nor hiddenLeds, at or after [from], or null if there is none. */
fun WallLayout.firstUnmappedLed(from: Int = 0): Int? {
    val mapped = HashSet<Int>(hiddenLeds)
    holds.forEach { mapped.addAll(it.leds) }
    return (from.coerceAtLeast(0) until ledCount).firstOrNull { it !in mapped }
}

/** The Review step's summary line (§12.2). */
data class MappingSummary(val ledCount: Int, val holds: Int, val ledsInHolds: Int, val hidden: Int) {
    val unmapped: Int get() = ledCount - ledsInHolds - hidden

    /** e.g. "550 LEDs · 380 holds (402 LEDs) · 148 hidden" (the unmapped count is shown separately). */
    val text: String
        get() = "$ledCount ${if (ledCount == 1) "LED" else "LEDs"} · $holds ${if (holds == 1) "hold" else "holds"} " +
            "($ledsInHolds ${if (ledsInHolds == 1) "LED" else "LEDs"}) · $hidden hidden"

    companion object {
        fun of(layout: WallLayout) = MappingSummary(
            ledCount = layout.ledCount,
            holds = layout.holds.size,
            ledsInHolds = layout.holds.sumOf { it.leds.size },
            hidden = layout.hiddenLeds.size,
        )
    }
}

/** Positions are kept to 0.1 in, which keeps the hand-editable JSON tidy. */
private fun roundPosition(value: Float): Float = (value * 10f).roundToInt() / 10f
