package com.comfypilot.core.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 发给模型的节点字段摘要（只保留模型需要知道的）。 */
data class AiField(
    val nodeId: Int,
    val input: String,
    val type: String,
    val value: JsonElement?,
    val editable: Boolean,
)

data class AiNode(
    val id: Int,
    val className: String,
    val title: String,
    val enabled: Boolean,
    val reachable: Boolean,
    val fields: List<AiField>,
)

data class AiGraph(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/** 当前工作流的压缩视图，由宿主从 WorkflowAnalysis 构造。 */
data class AiWorkflowView(
    val path: String,
    val nodes: List<AiNode>,
    val graphs: List<AiGraph>,
) {
    fun node(id: Int): AiNode? = nodes.firstOrNull { it.id == id }

    fun field(nodeId: Int, input: String): AiField? =
        node(nodeId)?.fields?.firstOrNull { it.input == input }
}

/** 会话级编辑状态，与 UI 的 overrides/bypass/graphs 一一对应。 */
data class AiEditState(
    val overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
    val bypassed: Set<Int> = emptySet(),
    val enabled: Set<Int> = emptySet(),
    /** null 表示沿用工作流文件里的默认选中图。 */
    val graphs: Set<String>? = null,
) {
    fun valueOf(field: AiField): JsonElement? =
        overrides[field.nodeId]?.get(field.input) ?: field.value

    fun isEnabled(node: AiNode): Boolean = when {
        node.id in bypassed -> false
        node.id in enabled -> true
        else -> node.enabled
    }
}

object AiDigest {

    /** 构造模型可见的 digest；超预算的截断策略由调用方决定，这里只负责结构。 */
    fun build(view: AiWorkflowView, state: AiEditState, reachableOnly: Boolean = true): JsonObject =
        buildJsonObject {
            put("path", view.path)
            putJsonArray("graphs") {
                view.graphs.forEach { graph ->
                    add(
                        buildJsonObject {
                            put("id", graph.id)
                            put("title", graph.title)
                            put("selected", state.graphs?.contains(graph.id) ?: graph.selected)
                        },
                    )
                }
            }
            putJsonArray("nodes") {
                view.nodes
                    .filter { !reachableOnly || it.reachable }
                    .forEach { node ->
                        add(
                            buildJsonObject {
                                put("id", node.id)
                                put("class", node.className)
                                put("title", node.title)
                                put("enabled", state.isEnabled(node))
                                putJsonArray("fields") {
                                    node.fields.filter { it.editable }.forEach { field ->
                                        add(
                                            buildJsonObject {
                                                put("name", field.input)
                                                put("type", field.type)
                                                put("value", state.valueOf(field) ?: JsonNull)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
            }
        }

    /** 粗略 token 估算：4 个字符约 1 token，够用来提示成本。 */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    fun estimateTokens(element: JsonElement): Int = estimateTokens(element.toString())
}
