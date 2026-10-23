package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `image-2` 这类辅助图组没有自己的输出节点，但必须能被单独启用，
 * 并把组内被旁路的 LoadImage/缩放节点强制回正常执行。
 */
class AuxiliaryGroupsTest {

    @Test
    fun `auxiliary image group is exposed and forces its nodes normal`() {
        val graphs = WorkflowGraphs.analyze(document(), DEFINITIONS)

        assertEquals(listOf("A", "image-2"), graphs.map { it.title })
        val main = graphs.first { it.title == "A" }
        val image2 = graphs.first { it.title == "image-2" }
        assertEquals(setOf(1, 5, 3), main.nodeIds)
        assertEquals(setOf(4), image2.nodeIds)
        assertEquals(emptySet(), image2.outputNodeIds)
        assertEquals(main.id, image2.parentId)
        assertFalse(image2.enabled)

        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            requestedGraphIds = setOf(main.id, image2.id),
            nodeInfoRequested = CLASSES,
        )

        assertTrue(decision.canSubmit)
        assertTrue(decision.nodes.first { it.id == 4 }.reachable)
        val prompt = assertIs<PromptBuild.Ready>(decision.build).prompt
        assertEquals(setOf("1", "4", "5", "3"), prompt.keys)
    }

    private fun document(): WorkflowDocument {
        val raw = """
            {
              "nodes": [
                {"id": 1, "type": "LoadImage", "order": 0, "mode": 0, "pos": [0, 0], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": ["a.png", "image"]},
                {"id": 4, "type": "LoadImage", "order": 1, "mode": 4, "pos": [0, 500], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": ["b.png", "image"]},
                {"id": 5, "type": "CollectImages", "order": 2, "mode": 0, "pos": [200, 0], "size": [100, 100],
                 "inputs": [{"name": "image_1", "type": "IMAGE", "link": 1},
                            {"name": "image_2", "type": "IMAGE", "link": 2}],
                 "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": []},
                {"id": 3, "type": "SaveImage", "order": 3, "mode": 0, "pos": [400, 0], "size": [100, 100],
                 "inputs": [{"name": "images", "type": "IMAGE", "link": 3}], "outputs": [],
                 "widgets_values": ["x"]}
              ],
              "links": [
                [1, 1, 0, 5, 0, "IMAGE"],
                [2, 4, 0, 5, 1, "IMAGE"],
                [3, 5, 0, 3, 0, "IMAGE"]
              ],
              "groups": [
                {"id": 2, "title": "A", "bounding": [0, 0, 600, 200]},
                {"id": 4, "title": "image-2", "bounding": [0, 500, 200, 200]}
              ]
            }
        """.trimIndent()
        return WorkflowDocument(
            path = "multi-image.json",
            name = "multi-image",
            raw = raw,
            format = WorkflowFormat.UI,
            nodeClasses = CLASSES.toList(),
        )
    }

    private companion object {
        val CLASSES = setOf("LoadImage", "CollectImages", "SaveImage")

        val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
            "LoadImage" to NodeDefinition(
                className = "LoadImage",
                inputs = listOf(NodeInputDef(name = "image", type = "STRING", isWidget = true)),
                outputs = listOf("IMAGE"),
            ),
            "CollectImages" to NodeDefinition(
                className = "CollectImages",
                inputs = listOf(
                    NodeInputDef(name = "image_1", type = "IMAGE"),
                    NodeInputDef(name = "image_2", type = "IMAGE"),
                ),
                outputs = listOf("IMAGE"),
            ),
            "SaveImage" to NodeDefinition(
                className = "SaveImage",
                inputs = listOf(
                    NodeInputDef(name = "images", type = "IMAGE"),
                    NodeInputDef(name = "filename_prefix", type = "STRING", isWidget = true),
                ),
                outputs = listOf("IMAGE"),
                isOutputNode = true,
            ),
        )
    }
}
