package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * rgthree 的 Seed 节点会在 `widgets_values` 里多存几个前端控制值
 * （`[-1, "", "", ""]`）。只有第一个是真正的 seed，其余要忽略，
 * 否则控件映射会错位、seed 改完也不生效。
 */
class RgthreeSeedTest {

    @Test
    fun `seed keeps first value and ignores frontend control tokens`() {
        val node = UiNode(
            id = 330,
            type = "Seed (rgthree)",
            title = null,
            mode = 0,
            order = 0,
            inputs = emptyList(),
            outputs = emptyList(),
            widgets = JsonArray(
                listOf(
                    JsonPrimitive(-1),
                    JsonPrimitive(""),
                    JsonPrimitive(""),
                    JsonPrimitive(""),
                ),
            ),
            widgetsNamed = emptyMap(),
        )
        val definitions = listOf(NodeInputDef(name = "seed", type = "INT", isWidget = true))

        val values = WidgetValueReader.read(node, definitions, mutableListOf(), mutableListOf())

        assertEquals("-1", values["seed"]?.jsonPrimitive?.content)
        assertEquals(1, values.size, "多余的前端控制值不应产生字段：$values")
    }
}
