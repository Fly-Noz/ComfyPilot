package com.comfypilot.core.host

import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.NodeDefinition
import com.comfypilot.core.workflow.NodeInputDef
import com.comfypilot.core.workflow.WorkflowDocument
import com.comfypilot.core.workflow.WorkflowFormat
import com.comfypilot.core.workflow.WorkflowSelection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 鸿蒙侧按需工作流视图：确保 ArkTS 拿到的是可直接渲染和提交的模型，
 * 而不是在宿主里重写一遍节点图、字段排序和 overrides 规则。
 */
class WorkflowUiTest {

    @Test
    fun `view sorts pinned reachable nodes first and keeps disabled graphs last`() {
        val response = Json.parseToJsonElement(
            sessionWorkflowJson(state(), """{"op":"view","pinned":[2,4]}"""),
        ).jsonObject

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("2", "1", "3", "4"), response["nodes"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        })
        assertEquals(listOf("c1"), response["selectedGraphIds"]!!.jsonArray.map {
            it.jsonPrimitive.content
        })
        assertTrue(response["canSubmit"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("", response["blockReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `selected graph changes reachable fields and prompt`() {
        val request = """{"op":"prompt","graphs":["c3"],"overrides":{"3":{"ckpt":"branch-b"}}}"""
        val response = Json.parseToJsonElement(sessionWorkflowJson(state(), request)).jsonObject

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        val prompt = response["prompt"]!!.jsonObject
        assertEquals(setOf("3", "4"), prompt.keys)
        assertEquals(
            "branch-b",
            prompt["3"]!!.jsonObject["inputs"]!!.jsonObject["ckpt"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `prompt run token is appended to save prefix`() {
        val request = """{"op":"prompt","run":"run42"}"""
        val response = Json.parseToJsonElement(sessionWorkflowJson(state(), request)).jsonObject
        val prompt = response["prompt"]!!.jsonObject
        val prefix = prompt["2"]!!.jsonObject["inputs"]!!.jsonObject["filename_prefix"]!!.jsonPrimitive.content
        assertEquals("x_run42", prefix)
    }

    @Test
    fun `pending node definitions block submission but not the view`() {
        val pending = state(nodeInfoRequested = emptySet(), nodeInfo = emptyMap())
        val view = Json.parseToJsonElement(
            sessionWorkflowJson(pending, """{"op":"view"}"""),
        ).jsonObject
        assertFalse(view["canSubmit"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("Checkpoint", "SaveImage"),
            view["pendingNodeClasses"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "不可提交：正在加载节点定义（还有 2 个类）",
            view["blockReason"]!!.jsonPrimitive.content,
        )

        val prompt = Json.parseToJsonElement(
            sessionWorkflowJson(pending, """{"op":"prompt"}"""),
        ).jsonObject
        assertEquals("waiting", prompt["state"]!!.jsonPrimitive.content)
        assertFalse(prompt["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `view and prompt report the same blocked graph reason`() {
        val request = """{"op":"view","graphs":[]}"""
        val view = Json.parseToJsonElement(sessionWorkflowJson(state(), request)).jsonObject
        assertFalse(view["canSubmit"]!!.jsonPrimitive.content.toBoolean())
        val blockReason = view["blockReason"]!!.jsonPrimitive.content
        assertTrue(blockReason.startsWith("不可提交："))

        val promptRequest = """{"op":"prompt","graphs":[]}"""
        val prompt = Json.parseToJsonElement(sessionWorkflowJson(state(), promptRequest)).jsonObject
        assertEquals("blocked", prompt["state"]!!.jsonPrimitive.content)
        assertFalse(prompt["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            view["problems"]!!.jsonArray.map { it.jsonObject["display"]!!.jsonPrimitive.content },
            prompt["problems"]!!.jsonArray.map { it.jsonObject["display"]!!.jsonPrimitive.content },
        )
        val firstProblem = view["problems"]!!.jsonArray.first().jsonObject["display"]!!.jsonPrimitive.content
        assertEquals("不可提交：$firstProblem", blockReason)
        assertTrue(
            prompt["message"]!!.jsonPrimitive.content.contains("不能提交"),
            "转换失败时也要给出明确原因：${prompt["message"]}",
        )
    }

    @Test
    fun `view exposes per node bypass and explicit disable wins over graph enable`() {
        val request = """{"op":"view","graphs":["c1"],"bypassedNodeIds":[1]}"""
        val response = Json.parseToJsonElement(sessionWorkflowJson(state(), request)).jsonObject

        val nodes = response["nodes"]!!.jsonArray.associateBy {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertTrue(nodes.getValue("1").jsonObject["bypassed"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(nodes.getValue("2").jsonObject["bypassed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("1"),
            response["bypassedNodeIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // 图级强制启用不能把用户显式关闭的上游重新打开。
        assertFalse(response["canSubmit"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `view reports saved bypass mode as disabled until the user enables it`() {
        val view = Json.parseToJsonElement(
            sessionWorkflowJson(state(), """{"op":"view"}"""),
        ).jsonObject
        val nodes = view["nodes"]!!.jsonArray.associateBy {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }

        // 节点 3/4 在文件里就是旁路（mode=4）：开关必须显示为关闭，而不是按覆盖集合判断。
        assertEquals("4", nodes.getValue("3").jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(nodes.getValue("3").jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("0", nodes.getValue("1").jsonObject["mode"]!!.jsonPrimitive.content)
        assertTrue(nodes.getValue("1").jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())

        // 用户点开它：通过 enabledNodeIds 覆盖为正常执行，视图里的 mode 随之为 0。
        val enabled = Json.parseToJsonElement(
            sessionWorkflowJson(state(), """{"op":"view","enabledNodeIds":[3]}"""),
        ).jsonObject
        val enabledNode = enabled["nodes"]!!.jsonArray.first {
            it.jsonObject["id"]!!.jsonPrimitive.content == "3"
        }.jsonObject
        assertEquals("0", enabledNode["mode"]!!.jsonPrimitive.content)
        assertTrue(enabledNode["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("3"),
            enabled["enabledNodeIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `disable impact reports a breaking output node`() {
        val request = """{"op":"disable_impact","nodeId":2}"""
        val response = Json.parseToJsonElement(sessionWorkflowJson(state(), request)).jsonObject

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2", response["nodeId"]!!.jsonPrimitive.content)
        assertTrue(response["breaking"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "该节点是输出节点，关闭后没有可执行的输出",
            response["reason"]!!.jsonPrimitive.content,
        )
    }

    private fun state(
        nodeInfoRequested: Set<String> = setOf("Checkpoint", "SaveImage"),
        nodeInfo: Map<String, NodeDefinition> = DEFINITIONS,
    ): SessionState =
        SessionState(
            workflow = WorkflowSelection(document = document()),
            nodeInfo = nodeInfo,
            nodeInfoRequested = nodeInfoRequested,
        )

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
