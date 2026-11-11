package com.kreyenhagen.homewall.data.layout

import java.util.Locale

/**
 * CSV import and export (§12.3), e.g. from Google Sheets → File → Download → CSV:
 * ```
 * led,x_in,y_in,hold
 * 0,4,6,
 * 1,,,
 * 7,12,22,A
 * 8,12,22,A
 * ```
 * - `x_in`/`y_in` are inches on the unfolded wall (§5). Blank x and y (with no hold key) means hidden.
 * - Blank x and y with the hold key `unused` means Unused: on the wall but with no hold. The key
 *   `unused` is kept for that, so it can't name a hold.
 * - Rows sharing a `hold` key form one hold, positioned at the first such row's x/y; later rows of
 *   the same hold may leave x/y blank. A blank key means the LED is its own hold.
 * - LEDs missing from the file are unmapped. `ledCount` is the highest `led` + 1.
 *
 * The file has no wall size, so the panels come from the current layout. Hold ids follow §6.1
 * (each hold's lowest LED index). Columns are found by their header names, in any order.
 */
object CsvLayout {

    sealed interface ParseResult {
        data class Ok(val layout: WallLayout) : ParseResult
        data class Failed(val problems: List<String>) : ParseResult
    }

    private const val MAX_PROBLEMS = 50

    /** Some editors (and Excel) start text files with a byte-order mark; it isn't part of the data. */
    val BYTE_ORDER_MARK: String = Char(0xFEFF).toString()

    fun parse(text: String, wall: WallGeometry): ParseResult {
        val rows = parseRows(text.removePrefix(BYTE_ORDER_MARK))
            .withIndex()
            .filterNot { (_, cells) -> cells.all { it.isBlank() } }
        if (rows.isEmpty()) return ParseResult.Failed(listOf("The file is empty."))

        val header = rows.first().value.map { it.trim().lowercase(Locale.ROOT) }
        val ledCol = header.indexOf("led")
        val xCol = header.indexOf("x_in")
        val yCol = header.indexOf("y_in")
        val holdCol = header.indexOf("hold")
        if (ledCol < 0 || xCol < 0 || yCol < 0) {
            return ParseResult.Failed(listOf("The first row must be the header: led,x_in,y_in,hold (hold is optional)."))
        }

        val problems = mutableListOf<String>()
        val seenLeds = HashSet<Int>()
        val hidden = mutableListOf<Int>()
        val unused = mutableListOf<Int>()
        // Holds in file order: key → (position, leds). Unkeyed rows get a unique key each.
        val holdPositions = LinkedHashMap<String, Pair<Float, Float>>()
        val holdLeds = LinkedHashMap<String, MutableList<Int>>()

        for ((index, cells) in rows.drop(1)) {
            val line = index + 1
            fun cell(col: Int) = cells.getOrNull(col)?.trim().orEmpty()

            val led = cell(ledCol).toIntOrNull()
            if (led == null || led < 0) {
                problems += "Line $line: led \"${cell(ledCol)}\" isn't a whole number ≥ 0."
                continue
            }
            if (!seenLeds.add(led)) {
                problems += "Line $line: LED $led is listed more than once."
                continue
            }
            val key = if (holdCol >= 0) cell(holdCol) else ""
            val xText = cell(xCol)
            val yText = cell(yCol)

            if (key.equals(UNUSED_KEY, ignoreCase = true)) {
                if (xText.isEmpty() && yText.isEmpty()) {
                    unused += led
                } else {
                    problems += "Line $line: LED $led is marked $UNUSED_KEY, so leave x_in and y_in blank."
                }
                continue
            }
            if (xText.isEmpty() && yText.isEmpty()) {
                when {
                    key.isEmpty() -> hidden += led
                    key in holdLeds -> holdLeds.getValue(key) += led
                    else -> problems += "Line $line: LED $led starts hold \"$key\" but has no position."
                }
                continue
            }
            val x = parseNumber(xText)
            val y = parseNumber(yText)
            if (x == null || y == null) {
                problems += "Line $line: LED $led needs both x_in and y_in as numbers (got \"$xText\", \"$yText\")."
                continue
            }
            val holdKey = if (key.isEmpty()) "\u0000led$led" else key
            holdPositions.putIfAbsent(holdKey, x to y)
            holdLeds.getOrPut(holdKey) { mutableListOf() } += led

            if (problems.size >= MAX_PROBLEMS) break
        }
        if (problems.isNotEmpty()) return ParseResult.Failed(problems.take(MAX_PROBLEMS))
        if (seenLeds.isEmpty()) return ParseResult.Failed(listOf("The file has a header but no LED rows."))

        val holds = holdLeds.map { (key, leds) ->
            val (x, y) = holdPositions.getValue(key)
            val sorted = leds.sorted()
            Hold(id = sorted.first(), x = x, y = y, leds = sorted)
        }
        val layout = WallLayout(
            wall = wall,
            ledCount = (seenLeds.maxOrNull() ?: 0) + 1,
            holds = holds.sortedBy { it.id },
            hiddenLeds = hidden.sorted(),
            unusedLeds = unused.sorted(),
        )
        val invalid = LayoutValidator.validate(layout)
        return if (invalid.isEmpty()) ParseResult.Ok(layout) else ParseResult.Failed(invalid)
    }

    /**
     * One row per mapped, hidden or unused LED, in LED order. Every hold gets its id as the key, so
     * paired LEDs stay together. Unmapped LEDs are left out. Groups and ring sizes aren't part of
     * the CSV, so JSON is the exact backup.
     */
    fun encode(layout: WallLayout): String = buildString {
        append("led,x_in,y_in,hold\n")
        val holdByLed = HashMap<Int, Hold>()
        layout.holds.forEach { hold -> hold.leds.forEach { holdByLed[it] = hold } }
        val hidden = layout.hiddenLeds.toHashSet()
        val unused = layout.unusedLeds.toHashSet()
        for (led in 0 until layout.ledCount) {
            val hold = holdByLed[led]
            when {
                hold != null -> append(led).append(',').append(number(hold.x)).append(',').append(number(hold.y))
                    .append(',').append(hold.id).append('\n')
                led in hidden -> append(led).append(",,,\n")
                led in unused -> append(led).append(",,,").append(UNUSED_KEY).append('\n')
            }
        }
    }

    /** The hold key that marks an LED Unused. */
    const val UNUSED_KEY = "unused"

    private fun parseNumber(text: String): Float? =
        text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

    private fun number(value: Float): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.rint(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /** RFC 4180-style: commas separate cells, double quotes wrap cells that contain commas or quotes. */
    internal fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    row += cell.toString()
                    cell.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    row += cell.toString()
                    cell.clear()
                    rows += row
                    row = mutableListOf()
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows
    }
}
