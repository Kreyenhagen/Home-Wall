package com.kreyenhagen.homewall.data.layout

/**
 * Development layout used until the real wall is mapped (§6.4): the real wall's two panels,
 * 11 columns × 14 rows, one LED per hold, numbered serpentine from the bottom-left.
 */
object PlaceholderLayout {
    val COLUMNS_X: List<Float> = (1..11).map { it * 8f } // 8, 16, … 88
    val ROWS_Y: List<Float> = listOf(4f, 11f) + (0 until 12).map { 19f + it * 8f } // kicker 4, 11; main 19, 27, … 107

    fun create(): WallLayout {
        val holds = ROWS_Y.flatMapIndexed { row, y ->
            val columns = if (row % 2 == 0) COLUMNS_X else COLUMNS_X.reversed()
            columns.mapIndexed { col, x ->
                val led = row * COLUMNS_X.size + col
                Hold(id = led, x = x, y = y, leds = listOf(led))
            }
        }
        return WallLayout(
            placeholder = true,
            wall = WallGeometry(
                widthIn = 96f,
                panels = listOf(Panel("Kicker", 15f), Panel("Main", 99f)),
            ),
            ledCount = holds.size,
            holds = holds,
        )
    }
}
