package com.comfypilot.core.workflow

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 网页端（UI 格式）工作流的可执行视图。
 *
 * 只保留转换到 API 格式必需的东西：节点、连线、控件值、模式。
 * 坐标、颜色、分组一概不读——提交用不到，读多了只会多一种出错方式。
 */
data class UiGraph(
    val nodes: List<UiNode>,
    val links: Map<Int, UiLink>,
    /** 顶层带 `definitions.subgraphs`：当前版本不展开子图，直接拒绝。 */
    val hasSubgraphs: Boolean,
) {
    val byId: Map<Int, UiNode> = nodes.associateBy { it.id }
}

data class UiNode(
    val id: Int,
    val type: String,
    val title: String?,
    val mode: Int,
    val order: Int,
    val inputs: List<UiInput>,
    val outputs: List<UiOutput>,
    /** `widgets_values` 数组；新版前端也可能写成对象。 */
    val widgets: JsonArray?,
    /** `widgets_values_named`（新版）或对象形式的 `widgets_values`。 */
    val widgetsNamed: Map<String, JsonElement>,
) {
    val hasWidgetValuesNamed: Boolean get() = widgetsNamed.isNotEmpty()
}

data class UiInput(
    val name: String,
    val type: String,
    val linkId: Int?,
    /** 该输入在界面上是一个控件时，控件的名字（通常等于 [name]）。 */
    val widgetName: String?,
    val localizedName: String?,
)

data class UiOutput(val name: String, val type: String)

data class UiLink(
    val id: Int,
    val originId: Int,
    val originSlot: Int,
    val targetId: Int,
    val targetSlot: Int,
    val type: String,
)

object UiWorkflowParser {
    const val MODE_NORMAL = 0
    const val MODE_MUTED = 2
    const val MODE_BYPASS = 4

    fun parse(raw: String): UiGraph? {
        val root = runCatching { ApiCodec.json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        val nodesJson = root["nodes"] as? JsonArray ?: return null

        val nodes = nodesJson.mapNotNull { element -> parseNode(element as? JsonObject ?: return@mapNotNull null) }
        val links = (root["links"] as? JsonArray).orEmpty()
            .mapNotNull { parseLink(it) }
            .associateBy { it.id }

        val subgraphs = ((root["definitions"] as? JsonObject)?.get("subgraphs") as? JsonArray)
        return UiGraph(
            nodes = nodes,
            links = links,
            hasSubgraphs = subgraphs != null && subgraphs.isNotEmpty(),
        )
    }

    private fun parseNode(obj: JsonObject): UiNode? {
        val id = obj["id"].asIntOrNull() ?: return null
        val type = obj.stringOrNull("type")?.takeIf { it.isNotBlank() } ?: return null
        val widgetValues = obj["widgets_values"]
        val named = (obj["widgets_values_named"] as? JsonObject)?.toMap()
            ?: (widgetValues as? JsonObject)?.toMap()
            ?: emptyMap()
        return UiNode(
            id = id,
            type = type,
            title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() },
            mode = obj["mode"].asIntOrNull() ?: MODE_NORMAL,
            order = obj["order"].asIntOrNull() ?: id,
            inputs = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { parseInput(it as? JsonObject) },
            outputs = (obj["outputs"] as? JsonArray).orEmpty().mapNotNull { parseOutput(it as? JsonObject) },
            widgets = widgetValues as? JsonArray,
            widgetsNamed = named,
        )
    }

    private fun parseInput(obj: JsonObject?): UiInput? {
        obj ?: return null
        val name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        // 老工作流没有 type 的情况极少；空类型按通配处理，不影响连接解析。
        val type = obj.stringOrNull("type") ?: ""
        return UiInput(
            name = name,
            type = type,
            linkId = obj["link"].asIntOrNull(),
            widgetName = (obj["widget"] as? JsonObject)?.stringOrNull("name"),
            localizedName = obj.stringOrNull("localized_name"),
        )
    }

    private fun parseOutput(obj: JsonObject?): UiOutput? {
        obj ?: return null
        return UiOutput(
            name = obj.stringOrNull("name") ?: "",
            type = obj.stringOrNull("type") ?: "",
        )
    }

    private fun parseLink(element: JsonElement): UiLink? = when (element) {
        is JsonArray -> {
            if (element.size < 5) return null
            UiLink(
                id = element[0].asIntOrNull() ?: return null,
                originId = element[1].asIntOrNull() ?: return null,
                originSlot = element[2].asIntOrNull() ?: return null,
                targetId = element[3].asIntOrNull() ?: return null,
                targetSlot = element[4].asIntOrNull() ?: return null,
                type = (element.getOrNull(5) as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }

        is JsonObject -> UiLink(
            id = element["id"].asIntOrNull() ?: return null,
            originId = element["origin_id"].asIntOrNull() ?: return null,
            originSlot = element["origin_slot"].asIntOrNull() ?: return null,
            targetId = element["target_id"].asIntOrNull() ?: return null,
            targetSlot = element["target_slot"].asIntOrNull() ?: return null,
            type = element.stringOrNull("type") ?: "",
        )

        else -> null
    }
}

private fun JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
