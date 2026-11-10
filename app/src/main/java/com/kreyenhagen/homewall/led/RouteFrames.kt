package com.kreyenhagen.homewall.led

import com.kreyenhagen.homewall.data.layout.WallLayout
import com.kreyenhagen.homewall.domain.HoldRole
import com.kreyenhagen.homewall.domain.RouteHold

/**
 * Route → frame (§9.3): for each route hold that exists in the layout, every LED of that hold
 * lights in the role's slot. Holds missing from the layout are skipped.
 */
object RouteFrames {
    fun forRoute(holds: List<RouteHold>, layout: WallLayout): LedFrame =
        build(holds.map { it.holdId to it.role }, layout)

    /** The editor's draft (§8.3): holdId → role. */
    fun forDraft(holds: Map<Int, HoldRole>, layout: WallLayout): LedFrame =
        build(holds.map { (id, role) -> id to role }, layout)

    private fun build(holds: List<Pair<Int, HoldRole>>, layout: WallLayout): LedFrame {
        if (holds.isEmpty()) return LedFrame.OFF
        val byId = layout.holds.associateBy { it.id }
        return LedFrame.of(
            holds.flatMap { (holdId, role) -> byId[holdId]?.leds.orEmpty().map { LedEntry(it, role.slot) } },
        )
    }
}
