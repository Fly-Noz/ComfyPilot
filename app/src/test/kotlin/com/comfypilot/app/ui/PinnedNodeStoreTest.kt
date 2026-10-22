package com.comfypilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedNodeStoreTest {

    @Test
    fun `pinned node ids round trip in stable order`() {
        val encoded = encodePinnedNodeIds(setOf(42, 7, 19))

        assertEquals("7,19,42", encoded)
        assertEquals(setOf(42, 7, 19), decodePinnedNodeIds(encoded))
    }

    @Test
    fun `missing or blank values decode to no pins`() {
        assertEquals(emptySet<Int>(), decodePinnedNodeIds(null))
        assertEquals(emptySet<Int>(), decodePinnedNodeIds(""))
        assertEquals(emptySet<Int>(), decodePinnedNodeIds(" , "))
    }

    @Test
    fun `invalid entries are ignored`() {
        assertEquals(setOf(3, 9), decodePinnedNodeIds("3, x, 9, 3, -"))
    }
}
