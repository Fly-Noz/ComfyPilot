package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowCacheTest {

    @Test
    fun `moving a node does not count as significant`() {
        val old = workflow(nodePos = 0)
        val new = workflow(nodePos = 400)
        assertFalse(WorkflowDiffer.compare(old, new).significant)
    }

    @Test
    fun `changing a link is significant`() {
        val old = workflow(linkTarget = 2)
        val new = workflow(linkTarget = 3)
        val diff = WorkflowDiffer.compare(old, new)
        assertTrue(diff.significant)
        assertTrue(diff.reasons.contains("连线变化"))
    }

    @Test
    fun `changing a node mode is significant`() {
        assertTrue(
            WorkflowDiffer.compare(
                workflow(nodeMode = 0),
                workflow(nodeMode = 4),
            ).significant,
        )
    }

    private fun workflow(nodePos: Int = 0, nodeMode: Int = 0, linkTarget: Int = 2): String = """
        {
          "nodes": [
            {"id": 1, "type": "Source", "order": 0, "mode": 0, "pos": [$nodePos, 0], "size": [100, 100],
             "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}]},
            {"id": 2, "type": "SaveImage", "order": 1, "mode": 0, "pos": [200, 0], "size": [100, 100],
             "inputs": [{"name": "images", "type": "IMAGE", "link": 1}], "outputs": [], "widgets_values": []},
            {"id": 3, "type": "SaveImage", "order": 2, "mode": $nodeMode, "pos": [200, 300], "size": [100, 100],
             "inputs": [{"name": "images", "type": "IMAGE", "link": 2}], "outputs": [], "widgets_values": []}
          ],
          "links": [
            [1, 1, 0, 2, 0, "IMAGE"],
            [2, 1, 0, $linkTarget, 0, "IMAGE"]
          ],
          "groups": []
        }
    """.trimIndent()
}
