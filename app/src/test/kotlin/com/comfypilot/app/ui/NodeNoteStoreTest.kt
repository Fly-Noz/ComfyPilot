package com.comfypilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeNoteStoreTest {

    @Test
    fun `notes round trip sorted by node id`() {
        val encoded = encodeNodeNotes(mapOf(42 to "首图", 7 to "提示词"))

        assertEquals("7\t提示词\n42\t首图", encoded)
        assertEquals(mapOf(42 to "首图", 7 to "提示词"), decodeNodeNotes(encoded))
    }

    @Test
    fun `blank or malformed values decode to no notes`() {
        assertEquals(emptyMap<Int, String>(), decodeNodeNotes(null))
        assertEquals(emptyMap<Int, String>(), decodeNodeNotes(""))
        assertEquals(emptyMap<Int, String>(), decodeNodeNotes("oops\n12\t\n\tx"))
    }

    @Test
    fun `tabs and newlines in the label are flattened`() {
        val encoded = encodeNodeNotes(mapOf(3 to "a\tb\nc"))

        assertEquals("3\ta b c", encoded)
        assertEquals(mapOf(3 to "a b c"), decodeNodeNotes(encoded))
    }
}