package com.kreyenhagen.homewall.util

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

/** Uppercase hex with a space between bytes, e.g. `02 08 03`. Used for packet logs and tests. */
fun ByteArray.toHex(): String = buildString(size * 3) {
    this@toHex.forEachIndexed { i, byte ->
        if (i > 0) append(' ')
        val v = byte.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}
