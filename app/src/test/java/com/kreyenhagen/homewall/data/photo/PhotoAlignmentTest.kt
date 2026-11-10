package com.kreyenhagen.homewall.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAlignmentTest {

    @Test
    fun parsesTheDocFormat() {
        val text = """
            {
              "edges": [
                { "left": [0.12, 0.95], "right": [0.88, 0.95] },
                { "left": [0.11, 0.84], "right": [0.89, 0.84] },
                { "left": [0.03, 0.04], "right": [0.97, 0.04] }
              ]
            }
        """.trimIndent()
        val alignment = PhotoAlignment.parse(text) ?: error("didn't parse")
        assertEquals(3, alignment.edges.size)
        assertEquals(NormPoint(0.11f, 0.84f), alignment.edges[1].left)
        assertEquals(NormPoint(0.97f, 0.04f), alignment.edges[2].right)
    }

    @Test
    fun writesPointsAsTwoNumberArraysAndRoundTrips() {
        val alignment = PhotoAlignment.defaultGuess(3)
        val text = PhotoAlignment.encode(alignment)
        assertTrue(text.replace(Regex("\\s"), "").contains("\"left\":[0.1,0.95]"))
        assertEquals(alignment, PhotoAlignment.parse(text))
    }

    @Test
    fun defaultGuessMatchesTheSpec() {
        val edges = PhotoAlignment.defaultGuess(3).edges
        assertEquals(listOf(0.95f, 0.85f, 0.05f), edges.map { it.left.y })
        assertEquals(edges.map { it.left.y }, edges.map { it.right.y })
        assertTrue(edges.all { it.left.x == 0.1f && it.right.x == 0.9f })
    }

    @Test
    fun badFilesGiveNull() {
        assertNull(PhotoAlignment.parse("{ \"edges\": [ { \"left\": [0.1], \"right\": [0.9, 0.9] } ] }"))
        assertNull(PhotoAlignment.parse("nope"))
    }
}
