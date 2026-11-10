package com.kreyenhagen.homewall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSortingTest {

    private fun route(id: Long, grade: Int?, createdAt: Long) = Route(id, "R$id", grade, emptyList(), createdAt, createdAt)

    // ids are in creation order; createdAt values are deliberately not in id order for 5 and 6.
    private val routes = listOf(
        route(1, 3, createdAt = 100),
        route(2, null, createdAt = 200),
        route(3, 7, createdAt = 300),
        route(4, 3, createdAt = 400),
        route(5, 0, createdAt = 600),
        route(6, null, createdAt = 500),
        route(7, 17, createdAt = 700),
    )

    private fun ids(sort: RouteSort, filter: GradeFilter = GradeFilter(), pinned: Set<Long> = emptySet()) =
        RouteSorting.apply(routes.shuffled(), sort, filter, pinned).map { it.id }

    @Test
    fun newestAndOldestUseCreatedAt() {
        assertEquals(listOf(7L, 5, 6, 4, 3, 2, 1), ids(RouteSort.NEWEST))
        assertEquals(listOf(1L, 2, 3, 4, 6, 5, 7), ids(RouteSort.OLDEST))
    }

    @Test
    fun gradeHighToLowWithProjectsLastAndTiesNewestFirst() {
        // V17, V7, V3 (route 4 is newer than 1), V0, then projects newest-first.
        assertEquals(listOf(7L, 3, 4, 1, 5, 6, 2), ids(RouteSort.GRADE_HIGH_TO_LOW))
    }

    @Test
    fun gradeLowToHighWithProjectsLastAndTiesNewestFirst() {
        assertEquals(listOf(5L, 4, 1, 3, 7, 6, 2), ids(RouteSort.GRADE_LOW_TO_HIGH))
    }

    @Test
    fun gradeRangeIncludesBothEnds() {
        assertEquals(listOf(4L, 3, 1), ids(RouteSort.NEWEST, GradeFilter(3, 7, includeProjects = false)))
        assertEquals(listOf(7L), ids(RouteSort.NEWEST, GradeFilter(17, 17, includeProjects = false)))
        assertEquals(listOf(5L), ids(RouteSort.NEWEST, GradeFilter(0, 0, includeProjects = false)))
    }

    @Test
    fun includeProjectsSwitch() {
        assertEquals(listOf(6L, 2), ids(RouteSort.NEWEST, GradeFilter(10, 12, includeProjects = true)))
        assertEquals(emptyList<Long>(), ids(RouteSort.NEWEST, GradeFilter(10, 12, includeProjects = false)))
        assertEquals(5, ids(RouteSort.NEWEST, GradeFilter(includeProjects = false)).size)
    }

    @Test
    fun aPinnedRouteIsKeptAtItsSortedPositionEvenWhenFilteredOut() {
        // §8.2: the Project I just saved while projects are filtered out still shows.
        val filter = GradeFilter(includeProjects = false)
        assertEquals(listOf(7L, 3, 4, 1, 5, 6), ids(RouteSort.GRADE_HIGH_TO_LOW, filter, pinned = setOf(6)))
        assertEquals(listOf(7L, 5, 6, 4, 3, 1), ids(RouteSort.NEWEST, filter, pinned = setOf(6)))
    }

    @Test
    fun filterSummaryForTheChip() {
        assertFalse(GradeFilter().isActive)
        assertEquals("", GradeFilter().summary)
        assertEquals("V3–V7 · no projects", GradeFilter(3, 7, includeProjects = false).summary)
        assertEquals("V3–V7", GradeFilter(3, 7).summary)
        assertEquals("V5", GradeFilter(5, 5).summary)
        assertEquals("No projects", GradeFilter(includeProjects = false).summary)
        assertTrue(GradeFilter(0, 16).isActive)
    }
}
