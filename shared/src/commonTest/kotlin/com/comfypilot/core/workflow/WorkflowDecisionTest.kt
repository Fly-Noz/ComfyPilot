package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 共享决策层的验收测试：两端拿到的顺序、canSubmit、阻断原因、警告和 prompt
 * 必须由同一份输入得到同一份结果。
 */
class WorkflowDecisionTest {

    @Test
    fun `same graph toggles and overrides produce the same decision`() {
        val first = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            requestedGraphIds = setOf("c3"),
            pinnedNodeIds = setOf(3),
            overrides = mapOf(3 to mapOf("ckpt" to JsonPrimitive("branch-b"))),
            nodeInfoRequested = CLASSES,
        )
        val second = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            requestedGraphIds = setOf("c3"),
            pinnedNodeIds = setOf(3),
            overrides = mapOf(3 to mapOf("ckpt" to JsonPrimitive("branch-b"))),
            nodeInfoRequested = CLASSES,
        )

        assertEquals(second.canSubmit, first.canSubmit)
        assertEquals(second.problems, first.problems)
        assertEquals(second.warnings, first.warnings)
        assertEquals(second.nodes.map { it.id }, first.nodes.map { it.id })
        assertEquals(promptText(first, nodeId = "4"), promptText(second, nodeId = "4"))
        assertEquals("c3", first.selectedGraphIds.single())
        assertTrue(first.canSubmit)
        assertTrue(first.problems.isEmpty())
        assertTrue(first.warnings.any { it.contains("已忽略 2 个未连接到输出节点的节点") })

        val prompt = assertIs<PromptBuild.Ready>(first.build).prompt
        assertEquals(setOf("3", "4"), prompt.keys)
        assertEquals(
            "branch-b",
            prompt["3"]!!.jsonObject["inputs"]!!.jsonObject["ckpt"]!!.jsonPrimitive.content,
        )
        // 置顶的可达节点排在最前。
        assertEquals(listOf(3, 4, 1, 2), first.nodes.map { it.id })
    }

    @Test
    fun `pinned node in a disabled graph stays last`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            pinnedNodeIds = setOf(4),
            nodeInfoRequested = CLASSES,
        )

        assertEquals(setOf("c1"), decision.selectedGraphIds)
        assertEquals(setOf(3, 4), decision.disabledNodeIds)
        assertEquals(listOf(1, 2, 3, 4), decision.nodes.map { it.id })
        assertTrue(decision.canSubmit)
    }

    @Test
    fun `disabling all graphs blocks with the shared reason`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            requestedGraphIds = emptySet(),
            nodeInfoRequested = CLASSES,
        )

        assertFalse(decision.canSubmit)
        assertIs<PromptBuild.Blocked>(decision.build)
        assertTrue(decision.problems.any { it.message.contains("没有启用任何节点图") })
        assertEquals("不可提交：${decision.problems.first().format()}", decision.blockReason)
        assertTrue(decision.warnings.isEmpty())
    }

    @Test
    fun `pending node definitions block submission and expose one reason`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = emptyMap(),
            nodeInfoRequested = emptySet(),
        )

        assertFalse(decision.canSubmit)
        assertEquals(listOf("Checkpoint", "SaveImage"), decision.pendingNodeClasses)
        assertEquals(
            "不可提交：正在加载节点定义（还有 2 个类）",
            decision.blockReason,
        )
        assertIs<PromptBuild.Blocked>(decision.build)
    }

    @Test
    fun `warnings and prompt come from the same build`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(withOrphan = true),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
        )
        val ready = assertIs<PromptBuild.Ready>(decision.build)

        assertEquals(ready.warnings, decision.warnings)
        assertTrue(decision.warnings.any { it.contains("已忽略 1 个未连接到输出节点的节点") })
        assertTrue("9" !in ready.prompt)
    }

    @Test
    fun `run token is only appended to an actual submission prompt`() {
        val view = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
        )
        val submission = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
            runToken = "run42",
        )

        assertEquals("x", promptText(view, nodeId = "2"))
        assertEquals("x_run42", promptText(submission, nodeId = "2"))
        assertEquals(view.canSubmit, submission.canSubmit)
        assertEquals(view.problems, submission.problems)
    }

    @Test
    fun `invalid override blocks through the same decision`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            overrides = mapOf(2 to mapOf("not_a_field" to JsonPrimitive(1))),
            nodeInfoRequested = CLASSES,
        )

        assertFalse(decision.canSubmit)
        assertTrue(decision.problems.any { it.message.contains("not_a_field") })
        assertEquals("不可提交：${decision.problems.first().format()}", decision.blockReason)
    }

    private fun promptText(decision: WorkflowDecision, nodeId: String): String {
        val prompt = assertNotNull((decision.build as? PromptBuild.Ready)?.prompt)
        return prompt[nodeId]!!.jsonObject["inputs"]!!.jsonObject["filename_prefix"]!!.jsonPrimitive.content
    }

    private fun document(withOrphan: Boolean = false): WorkflowDocument {
        val orphan = if (withOrphan) {
            """,
                {"id": 9, "type": "Checkpoint", "order": 4, "mode": 0, "pos": [0, 900], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": ["orphan"]}
            """
        } else {
            ""
        }
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
                $orphan
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
        val CLASSES = setOf("Checkpoint", "SaveImage")

        val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
            "Checkpoint" to NodeDefinition(
                className = "Checkpoint",
                inputs = listOf(NodeInputDef(name = "ckpt", type = "STRING", isWidget = true)),
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
