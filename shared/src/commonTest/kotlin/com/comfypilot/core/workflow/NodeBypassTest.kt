package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单节点旁路（关闭）开关的共享层验收测试。
 *
 * 关键约束：
 * - 显式关闭优先于图级强制启用；
 * - 关闭后的节点真的不进入 prompt，而不是只在界面上变灰；
 * - 影响探测只对"关掉会构建失败/没有输出"的节点报 breaking。
 */
class NodeBypassTest {

    @Test
    fun `explicit node bypass overrides the workflow file and is relinked`() {
        // 节点 2 在文件里是正常执行的；本次会话显式关闭它后，SaveImage 应穿透到 LoadImage。
        val ready = assertIs<PromptBuild.Ready>(
            PromptBuilder.build(document(), DEFINITIONS, bypassedNodeIds = setOf(2)),
        )

        assertEquals(setOf("1", "3"), ready.prompt.keys)
        val images = ready.prompt["3"]!!.jsonObject["inputs"]!!.jsonObject["images"]!!.jsonArray
        assertEquals("1", images[0].jsonPrimitive.content)
        assertEquals(0, images[1].jsonPrimitive.content.toInt())
    }

    @Test
    fun `explicit bypass beats graph level force normal`() {
        // 默认选中的节点图会把 1..3 全部强制正常执行；显式关闭仍必须生效。
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
            bypassedNodeIds = setOf(1),
        )

