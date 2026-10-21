package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowGraphsTest {

    @Test
    fun `two groups become two independent graphs`() {
        val graphs = WorkflowGraphs.analyze(document(), DEFINITIONS)

        assertEquals(listOf("A", "B"), graphs.map { it.title })
        assertTrue(graphs[0].enabled)
        assertEquals(false, graphs[1].enabled)
        assertEquals(setOf(1, 2), graphs[0].nodeIds)
        assertEquals(setOf(3, 4), graphs[1].nodeIds)
        assertEquals(setOf(2), graphs[0].outputNodeIds)
        assertEquals(setOf(4), graphs[1].outputNodeIds)
    }

    @Test
    fun `enabling a bypassed graph forces only its nodes normal`() {
        val graphs = WorkflowGraphs.analyze(document(), DEFINITIONS)
        val graphB = graphs.first { it.title == "B" }

        val ready = PromptBuilder.build(
            document = document(),
            definitions = DEFINITIONS,
            enabledOutputIds = graphB.outputNodeIds,
            forcedNormalNodeIds = graphB.nodeIds,
        ) as? PromptBuild.Ready
        assertNotNull(ready)
        assertEquals(setOf("3", "4"), ready.prompt.keys)

        val blocked = PromptBuilder.build(
            document = document(),
            definitions = DEFINITIONS,
            enabledOutputIds = emptySet(),
        ) as? PromptBuild.Blocked
        assertNotNull(blocked)
        assertTrue(blocked.problems.any { it.message.contains("没有启用") })
    }

    @Test
    fun `fields follow the selected graph`() {
        val graphB = WorkflowGraphs.analyze(document(), DEFINITIONS).first { it.title == "B" }
        val view = WorkflowFields.build(
            document = document(),
            definitions = DEFINITIONS,
            forcedNormalNodeIds = graphB.nodeIds,
            enabledOutputIds = graphB.outputNodeIds,
        )

        assertTrue(view.nodes.first { it.id == 4 }.reachable)
        assertTrue(view.nodes.first { it.id == 3 }.reachable)
        assertEquals(false, view.nodes.first { it.id == 2 }.reachable)
    }

    private fun document(): WorkflowDocument {
        val raw = """
            {
              "nodes": [
                {"id": 1, "type": "Checkpoint", "order": 0, "mode": 0, "pos": [0, 0], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": ["a"]},
                {"id": 2, "type": "SaveImage", "order": 1, "mode": 0, "pos": [200, 0], "size": [100, 100],
                 "inputs": [{"name": "images", "type": "IMAGE", "link": 1}], "outputs": [], "widgets_values": ["x"]},
                {"id": 3, "type": "Checkpoint", "order": 2, "mode": 4, "pos": [0, 500], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": ["b"]},
                {"id": 4, "type": "SaveImage", "order": 3, "mode": 4, "pos": [200, 500], "size": [100, 100],
                 "inputs": [{"name": "images", "type": "IMAGE", "link": 2}], "outputs": [], "widgets_values": ["y"]}
              ],
              "links": [
                [1, 1, 0, 2, 0, "IMAGE"],
                [2, 3, 0, 4, 0, "IMAGE"]
              ],
              "groups": [
                {"id": 1, "title": "A", "bounding": [0, 0, 400, 200]},
                {"id": 2, "title": "B", "bounding": [0, 500, 400, 200]}
              ]
            }
        """.trimIndent()
        return WorkflowDocument(
            path = "test.json",
            name = "test",
            raw = raw,
            format = WorkflowFormat.UI,
            nodeClasses = listOf("Checkpoint", "SaveImage"),
        )
    }

    private companion object {
        val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
            "Checkpoint" to NodeDefinition(
                className = "Checkpoint",
                inputs = listOf(NodeInputDef(name = "ckpt", type = "STRING", isWidget = true)),
                outputs = listOf("IMAGE"),
            ),
            "SaveImage" to NodeDefinition(
                className = "SaveImage",
                inputs = listOf(NodeInputDef(name = "images", type = "IMAGE")),
                outputs = listOf("IMAGE"),
                isOutputNode = true,
            ),
        )
    }
}
