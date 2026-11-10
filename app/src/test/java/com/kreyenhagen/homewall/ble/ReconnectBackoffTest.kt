package com.kreyenhagen.homewall.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun oneTwoFourThenEveryTenSeconds() {
        // §10.4: 1 s, 2 s, 4 s, then every 10 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 10_000L, 10_000L, 10_000L), (0..5).map(ReconnectBackoff::delayMs))
        assertEquals(10_000L, ReconnectBackoff.delayMs(Int.MAX_VALUE))
    }
}
