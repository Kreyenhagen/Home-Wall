package com.kreyenhagen.homewall.data.layout

/** The example from §6.1, verbatim. */
const val DOC_EXAMPLE_JSON = """
{
  "schemaVersion": 1,
  "placeholder": false,
  "wall": {
    "widthIn": 96.0,
    "panels": [
      { "name": "Kicker", "heightIn": 15.0 },
      { "name": "Main",   "heightIn": 99.0 }
    ]
  },
  "ledCount": 550,
  "holds": [
    { "id": 0,  "x": 4.0,  "y": 6.0,  "leds": [0] },
    { "id": 7,  "x": 12.0, "y": 22.0, "leds": [7, 8], "group": "main" },
    { "id": 40, "x": 8.0,  "y": 26.0, "leds": [40], "group": "aux-a", "ringIn": 2.5 }
  ],
  "hiddenLeds": [1, 2, 3, 4, 5, 6]
}
"""

val REAL_WALL = WallGeometry(96f, listOf(Panel("Kicker", 15f), Panel("Main", 99f)))

fun layoutOf(vararg holds: Hold, ledCount: Int = 550, hidden: List<Int> = emptyList()) =
    WallLayout(wall = REAL_WALL, ledCount = ledCount, holds = holds.toList(), hiddenLeds = hidden)
