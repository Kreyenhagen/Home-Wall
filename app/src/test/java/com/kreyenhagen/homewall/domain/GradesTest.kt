package com.kreyenhagen.homewall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesTest {

    @Test
    fun labels() {
        assertEquals("Project", Grades.label(null))
        assertEquals("V0", Grades.label(0))
        assertEquals("V17", Grades.label(17))
    }

    @Test
    fun defaultFilterIsInactive() {
        assertFalse(GradeFilter().isActive)
        assertTrue(GradeFilter(minGrade = 1).isActive)
        assertTrue(GradeFilter(maxGrade = 16).isActive)
        assertTrue(GradeFilter(includeProjects = false).isActive)
    }
}
