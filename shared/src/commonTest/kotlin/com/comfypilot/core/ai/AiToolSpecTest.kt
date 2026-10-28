package com.comfypilot.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具声明是两端的唯一来源，格式坏掉会让模型一个工具都看不到（静默失败）。
 * 这里只校验结构与名字，不校验描述文案。
 */
class AiToolSpecTest {

    @Test
    fun `tool spec is valid json and lists every tool once`() {
        val array = Json.parseToJsonElement(AiToolSpec.json).jsonArray
        val names = array.map { item ->
            item.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(names.size, names.toSet().size, "工具名不能重复")
        assertEquals(
            setOf(
                "propose_edits",
                "submit",
                "task_status",
                "open_workflow",
                "ask_user",
                "list_graphs",
                "list_nodes",
                "get_node",
                "lookup_prompt_guide",
                "fetch_doc",
            ),
            names.toSet(),
        )
        assertTrue(array.all { it.jsonObject["type"]!!.jsonPrimitive.content == "function" })
    }
}
