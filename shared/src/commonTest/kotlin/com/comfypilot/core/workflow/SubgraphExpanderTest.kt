package com.comfypilot.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 子图展开：实例节点替换成内部节点，边界连线接到外层，控件值注入内部 widgets_values。
 *
 * fixture 是按真实工作流（Qwen 图像编辑流里的一个子图）裁剪的：一个子图两个输入
 * （model 连线进来、filename_prefix 是控件值）、一个 MODEL 输出。
 */
class SubgraphExpanderTest {

    @Test
    fun `instance is flattened into inner nodes with boundary links remapped`() {
        val expanded = SubgraphExpander.expand(Json.parseToJsonElement(FIXTURE).jsonObject)
        val nodes = expanded["nodes"]!!.jsonArray.map { it.jsonObject }
        val types = nodes.map { it["type"]!!.jsonPrimitive.content }

        assertTrue(types.none { it.startsWith("11111111-") }, "实例节点必须被展开：$types")
        assertEquals(4, nodes.size, "外层 2 个 + 子图内部 2 个：$types")

        val ids = nodes.map { it["id"]!!.jsonPrimitive.intOrNull!! }
        assertEquals(ids.size, ids.toSet().size, "展开后 id 不能重复")

        // 外层 1 的 MODEL 输出应接到子图内部的 UNETLoader（而不是还指向实例 674）。
        val innerLoader = nodes.first { it["type"]!!.jsonPrimitive.content == "UNETLoader" }
        val loaderId = innerLoader["id"]!!.jsonPrimitive.intOrNull!!
        val links = expanded["links"]!!.jsonArray.map { it.jsonArray.map { item -> item.jsonPrimitive.content } }
        assertTrue(
            links.any { it[1] == "1" && it[2] == "0" && it[3] == loaderId.toString() },
            "外层连线应改接到内部节点：$links",
        )

        // 内部输出经子图输出槽接到外层 2。
        assertTrue(links.any { it[3] == "2" && it[1] == loaderId.toString() }, "子图输出应接到外层 2：$links")

        // filename_prefix 是控件值：要写进内部 SaveImage 的 widgets_values。
        val saveImage = nodes.first { it["type"]!!.jsonPrimitive.content == "SaveImage" }
        val widgets = saveImage["widgets_values"]!!.jsonArray
        assertEquals("QW21-prefix", widgets[0].jsonPrimitive.content)
    }

    private companion object {
        val FIXTURE = """
            {
              "last_node_id": 700, "last_link_id": 999,
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "pos": [0, 0], "mode": 0,
                 "inputs": [], "outputs": [{"name": "MODEL", "type": "MODEL", "links": [935]}]},
                {"id": 2, "type": "SaveVideo", "pos": [900, 0], "mode": 0,
                 "inputs": [{"name": "model", "type": "MODEL", "link": 936}], "outputs": []},
                {"id": 674, "type": "11111111-2222-3333-4444-555555555555", "pos": [300, 0], "mode": 0,
                 "inputs": [
                   {"name": "model", "type": "MODEL", "link": 935},
                   {"name": "filename_prefix", "type": "STRING", "widget": {"name": "filename_prefix"}, "link": null}
                 ],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [936]}],
                 "widgets_values": ["QW21-prefix"],
                 "widgets_values_named": {"filename_prefix": "QW21-prefix"}}
              ],
              "links": [
                [935, 1, 0, 674, 0, "MODEL"],
                [936, 674, 0, 2, 0, "MODEL"]
              ],
              "definitions": {"subgraphs": [
                {"id": "11111111-2222-3333-4444-555555555555", "name": "New Subgraph",
                 "inputs": [
                   {"name": "model", "type": "MODEL", "linkIds": [915]},
                   {"name": "filename_prefix", "type": "STRING", "linkIds": [937]}
                 ],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "linkIds": [920]}],
                 "nodes": [
                   {"id": 523, "type": "UNETLoader", "pos": [0, 100], "mode": 0,
                    "inputs": [{"name": "model", "type": "MODEL", "link": 915}],
                    "outputs": [{"name": "MODEL", "type": "MODEL", "links": [920]}]},
                   {"id": 508, "type": "SaveImage", "pos": [400, 100], "mode": 0,
                    "inputs": [
                      {"name": "images", "type": "IMAGE", "link": null},
                      {"name": "filename_prefix", "type": "STRING", "widget": {"name": "filename_prefix"}, "link": 937}
                    ],
                    "outputs": [], "widgets_values": ["old"]}
                 ],
                 "links": [
                   [915, -10, 0, 523, 0, "MODEL"],
                   [920, 523, 0, -20, 0, "MODEL"],
                   [937, -10, 1, 508, 1, "STRING"]
                 ]}
              ]}
            }
        """.trimIndent()
    }
}
