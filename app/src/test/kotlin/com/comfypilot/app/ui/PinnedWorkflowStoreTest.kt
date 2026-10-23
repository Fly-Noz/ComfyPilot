package com.comfypilot.app.ui

import com.comfypilot.core.workflow.WorkflowEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedWorkflowStoreTest {

    @Test
    fun `pinned workflows come first then path order`() {
        val entries = listOf(
            entry("/b.json"),
            entry("/a.json"),
            entry("/c.json"),
        )

        val ordered = orderWorkflowEntries(entries, setOf("/c.json"))

        assertEquals(listOf("/c.json", "/a.json", "/b.json"), ordered.map { it.path })
    }

    @Test
    fun `unknown pins do not hide entries`() {
        val entries = listOf(entry("/b.json"), entry("/a.json"))

        val ordered = orderWorkflowEntries(entries, setOf("/missing.json"))

        assertEquals(listOf("/a.json", "/b.json"), ordered.map { it.path })
    }

    private fun entry(path: String): WorkflowEntry =
        WorkflowEntry(path = path, name = path.substringAfterLast('/'))
}
