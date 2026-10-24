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
 * 坐标只用于把节点归到分组（多个独立节点图），不参与提交图本身。
 */
data class UiGraph(
    val nodes: List<UiNode>,
    val links: Map<Int, UiLink>,
    /** 顶层带 `definitions.subgraphs`：当前版本不展开子图，直接拒绝。 */
    val hasSubgraphs: Boolean,
    val groups: List<UiGroup> = emptyList(),
) {
    val byId: Map<Int, UiNode> = nodes.associateBy { it.id }

    /** 把某一个独立节点图里的节点临时当作正常执行（用于在 App 里启用被旁路的图）。 */
    fun withForcedNormal(ids: Set<Int>): UiGraph = withEffectiveModes(forcedNormalNodeIds = ids)

    /**
     * 应用本次会话的节点模式覆盖。
     *
     * 优先级从高到低：用户逐个关闭 > 用户逐个重新启用 > 图级强制启用 > 文件里保存的模式。
     * 单节点显式关闭优先于图级强制启用：用户刚关掉的节点不能被图选择悄悄打开；
     * 反过来，文件里保存为静音/旁路的节点只有在用户明确点开或启用整张图时才恢复执行。
     */
    fun withEffectiveModes(
        forcedNormalNodeIds: Set<Int> = emptySet(),
        bypassedNodeIds: Set<Int> = emptySet(),
        enabledNodeIds: Set<Int> = emptySet(),
    ): UiGraph =
        if (forcedNormalNodeIds.isEmpty() && bypassedNodeIds.isEmpty() && enabledNodeIds.isEmpty()) {
            this
        } else {
            copy(
                nodes = nodes.map { node ->
                    when {
                        node.id in bypassedNodeIds -> node.copy(mode = UiWorkflowParser.MODE_BYPASS)
                        node.id in enabledNodeIds -> node.copy(mode = UiWorkflowParser.MODE_NORMAL)
                        node.id in forcedNormalNodeIds -> node.copy(mode = UiWorkflowParser.MODE_NORMAL)
                        else -> node
                    }
                },
            )
        }
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
    /** 画布坐标，只用于分组归属判断。 */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
) {
    val hasWidgetValuesNamed: Boolean get() = widgetsNamed.isNotEmpty()
}

/** 网页端画布上的一个分组（group）。 */
data class UiGroup(
    val id: Int,
    val title: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun contains(node: UiNode): Boolean {
        val centerX = node.x + node.width / 2.0
        val centerY = node.y + node.height / 2.0
        return centerX >= x && centerX <= x + width && centerY >= y && centerY <= y + height
    }
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
        val groups = (root["groups"] as? JsonArray).orEmpty()
            .mapNotNull { element -> parseGroup(element as? JsonObject ?: return@mapNotNull null) }
        return UiGraph(
            nodes = nodes,
            links = links,
            hasSubgraphs = subgraphs != null && subgraphs.isNotEmpty(),
            groups = groups,
        )
    }

    private fun parseNode(obj: JsonObject): UiNode? {
        val id = obj["id"].asIntOrNull() ?: return null
        val type = obj.stringOrNull("type")?.takeIf { it.isNotBlank() } ?: return null
        val widgetValues = obj["widgets_values"]
        val named = (obj["widgets_values_named"] as? JsonObject)?.toMap()
            ?: (widgetValues as? JsonObject)?.toMap()
            ?: emptyMap()
        val pos = obj["pos"] as? JsonArray
        val size = obj["size"] as? JsonArray
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
            x = pos?.getOrNull(0).asDoubleOrNull() ?: 0.0,
            y = pos?.getOrNull(1).asDoubleOrNull() ?: 0.0,
            width = size?.getOrNull(0).asDoubleOrNull() ?: 0.0,
            height = size?.getOrNull(1).asDoubleOrNull() ?: 0.0,
        )
    }

    private fun parseGroup(obj: JsonObject): UiGroup? {
        val id = obj["id"].asIntOrNull() ?: return null
        val bounding = obj["bounding"] as? JsonArray ?: return null
        if (bounding.size < 4) return null
        return UiGroup(
            id = id,
            title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: "节点图 #$id",
            x = bounding[0].asDoubleOrNull() ?: return null,
            y = bounding[1].asDoubleOrNull() ?: return null,
            width = bounding[2].asDoubleOrNull() ?: return null,
            height = bounding[3].asDoubleOrNull() ?: return null,
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

    private fun parseLink(element: JsonElement): UiLink? {
        return when (element) {
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
}

private fun JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

private fun JsonElement?.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
