package com.comfypilot.core.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
    /** COMBO 的可选值（截断后），其它类型为空。 */
    val options: List<String> = emptyList(),
    /** 该输入由哪个节点驱动；null 表示可编辑控件。 */
    val fromNode: Int? = null,
    val fromSlot: Int? = null,
)

data class AiNode(
    val id: Int,
    val className: String,
    val title: String,
    val enabled: Boolean,
    val reachable: Boolean,
    val fields: List<AiField>,
    /** 节点所属的输出图；多图工作流用来判断启用哪个图。 */
    val graphId: String? = null,
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

    /** 摘要：只发图、计数和节点索引；字段细节用 AiQuery 按需取。 */
    fun summary(view: AiWorkflowView, state: AiEditState): JsonObject = buildJsonObject {
        put("path", view.path)
        putJsonArray("graphs") {
            view.graphs.forEach { graph ->
                add(
                    buildJsonObject {
                        put("id", graph.id)
                        put("title", graph.title)
                        put("selected", state.graphs?.contains(graph.id) ?: graph.selected)
                        put("nodeCount", view.nodes.count { it.graphId == graph.id })
                    },
                )
            }
        }
        put(
            "counts",
            buildJsonObject {
                put("nodes", view.nodes.size)
                put("reachable", view.nodes.count { it.reachable })
            },
        )
        putJsonArray("nodeIndex") {
            view.nodes.forEach { node ->
                add(
                    buildJsonObject {
                        put("id", node.id)
                        put("class", node.className)
                        put("title", node.title)
                        put("enabled", state.isEnabled(node))
                        put("reachable", node.reachable)
                        node.graphId?.let { put("graph", it) }
                    },
                )
            }
        }
    }

    /** 粗略 token 估算：4 个字符约 1 token，够用来提示成本。 */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    fun estimateTokens(element: JsonElement): Int = estimateTokens(element.toString())
}


/** 轻量查询工具：模型需要细节时按需取，不一次性灌全量。 */
object AiQuery {

    fun graphs(view: AiWorkflowView, state: AiEditState): JsonObject = buildJsonObject {
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
    }

    fun nodes(
        view: AiWorkflowView,
        state: AiEditState,
        query: String? = null,
        limit: Int = 40,
    ): JsonObject {
        val needle = query?.trim()?.lowercase().orEmpty()
        val matched = view.nodes.filter { node ->
            needle.isEmpty() ||
                node.title.lowercase().contains(needle) ||
                node.className.lowercase().contains(needle) ||
                node.id.toString() == needle
        }
        return buildJsonObject {
            put("total", matched.size)
            putJsonArray("nodes") {
                matched.take(limit.coerceIn(1, 100)).forEach { node ->
                    add(
                        buildJsonObject {
                            put("id", node.id)
                            put("class", node.className)
                            put("title", node.title)
                            put("enabled", state.isEnabled(node))
                            put("reachable", node.reachable)
                            node.graphId?.let { put("graph", it) }
                            put("editableFields", node.fields.count { it.editable })
                        },
                    )
                }
            }
        }
    }

    fun node(
        view: AiWorkflowView,
        state: AiEditState,
        id: Int,
    ): JsonObject? {
        val node = view.node(id) ?: return null
        return buildJsonObject {
            put("id", node.id)
            put("class", node.className)
            put("title", node.title)
            put("enabled", state.isEnabled(node))
            put("reachable", node.reachable)
            putJsonArray("fields") {
                node.fields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("name", field.input)
                            put("type", field.type)
                            put("value", state.valueOf(field) ?: JsonNull)
                            put("editable", field.editable)
                            if (field.options.isNotEmpty()) {
                                putJsonArray("options") {
                                    field.options.take(20).forEach { add(JsonPrimitive(it)) }
                                }
                            }
                            field.fromNode?.let { put("fromNode", it) }
                            field.fromSlot?.let { put("fromSlot", it) }
                        },
                    )
                }
            }
        }
    }
}
