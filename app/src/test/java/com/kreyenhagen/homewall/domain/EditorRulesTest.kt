package com.kreyenhagen.homewall.domain

import com.kreyenhagen.homewall.domain.EditorRules.TapResult
import com.kreyenhagen.homewall.domain.HoldRole.FINISH
import com.kreyenhagen.homewall.domain.HoldRole.MIDDLE
import com.kreyenhagen.homewall.domain.HoldRole.START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRulesTest {

    private fun Map<Int, HoldRole>.tapped(holdId: Int, role: HoldRole): Map<Int, HoldRole> =
        (EditorRules.tap(this, holdId, role) as TapResult.Changed).holds

    @Test
    fun tappingAnInactiveHoldAddsItWithTheSelectedRole() {
        assertEquals(mapOf(5 to START), emptyMap<Int, HoldRole>().tapped(5, START))
        assertEquals(mapOf(5 to START, 9 to MIDDLE), mapOf(5 to START).tapped(9, MIDDLE))
    }

    @Test
    fun tappingAnActiveHoldRemovesItWhateverRoleIsSelected() {
        val holds = mapOf(5 to START, 9 to MIDDLE, 12 to FINISH)
        for (selected in HoldRole.entries) {
            assertEquals(mapOf(5 to START, 12 to FINISH), holds.tapped(9, selected))
        }
    }

    @Test
    fun atMostTwoStartHolds() {
        val two = mapOf(1 to START, 2 to START)
        assertEquals(TapResult.LimitReached(START), EditorRules.tap(two, 3, START))
        // Other roles are still allowed, and removing a start hold makes room again.
        assertEquals(MIDDLE, two.tapped(3, MIDDLE)[3])
        assertEquals(mapOf(2 to START, 3 to START), two.tapped(1, START).tapped(3, START))
    }

    @Test
    fun atMostTwoFinishHolds() {
        val two = mapOf(1 to FINISH, 2 to FINISH, 3 to START, 4 to START)
        assertEquals(TapResult.LimitReached(FINISH), EditorRules.tap(two, 5, FINISH))
        assertTrue(EditorRules.tap(two, 2, FINISH) is TapResult.Changed) // removing is always fine
    }

    @Test
    fun middleHoldsAreUnlimited() {
        var holds = emptyMap<Int, HoldRole>()
        for (id in 0 until 150) holds = holds.tapped(id, MIDDLE)
        assertEquals(150, holds.size)
        assertNull(EditorRules.limitFor(MIDDLE))
    }

    @Test
    fun routeHoldsAreSortedById() {
        assertEquals(
            listOf(RouteHold(2, FINISH), RouteHold(7, START), RouteHold(40, MIDDLE)),
            EditorRules.toRouteHolds(mapOf(40 to MIDDLE, 2 to FINISH, 7 to START)),
        )
    }
}

class EditHistoryTest {

    @Test
    fun undoAndRedoStepThroughChanges() {
        val h = EditHistory(0).push(1).push(2)
        assertEquals(1, h.undo().present)
        assertEquals(0, h.undo().undo().present)
        assertEquals(2, h.undo().undo().redo().redo().present)
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun aNewChangeClearsRedo() {
        val h = EditHistory(0).push(1).push(2).undo().push(5)
        assertEquals(5, h.present)
        assertFalse(h.canRedo)
        assertEquals(1, h.undo().present)
        assertSame(h, h.redo())
    }

    @Test
    fun nothingToUndoAtTheStart() {
        val h = EditHistory("saved route")
        assertFalse(h.canUndo)
        assertSame(h, h.undo())
    }

    @Test
    fun anUnchangedValueIsNotAStep() {
        val h = EditHistory(3).push(3)
        assertFalse(h.canUndo)
    }

    @Test
    fun keepsAtMost200Steps() {
        var h = EditHistory(0)
        for (i in 1..250) h = h.push(i)
        assertEquals(200, h.past.size)
        while (h.canUndo) h = h.undo()
        assertEquals(50, h.present) // the oldest 50 steps were dropped
        assertEquals(200, h.future.size)
    }
}

class RouteNamesTest {
    @Test
    fun namesAreTrimmedAndLimitedTo40Characters() {
        assertEquals("Crimp line", RouteNames.clean("  Crimp line \n"))
        assertNull(RouteNames.clean("   "))
        assertNull(RouteNames.clean(""))
        assertEquals("x".repeat(40), RouteNames.clean("x".repeat(40)))
        assertNull(RouteNames.clean("x".repeat(41)))
    }
}
