package com.kreyenhagen.homewall.data.layout

/** Checks a layout against the rules in §6.1 and lists every problem, so nothing has to be fixed blind. */
object LayoutValidator {

    /** LED indices go over the wire as uint16 (§11.3). */
    private const val MAX_LED_COUNT = 65_536

    fun validate(layout: WallLayout): List<String> {
        val problems = mutableListOf<String>()
        val wall = layout.wall

        if (layout.schemaVersion != WallLayout.SCHEMA_VERSION) {
            problems += "Unsupported schemaVersion ${layout.schemaVersion} (this app reads ${WallLayout.SCHEMA_VERSION})."
        }

        if (!(wall.widthIn > 0f) || wall.widthIn.isInfinite()) problems += "Wall width must be positive (got ${wall.widthIn})."
        if (wall.panels.isEmpty()) problems += "The wall needs at least one panel."
        wall.panels.forEachIndexed { i, panel ->
            val name = panel.name.ifBlank { "#${i + 1}" }
            if (!(panel.heightIn > 0f) || panel.heightIn.isInfinite()) {
                problems += "Panel $name: height must be positive (got ${panel.heightIn})."
            }
        }
        val geometryOk = problems.isEmpty()

        val ledCount = layout.ledCount
        val ledCountOk = ledCount in 1..MAX_LED_COUNT
        if (!ledCountOk) problems += "ledCount must be between 1 and $MAX_LED_COUNT (got $ledCount)."

        val holdIds = mutableSetOf<Int>()
        val ledOwner = mutableMapOf<Int, Int>()
        for (hold in layout.holds) {
            val h = "Hold ${hold.id}"
            if (hold.id < 0) problems += "$h: id must not be negative."
            if (!holdIds.add(hold.id)) problems += "$h: duplicate id."
            if (hold.leds.isEmpty()) problems += "$h: has no LEDs."
            for (led in hold.leds) {
                if (ledCountOk && led !in 0 until ledCount) {
                    problems += "$h: LED $led is out of range (0–${ledCount - 1})."
                    continue
                }
                val owner = ledOwner.put(led, hold.id)
                if (owner != null) {
                    problems += if (owner == hold.id) "$h: lists LED $led twice." else "LED $led is in both hold $owner and hold ${hold.id}."
                }
            }
            if (hold.x.isNaN() || hold.y.isNaN()) {
                problems += "$h: position is not a number."
            } else if (geometryOk && (hold.x !in 0f..wall.widthIn || hold.y !in 0f..wall.heightIn)) {
                problems += "$h: position (${hold.x}, ${hold.y}) is off the wall (${wall.widthIn} × ${wall.heightIn} in)."
            }
            hold.ringIn?.let { if (!(it > 0f)) problems += "$h: ringIn must be positive (got $it)." }
        }

        val hidden = mutableSetOf<Int>()
        for (led in layout.hiddenLeds) {
            when {
                ledCountOk && led !in 0 until ledCount -> problems += "hiddenLeds: LED $led is out of range (0–${ledCount - 1})."
                !hidden.add(led) -> problems += "hiddenLeds: LED $led is listed twice."
                led in ledOwner -> problems += "LED $led is in both hold ${ledOwner[led]} and hiddenLeds."
            }
        }

        return problems
    }
}
