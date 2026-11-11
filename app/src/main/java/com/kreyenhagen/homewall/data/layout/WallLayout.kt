package com.kreyenhagen.homewall.data.layout

import kotlinx.serialization.Serializable

/**
 * The wall, its holds, and which LEDs each hold owns: `wall_layout.json` (§6.1).
 * Positions are inches on the unfolded wall: origin at the bottom-left of the kicker,
 * x to the right, y up along the surface (§5).
 */
@Serializable
data class WallLayout(
    val schemaVersion: Int = SCHEMA_VERSION,
    val placeholder: Boolean = false,
    val wall: WallGeometry,
    val ledCount: Int,
    val holds: List<Hold> = emptyList(),
    /** LEDs behind the wall (or otherwise not under a hold) that stay off. */
    val hiddenLeds: List<Int> = emptyList(),
    /** LEDs on the wall with no hold in front of them: they stay off and can't be picked for routes. */
    val unusedLeds: List<Int> = emptyList(),
) {
    /**
     * The id for a new hold made of [leds] (§6.1): its lowest LED index if no existing hold uses
     * that id, otherwise the lowest unused id ≥ [ledCount]. Existing ids never change.
     */
    fun newHoldId(leds: Collection<Int>): Int {
        require(leds.isNotEmpty()) { "A hold needs at least one LED" }
        val used = holds.mapTo(HashSet()) { it.id }
        val lowest = leds.min()
        if (lowest !in used) return lowest
        var id = ledCount
        while (id in used) id++
        return id
    }

    /**
     * Resizes the wall per §6.1 so every hold stays on the same spot of the straightened image:
     * a hold keeps its fraction of the way up its panel (which also shifts holds on higher panels
     * by the change in the panels below), and x scales with the width.
     */
    fun resized(widthIn: Float, panelHeightsIn: List<Float>): WallLayout {
        require(panelHeightsIn.size == wall.panels.size) {
            "Expected ${wall.panels.size} panel heights, got ${panelHeightsIn.size}"
        }
        val newWall = WallGeometry(
            widthIn = widthIn,
            panels = wall.panels.zip(panelHeightsIn) { panel, h -> panel.copy(heightIn = h) },
        )
        val newHolds = holds.map { hold ->
            val i = wall.panelIndexAt(hold.y)
            val fraction = (hold.y - wall.panelBottom(i)) / wall.panels[i].heightIn
            hold.copy(
                x = hold.x / wall.widthIn * widthIn,
                y = newWall.panelBottom(i) + fraction * newWall.panels[i].heightIn,
            )
        }
        return copy(wall = newWall, holds = newHolds)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Full-width panels, listed bottom to top (§6.1). */
@Serializable
data class WallGeometry(
    val widthIn: Float,
    val panels: List<Panel>,
) {
    val heightIn: Float get() = panels.fold(0f) { sum, p -> sum + p.heightIn }

    /** y of the bottom edge of panel [index]; `panelBottom(panels.size)` is the top of the wall. */
    fun panelBottom(index: Int): Float = panels.take(index).fold(0f) { sum, p -> sum + p.heightIn }

    /** y of every panel edge, bottom to top: the floor, each fold, then the top edge. */
    val edgeYs: List<Float> get() = (0..panels.size).map(::panelBottom)

    /**
     * The panel that contains height [y] (a hold's panel is derived from its y, §5).
     * A hold exactly on a fold belongs to the panel above; y outside the wall clamps to the
     * bottom or top panel.
     */
    fun panelIndexAt(y: Float): Int {
        for (i in panels.indices.reversed()) {
            if (y >= panelBottom(i)) return i
        }
        return 0
    }
}

@Serializable
data class Panel(val name: String, val heightIn: Float)

@Serializable
data class Hold(
    val id: Int,
    val x: Float,
    val y: Float,
    val leds: List<Int>,
    /** Free-text label; only used to color debug markers. */
    val group: String? = null,
    /** Ring-radius override in inches. For an oval (see [ringYIn]), half its width. */
    val ringIn: Float? = null,
    /** For an oval ring: half its height in inches. Unset means a circle. */
    val ringYIn: Float? = null,
    /** The ring's rotation in degrees, clockwise as seen on the wall. Unset means 0. */
    val ringAngle: Float? = null,
)
