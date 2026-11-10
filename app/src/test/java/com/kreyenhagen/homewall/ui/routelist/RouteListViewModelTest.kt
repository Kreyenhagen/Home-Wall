package com.kreyenhagen.homewall.ui.routelist

import com.kreyenhagen.homewall.led.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteListViewModelTest {
    @Test
    fun ledCountMismatchWarnsOnlyWhenConnected() {
        // §10.3 step 5: warn on a mismatch but keep going.
        assertEquals(
            "The controller has 550 LEDs but the wall layout has 154. LEDs past the shorter count won't light.",
            RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), 154),
        )
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), 550))
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Simulated, 154))
        assertNull(RouteListViewModel.ledCountWarning(ConnectionState.Connected("HomeWall", 550), null))
    }
}
