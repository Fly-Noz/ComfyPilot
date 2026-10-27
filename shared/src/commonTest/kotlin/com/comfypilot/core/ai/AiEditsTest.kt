package com.comfypilot.core.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiEditsTest {

    private fun view() = AiWorkflowView(
        path = "demo.json",
        nodes = listOf(
            AiNode(
                id = 195,
                className = "SamplerCustomAdvanced",
                title = "采样器",
                enabled = true,
                reachable = true,
                fields = listOf(
                    AiField(195, "steps", "INT", JsonPrimitive(4), editable = true),
                    AiField(195, "seed", "INT", JsonPrimitive(1), editable = true),
                    AiField(195, "linked", "INT", JsonPrimitive(2), editable = false),
                ),
            ),
            AiNode(
                id = 189,
                className = "SaveVideo",
                title = "Save Video",
                enabled = true,
                reachable = true,
                fields = listOf(
                    AiField(189, "format", "STRING", JsonPrimitive("auto"), editable = true),
                ),
            ),
            AiNode(
                id = 7,
                className = "UNETLoader",
                title = "模型",
                enabled = true,
                reachable = false,
                fields = emptyList(),
            ),
        ),
        graphs = listOf(
            AiGraph("g1", "图生视频", selected = true),
            AiGraph("g2", "文生视频", selected = false),
        ),
    )

    @Test
    fun `parses and applies a mixed proposal`() {
        val proposal = AiEdits.parse(
            """
            {
              "summary": "改步数并关闭保存节点",
              "ops": [
                {"op": "set_input", "node": 195, "input": "steps", "value": 6},
                {"op": "toggle_node", "node": 189, "enabled": false},
                {"op": "select_graphs", "graphs": ["g2"]}
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(proposal)

        val result = AiEdits.apply(view(), AiEditState(), proposal)
        assertTrue(result.ok, "应当全部成功：${result.errors}")
        assertEquals(JsonPrimitive(6), result.state.overrides[195]?.get("steps"))
        assertTrue(189 in result.state.bypassed)
        assertEquals(setOf("g2"), result.state.graphs)
        assertEquals(3, result.changes.size)
    }

    @Test
    fun `rejects wrong value type and keeps state unchanged`() {
        val proposal = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"set_input","node":195,"input":"steps","value":"abc"}]}""",
        )
        assertNotNull(proposal)

        val base = AiEditState()
        val result = AiEdits.apply(view(), base, proposal)
        assertTrue(!result.ok)
        assertEquals(base, result.state)
        assertTrue(result.errors.single().contains("类型不匹配"))
    }

    @Test
    fun `rejects unknown node and linked field`() {
        val unknown = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"set_input","node":999,"input":"steps","value":6}]}""",
        )
        assertNotNull(unknown)
        assertTrue(AiEdits.apply(view(), AiEditState(), unknown).errors.single().contains("没有输入"))

        val linked = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"set_input","node":195,"input":"linked","value":6}]}""",
        )
        assertNotNull(linked)
        assertTrue(AiEdits.apply(view(), AiEditState(), linked).errors.single().contains("不可编辑"))
    }

    @Test
    fun `toggling back on clears bypass`() {
        val proposal = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"toggle_node","node":189,"enabled":true}]}""",
        )
        assertNotNull(proposal)
        val result = AiEdits.apply(view(), AiEditState(bypassed = setOf(189)), proposal)
        assertTrue(result.ok)
        assertTrue(189 !in result.state.bypassed)
        assertTrue(189 in result.state.enabled)
    }

    @Test
    fun `rejects unknown graph`() {
        val proposal = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"select_graphs","graphs":["nope"]}]}""",
        )
        assertNotNull(proposal)
        assertTrue(AiEdits.apply(view(), AiEditState(), proposal).errors.single().contains("未知的节点图"))
    }

    @Test
    fun `open workflow is reported without changing edit state`() {
        val proposal = AiEdits.parse(
            """{"summary":"x","ops":[{"op":"open_workflow","path":"other.json"}]}""",
        )
        assertNotNull(proposal)
        val result = AiEdits.apply(view(), AiEditState(), proposal)
        assertTrue(result.ok)
        assertEquals("other.json", result.openWorkflow)
        assertEquals(AiEditState(), result.state)
    }

    @Test
    fun `digest reflects overrides and hides unreachable and linked fields`() {
        val state = AiEditState(overrides = mapOf(195 to mapOf("steps" to JsonPrimitive(6))))
        val digest = AiDigest.build(view(), state, reachableOnly = true)

        val nodes = digest["nodes"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, nodes.size)
        val sampler = nodes.first { it.jsonObject["id"]!!.jsonPrimitive.content == "195" }
        val fields = sampler.jsonObject["fields"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(2, fields.size)
        val steps = fields.first { it.jsonObject["name"]!!.jsonPrimitive.content == "steps" }
        assertEquals("6", steps.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(fields.none { it.jsonObject["name"]!!.jsonPrimitive.content == "linked" })
        assertTrue(AiDigest.estimateTokens(digest) > 0)
    }

    @Test
    fun `malformed json is rejected`() {
        assertNull(AiEdits.parse("not json"))
        assertNull(AiEdits.parse("""{"summary":"x","ops":[]}"""))
    }
}
