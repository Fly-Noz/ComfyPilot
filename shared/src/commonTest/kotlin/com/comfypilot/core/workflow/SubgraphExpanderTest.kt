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
    fun `relocation keeps the original group by extending its bounding box`() {
        // 给 fixture 加一个包住子图内部节点的图组；展开簇和已有节点重叠时，
        // 应该贴着原组右侧停放并扩展原组，而不是新造一个「子图 …」组。
        val withGroup = FIXTURE
            .replace(
                "\"nodes\": [",
                "\"groups\": [{\"id\": 9, \"title\": \"编辑组\", \"bounding\": [0, 0, 700, 400]}],\n              \"nodes\": [",
            )
            // 把外层节点挪远，保证组框右侧的夹缝里没有节点，才能走「扩展原组」分支。
            .replace("\"pos\": [900, 0]", "\"pos\": [2000, 0]")
        val expanded = SubgraphExpander.expand(Json.parseToJsonElement(withGroup).jsonObject)
        val groups = expanded["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, groups.size, "不应额外造组：$groups")
        assertEquals("编辑组", groups[0]["title"]!!.jsonPrimitive.content)
        val bounding = groups[0]["bounding"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertTrue(bounding[2] > 700.0, "原组应被扩展到包住展开簇：$bounding")

        // 展开节点仍在原组（扩展后的）范围内。
        val inner = expanded["nodes"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "SaveImage" }
        val pos = inner["pos"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertTrue(pos[0] >= 0 && pos[0] + 220 <= bounding[2], "展开节点应落在扩展后的组框内：$pos / $bounding")
    }

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

        // 展开簇和已有节点重叠时要挪开，并追加「子图 …」的合成图组。
        val outer = nodes.first { it["id"]!!.jsonPrimitive.content == "1" }
        val outerRect = rectOf(outer)
        val saveRect = rectOf(saveImage)
        assertTrue(
            outerRect.right <= saveRect.left || saveRect.right <= outerRect.left ||
                outerRect.bottom <= saveRect.top || saveRect.bottom <= outerRect.top,
            "展开节点不能压住已有节点：outer=$outerRect save=$saveRect",
        )
        val groups = expanded["groups"]!!.jsonArray.map { it.jsonObject }
        assertTrue(groups.any { it["title"]!!.jsonPrimitive.content.startsWith("子图 ") }, "应当追加合成图组：$groups")
        // 标题带子图名前缀，列表里能看出这节点是从哪个子图来的。
        assertTrue(
            saveImage["title"]!!.jsonPrimitive.content.startsWith("New Subgraph · "),
            saveImage["title"]!!.jsonPrimitive.content,
        )
    }

    private data class R(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun rectOf(node: JsonObject): R {
        val pos = node["pos"]!!.jsonArray
        val size = node["size"] as? JsonArray
        val w = size?.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 220
        val h = size?.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 140
        val x = pos[0].jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: 0
        val y = pos[1].jsonPrimitive.content.toDoubleOrNull()?.toInt() ?: 0
        return R(x, y, x + w, y + h)
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
