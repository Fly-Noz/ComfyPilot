package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 模型只能通过这些声明式操作改工作流。 */
sealed interface AiOp {
    data class SetInput(val nodeId: Int, val input: String, val value: JsonElement) : AiOp

    data class ToggleNode(val nodeId: Int, val enabled: Boolean) : AiOp

    data class SelectGraphs(val graphs: List<String>) : AiOp

    /** 由宿主执行；不改变编辑状态。 */
    data class OpenWorkflow(val path: String) : AiOp
}

data class AiProposal(val summary: String, val ops: List<AiOp>)

data class AiChange(val label: String)

data class AiApplyResult(
    val state: AiEditState,
    val changes: List<AiChange>,
    val errors: List<String>,
    val openWorkflow: String? = null,
) {
    val ok: Boolean get() = errors.isEmpty()
}

object AiEdits {

    /** 解析模型输出的 JSON；结构不合法时返回 null，交给上层提示重试。 */
    fun parse(raw: String): AiProposal? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        val summary = root["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val array = root["ops"]?.jsonArray ?: JsonArray(emptyList())
        val ops = array.mapNotNull { parseOp(it) }
        if (ops.isEmpty()) null else AiProposal(summary, ops)
    }.getOrNull()

    private fun parseOp(element: JsonElement): AiOp? {
        val obj = element as? JsonObject ?: return null
        return when (obj.string("op")) {
            "set_input" -> {
                val node = obj.int("node") ?: return null
                val input = obj.string("input") ?: return null
                val value = obj["value"] ?: return null
                AiOp.SetInput(node, input, value)
            }

            "toggle_node" -> {
                val node = obj.int("node") ?: return null
                val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: return null
                AiOp.ToggleNode(node, enabled)
            }

            "select_graphs" -> {
                val graphs = (obj["graphs"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: return null
                AiOp.SelectGraphs(graphs)
            }

            "open_workflow" -> {
                val path = obj.string("path") ?: return null
                AiOp.OpenWorkflow(path)
            }

            else -> null
        }
    }

    /**
     * 应用一组操作。全部成功才返回新状态；任意一条失败则整批拒绝。
     */
    fun apply(view: AiWorkflowView, state: AiEditState, proposal: AiProposal): AiApplyResult {
        val errors = mutableListOf<String>()
        val changes = mutableListOf<AiChange>()
        val overrides = state.overrides.toMutableMap()
        val bypassed = state.bypassed.toMutableSet()
        val enabled = state.enabled.toMutableSet()
        var graphs = state.graphs
        var openWorkflow: String? = null

        fun nodeLabel(nodeId: Int): String {
            val node = view.node(nodeId)
            return if (node == null) "#$nodeId" else "${node.title}（#${node.id}）"
        }

        proposal.ops.forEach { op ->
            when (op) {
                is AiOp.SetInput -> {
                    val field = view.field(op.nodeId, op.input)
                    when {
                        field == null -> errors += "节点 ${nodeLabel(op.nodeId)} 没有输入 ${op.input}"
                        !field.editable -> errors += "节点 ${nodeLabel(op.nodeId)} 的 ${op.input} 不可编辑（由连线驱动）"
                        else -> {
                            val coerced = coerce(field, op.value)
                            if (coerced == null) {
                                errors += "${nodeLabel(op.nodeId)} 的 ${op.input} 类型不匹配（${field.type}）"
                            } else {
                                val old = state.valueOf(field)
                                val nodeOverrides = overrides[op.nodeId].orEmpty().toMutableMap()
                                nodeOverrides[op.input] = coerced
                                overrides[op.nodeId] = nodeOverrides
                                changes += AiChange(
                                    "${nodeLabel(op.nodeId)} ${op.input}: " +
                                        "${old.display()} → ${coerced.display()}",
                                )
                            }
                        }
                    }
                }

                is AiOp.ToggleNode -> {
                    val node = view.node(op.nodeId)
                    if (node == null) {
                        errors += "节点 #${op.nodeId} 不存在"
                    } else {
                        val before = state.isEnabled(node) && op.nodeId !in bypassed
                        if (op.enabled) {
                            bypassed -= op.nodeId
                            enabled += op.nodeId
                        } else {
                            enabled -= op.nodeId
                            bypassed += op.nodeId
                        }
                        if (before != op.enabled) {
                            changes += AiChange(
                                "${nodeLabel(op.nodeId)}: " +
                                    (if (before) "启用 → 关闭" else "关闭 → 启用"),
                            )
                        }
                    }
                }

                is AiOp.SelectGraphs -> {
                    val known = view.graphs.map { it.id }.toSet()
                    val unknown = op.graphs.filter { it !in known }
                    when {
                        op.graphs.isEmpty() -> errors += "至少要选择一个节点图"
                        unknown.isNotEmpty() -> errors += "未知的节点图：${unknown.joinToString()}"
                        else -> {
                            val titles = op.graphs.mapNotNull { id ->
                                view.graphs.firstOrNull { it.id == id }?.title
                            }
                            changes += AiChange("节点图切换为：${titles.joinToString("、")}")
                            graphs = op.graphs.toSet()
                        }
                    }
                }

                is AiOp.OpenWorkflow -> {
                    if (openWorkflow != null) {
                        errors += "一次只能切换一个工作流"
                    } else {
                        openWorkflow = op.path
                        changes += AiChange("切换到工作流：${op.path}")
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            AiApplyResult(
                state = AiEditState(overrides, bypassed, enabled, graphs),
                changes = changes,
                errors = emptyList(),
                openWorkflow = openWorkflow,
            )
        } else {
            AiApplyResult(state, emptyList(), errors)
        }
    }

    /** 按字段类型把模型给的值规范化。 */
    private fun coerce(field: AiField, value: JsonElement): JsonElement? {
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        val type = field.type.uppercase()
        return when (type) {
            "INT", "INTEGER" -> primitive.longOrNull?.let { JsonPrimitive(it) }
                ?: primitive.contentOrNull?.toLongOrNull()?.let { JsonPrimitive(it) }

            "FLOAT", "NUMBER", "DOUBLE" -> primitive.doubleOrNull?.let { JsonPrimitive(it) }
                ?: primitive.contentOrNull?.toDoubleOrNull()?.let { JsonPrimitive(it) }

            "BOOLEAN", "BOOL" -> primitive.booleanOrNull?.let { JsonPrimitive(it) }
                ?: primitive.contentOrNull?.lowercase()?.let {
                    when (it) {
                        "true", "1", "yes", "on" -> JsonPrimitive(true)
                        "false", "0", "no", "off" -> JsonPrimitive(false)
                        else -> null
                    }
                }

            else -> JsonPrimitive(primitive.contentOrNull ?: primitive.toString())
        }
    }

    private fun JsonElement?.display(): String = when {
        this == null || this is JsonNull -> "空"
        this is JsonPrimitive && isString -> "\"${content}\""
        else -> toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