        val node1 = decision.nodes.first { it.id == 1 }
        assertTrue(node1.bypassed)
        assertFalse(node1.reachable)
        assertIs<PromptBuild.Blocked>(decision.build)
        assertTrue(
            decision.problems.any { it.nodeId == 2 && it.message.contains("image") },
            "关闭上游后，下游的必填输入必须被报告：${decision.problems}",
        )
    }

    @Test
    fun `impact probe reports an output node as breaking`() {
        val impact = WorkflowDecisions.probeNodeDisable(
            document = document(),
            definitions = DEFINITIONS,
            nodeId = 3,
        )

        assertTrue(impact.breaking)
        assertEquals("该节点是输出节点，关闭后没有可执行的输出", impact.reason)
    }

    @Test
    fun `impact probe ignores a leaf load image that nothing requires`() {
        // 节点 4 是画布上孤立、没人引用的 LoadImage：关闭它不影响输出。
        val impact = WorkflowDecisions.probeNodeDisable(
            document = document(),
            definitions = DEFINITIONS,
            nodeId = 4,
        )

        assertFalse(impact.breaking)
        assertNull(impact.reason)
    }

    @Test
    fun `saved bypass and mute modes show up as disabled`() {
        val decision = WorkflowDecisions.evaluate(
            document = savedDisabledDocument(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
        )

        val node2 = decision.nodes.first { it.id == 2 }
        assertEquals(UiWorkflowParser.MODE_BYPASS, node2.mode)
        assertFalse(node2.enabled, "文件里保存为旁路的节点必须显示为关闭")
        val node4 = decision.nodes.first { it.id == 4 }
        assertEquals(UiWorkflowParser.MODE_MUTED, node4.mode)
        assertFalse(node4.enabled, "文件里保存为静音的节点必须显示为关闭")
        assertTrue(decision.nodes.first { it.id == 1 }.enabled)
        // 被旁路的节点不参与 prompt（ImageScale 穿透到 LoadImage）。
        val ready = assertIs<PromptBuild.Ready>(decision.build)
        assertEquals(setOf("1", "3"), ready.prompt.keys)
    }

    @Test
    fun `enabling a saved bypass node sets its effective mode normal`() {
        // 用户点开文件里保存为旁路的节点：通过 enabledNodeIds 覆盖为正常执行。
        val decision = WorkflowDecisions.evaluate(
            document = savedDisabledDocument(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
            enabledNodeIds = setOf(2),
        )

        val node2 = decision.nodes.first { it.id == 2 }
        assertEquals(UiWorkflowParser.MODE_NORMAL, node2.mode)
        assertTrue(node2.enabled)
        val ready = assertIs<PromptBuild.Ready>(decision.build)
        assertEquals(setOf("1", "2", "3"), ready.prompt.keys)
    }

    @Test
    fun `node bypass does not reorder the input list`() {
        val document = document()
        val before = WorkflowDecisions.evaluate(
            document = document,
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
        )
        // 关闭上游节点会让它和下游都不可提交，但顺序必须保持工作流原文顺序。
        val after = WorkflowDecisions.evaluate(
            document = document,
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
            bypassedNodeIds = setOf(1),
        )

        assertEquals(listOf(1, 2, 3, 4), before.nodes.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), after.nodes.map { it.id })
        assertFalse(after.nodes.first { it.id == 1 }.reachable)
        assertFalse(after.canSubmit)
    }

    @Test
    fun `pinned bypassed node keeps its pinned position`() {
        val decision = WorkflowDecisions.evaluate(
            document = document(),
            definitions = DEFINITIONS,
            nodeInfoRequested = CLASSES,
            pinnedNodeIds = setOf(2),
            bypassedNodeIds = setOf(2),
        )

        // 置顶顺序也只看置顶本身，不因关闭而把节点踢到末尾。
        assertEquals(listOf(2, 1, 3, 4), decision.nodes.map { it.id })
    }

    private fun document(): WorkflowDocument {
        val raw = """
            {
              "nodes": [
                {"id": 1, "type": "LoadImage", "order": 0, "mode": 0, "pos": [0, 0], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
                 "widgets_values": ["a.png", "image"]},
                {"id": 2, "type": "ImageScale", "order": 1, "mode": 0, "pos": [200, 0], "size": [100, 100],
                 "inputs": [{"name": "image", "type": "IMAGE", "link": 1}],
                 "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": []},
                {"id": 3, "type": "SaveImage", "order": 2, "mode": 0, "pos": [400, 0], "size": [100, 100],
                 "inputs": [{"name": "images", "type": "IMAGE", "link": 2}], "outputs": [],
                 "widgets_values": ["out"]},
                {"id": 4, "type": "LoadImage", "order": 3, "mode": 0, "pos": [0, 400], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
                 "widgets_values": ["unused.png", "image"]}
              ],
              "links": [
                [1, 1, 0, 2, 0, "IMAGE"],
                [2, 2, 0, 3, 0, "IMAGE"]
              ]
            }
        """.trimIndent()
        return WorkflowDocument(
            path = "node-bypass.json",
            name = "node-bypass",
            raw = raw,
            format = WorkflowFormat.UI,
            nodeClasses = CLASSES.toList(),
        )
    }

    /**
     * 默认就启用的节点图里，2 号节点在文件中保存为旁路（4），4 号为静音（2）。
     *
     * 用来验证开关读的是“生效后的 mode”，而不是只看本次会话的覆盖集合。
     */
    private fun savedDisabledDocument(): WorkflowDocument {
        val raw = """
            {
              "nodes": [
                {"id": 1, "type": "LoadImage", "order": 0, "mode": 0, "pos": [0, 0], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
                 "widgets_values": ["a.png", "image"]},
                {"id": 2, "type": "ImageScale", "order": 1, "mode": 4, "pos": [200, 0], "size": [100, 100],
                 "inputs": [{"name": "image", "type": "IMAGE", "link": 1}],
                 "outputs": [{"name": "IMAGE", "type": "IMAGE"}], "widgets_values": []},
                {"id": 3, "type": "SaveImage", "order": 2, "mode": 0, "pos": [400, 0], "size": [100, 100],
                 "inputs": [{"name": "images", "type": "IMAGE", "link": 2}], "outputs": [],
                 "widgets_values": ["out"]},
                {"id": 4, "type": "LoadImage", "order": 3, "mode": 2, "pos": [0, 400], "size": [100, 100],
                 "inputs": [], "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
                 "widgets_values": ["muted.png", "image"]}
              ],
              "links": [
                [1, 1, 0, 2, 0, "IMAGE"],
                [2, 2, 0, 3, 0, "IMAGE"]
              ]
            }
        """.trimIndent()
        return WorkflowDocument(
            path = "saved-disabled.json",
            name = "saved-disabled",
            raw = raw,
            format = WorkflowFormat.UI,
            nodeClasses = CLASSES.toList(),
        )
    }

    private companion object {
        val CLASSES = setOf("LoadImage", "ImageScale", "SaveImage")

        val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
            "LoadImage" to NodeDefinition(
                className = "LoadImage",
                inputs = listOf(
                    NodeInputDef(
                        name = "image",
                        type = "COMBO",
                        isWidget = true,
                        imageUpload = true,
                    ),
                ),
                outputs = listOf("IMAGE", "MASK"),
            ),
            "ImageScale" to NodeDefinition(
                className = "ImageScale",
                inputs = listOf(NodeInputDef(name = "image", type = "IMAGE")),
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