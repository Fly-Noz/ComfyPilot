package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.BuildProblem
import com.comfypilot.core.workflow.NodeDefinition
import com.comfypilot.core.workflow.PromptBuild
import com.comfypilot.core.workflow.PromptBuilder
import com.comfypilot.core.workflow.WorkflowDocument
import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowFields
import com.comfypilot.core.workflow.WorkflowFormat
import com.comfypilot.core.workflow.WorkflowGraph
import com.comfypilot.core.workflow.WorkflowGraphs
import com.comfypilot.core.workflow.WorkflowNodeView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 鸿蒙 ArkTS 侧按需拉取的工作流视图。
 *
 * 状态帧只带轻量元数据；几百 KB 的工作流原文、节点定义和字段模型放在这里按需序列化。
 * 这样每 500ms 的状态帧不会被一份大工作流拖慢，ArkTS 也不需要在本地重写字段/节点图规则。
 */
internal fun sessionWorkflowJson(state: SessionState, rawRequest: String): String {
    val request = runCatching { WorkflowUiRequest.parse(rawRequest) }.getOrNull()
        ?: return workflowError("工作流请求不是有效的 JSON")
    return when (request.op) {
        WorkflowUiRequest.OP_VIEW -> workflowViewJson(state, request)
        WorkflowUiRequest.OP_PROMPT -> workflowPromptJson(state, request)
        else -> workflowError("未知的工作流操作：${request.op}")
    }
}

private data class WorkflowUiRequest(
    val op: String,
    val pinnedNodeIds: Set<Int>,
    val enabledGraphIds: Set<String>?,
    val overrides: Map<Int, Map<String, JsonElement>>,
    /** 仅提交时使用：保存类节点的文件名追加这个短 token，避免产物互相覆盖。 */
    val runToken: String?,
) {
    companion object {
        const val OP_VIEW = "view"
        const val OP_PROMPT = "prompt"

        fun parse(raw: String): WorkflowUiRequest {
            val obj = bridgeJson.parseToJsonElement(raw) as? JsonObject
                ?: error("request must be an object")
            return WorkflowUiRequest(
                op = obj.stringOrNull("op") ?: OP_VIEW,
                pinnedNodeIds = (obj["pinned"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                    .toSet(),
                enabledGraphIds = parseGraphIds(obj),
                overrides = parseOverrides(obj["overrides"] as? JsonObject),
                runToken = obj.stringOrNull("run")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun parseGraphIds(obj: JsonObject): Set<String>? {
            val element = obj["graphs"] ?: return null
            if (element is JsonNull) return null
            val array = element as? JsonArray ?: return null
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        }

        private fun parseOverrides(obj: JsonObject?): Map<Int, Map<String, JsonElement>> {
            if (obj == null) return emptyMap()
            val result = mutableMapOf<Int, Map<String, JsonElement>>()
            obj.forEach { (nodeKey, nodeValue) ->
                val nodeId = nodeKey.toIntOrNull() ?: return@forEach
                val fields = nodeValue as? JsonObject ?: return@forEach
                if (fields.isNotEmpty()) {
                    result[nodeId] = fields.toMap()
                }
            }
            return result
        }
    }
}

private data class SelectedGraphs(
    val graphs: List<WorkflowGraph>,
    val selectedIds: Set<String>,
    val enabledOutputIds: Set<Int>?,
    val forcedNormalNodeIds: Set<Int>,
)

private fun selectGraphs(
    document: WorkflowDocument,
    definitions: Map<String, NodeDefinition>,
    requestedIds: Set<String>?,
): SelectedGraphs {
    val graphs = if (document.format == WorkflowFormat.UI) {
        WorkflowGraphs.analyze(document, definitions)
    } else {
        emptyList()
    }
    val defaultIds = graphs.filter { it.enabled }.map { it.id }.toSet()
    val selectedIds = if (requestedIds == null) {
        defaultIds
    } else {
        requestedIds.intersect(graphs.map { it.id }.toSet())
    }
    val selected = graphs.filter { it.id in selectedIds }
    return SelectedGraphs(
        graphs = graphs,
        selectedIds = selectedIds,
        enabledOutputIds = if (graphs.isEmpty()) null else selected.flatMap { it.outputNodeIds }.toSet(),
        forcedNormalNodeIds = if (graphs.isEmpty()) emptySet() else selected.flatMap { it.nodeIds }.toSet(),
    )
}

private fun workflowViewJson(state: SessionState, request: WorkflowUiRequest): String {
    val document = state.workflow.document
        ?: return workflowError("还没有打开工作流", path = state.workflow.loadingPath.orEmpty())
    val definitions = state.nodeInfo
    val selection = selectGraphs(document, definitions, request.enabledGraphIds)
    val fields = WorkflowFields.build(
        document = document,
        definitions = definitions,
        forcedNormalNodeIds = selection.forcedNormalNodeIds,
        enabledOutputIds = selection.enabledOutputIds,
    )
    val ordered = orderWorkflowNodes(fields.nodes, request.pinnedNodeIds)
    val build = PromptBuilder.build(
        document = document,
        definitions = definitions,
        overrides = request.overrides,
        enabledOutputIds = selection.enabledOutputIds,
        forcedNormalNodeIds = selection.forcedNormalNodeIds,
    )
    val allClasses = document.nodeClasses.distinct()
    val missingClasses = allClasses.filter { definitions[it]?.missing != false }
    val pendingClasses = missingClasses.filter { it !in state.nodeInfoRequested }
    val buildProblems = when (build) {
        is PromptBuild.Blocked -> build.problems
        is PromptBuild.Ready -> emptyList()
    }
    val problems = (fields.problems + buildProblems)
        .distinctBy { Triple(it.nodeId, it.nodeType, it.message) }

    return buildJsonObject {
        put("ok", true)
        put("state", "ready")
        put("path", document.path)
        put("name", document.name)
        put("format", document.format.name)
        put("defaultGraphIds", stringArray(selection.graphs.filter { it.enabled }.map { it.id }))
        put("selectedGraphIds", stringArray(selection.selectedIds.sorted()))
        putJsonArray("graphs") {
            selection.graphs.forEach { graph ->
                add(
                    buildJsonObject {
                        put("id", graph.id)
                        put("title", graph.title)
                        put("enabled", graph.enabled)
                        put("selected", graph.id in selection.selectedIds)
                        putJsonArray("nodeIds") { graph.nodeIds.sorted().forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("outputNodeIds") {
                            graph.outputNodeIds.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        putJsonArray("nodes") {
            ordered.forEach { node -> add(nodeJson(node, request.pinnedNodeIds)) }
        }
        putJsonArray("commonFields") {
            fields.commonFields.forEach { add(fieldJson(it)) }
        }
        putJsonArray("problems") { problems.forEach { add(problemJson(it)) } }
        putJsonArray("warnings") {
            if (build is PromptBuild.Ready) build.warnings.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("pendingNodeClasses") { pendingClasses.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("missingNodeClasses") {
            missingClasses.filter { it !in pendingClasses }.forEach { add(JsonPrimitive(it)) }
        }
        put("canSubmit", build is PromptBuild.Ready && pendingClasses.isEmpty())
    }.toString()
}

private fun workflowPromptJson(state: SessionState, request: WorkflowUiRequest): String {
    val document = state.workflow.document
        ?: return workflowError("还没有打开工作流", path = state.workflow.loadingPath.orEmpty())
    val definitions = state.nodeInfo
    val selection = selectGraphs(document, definitions, request.enabledGraphIds)
    val allClasses = document.nodeClasses.distinct()
    val pending = allClasses.filter {
        definitions[it]?.missing != false && it !in state.nodeInfoRequested
    }
    if (document.format == WorkflowFormat.UI && pending.isNotEmpty()) {
        return buildJsonObject {
            put("ok", false)
            put("state", "waiting")
            put("message", "节点定义还在加载，请稍后再提交")
            putJsonArray("warnings") {}
            putJsonArray("problems") {
                add(problemJson(BuildProblem(message = "等待节点定义：${pending.joinToString("、")}")))
            }
            put("prompt", JsonNull)
        }.toString()
    }

    val build = PromptBuilder.build(
        document = document,
        definitions = definitions,
        overrides = request.overrides,
        enabledOutputIds = selection.enabledOutputIds,
        forcedNormalNodeIds = selection.forcedNormalNodeIds,
        runToken = request.runToken,
    )
    return when (build) {
        is PromptBuild.Ready -> buildJsonObject {
            put("ok", true)
            put("state", "ready")
            put("message", "")
            put("prompt", build.prompt)
            putJsonArray("warnings") { build.warnings.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("problems") {}
        }.toString()

        is PromptBuild.Blocked -> buildJsonObject {
            put("ok", false)
            put("state", "blocked")
            put("message", "工作流还有问题，不能提交")
            putJsonArray("warnings") {}
            putJsonArray("problems") { build.problems.forEach { add(problemJson(it)) } }
            put("prompt", JsonNull)
        }.toString()
    }
}

private fun workflowError(message: String, path: String = ""): String = buildJsonObject {
    put("ok", false)
    put("state", "error")
    put("message", message)
    put("path", path)
    put("name", "")
    put("format", "")
    put("defaultGraphIds", stringArray(emptyList()))
    put("selectedGraphIds", stringArray(emptyList()))
    putJsonArray("graphs") {}
    putJsonArray("nodes") {}
    putJsonArray("commonFields") {}
    putJsonArray("problems") {}
    putJsonArray("warnings") {}
    putJsonArray("pendingNodeClasses") {}
    putJsonArray("missingNodeClasses") {}
    put("canSubmit", false)
    put("prompt", JsonNull)
}.toString()

private fun nodeJson(node: WorkflowNodeView, pinnedNodeIds: Set<Int>) = buildJsonObject {
    put("id", node.id)
    put("type", node.type)
    put("displayName", node.displayName)
    put("title", node.title)
    put("mode", node.mode)
    put("missing", node.missing)
    put("reachable", node.reachable)
    put("needsInput", node.needsInput)
    put("pinned", node.id in pinnedNodeIds)
    putJsonArray("fields") { node.fields.forEach { add(fieldJson(it)) } }
}

private fun fieldJson(field: WorkflowField) = buildJsonObject {
    put("nodeId", field.nodeId)
    put("nodeType", field.nodeType)
    put("nodeTitle", field.nodeTitle)
    put("inputName", field.inputName)
    put("label", field.label ?: field.inputName)
    put("type", field.type)
    putJsonArray("options") { field.options.forEach { add(JsonPrimitive(it)) } }
    put("value", field.value ?: JsonNull)
    put("valueText", field.valueText())
    put("valueKind", field.valueKind())
    put("linked", field.linked)
    put("widget", field.widget)
    put("required", field.required)
    put("common", field.common)
    put("needsInput", field.needsInput)
}

private fun problemJson(problem: BuildProblem) = buildJsonObject {
    put("nodeId", problem.nodeId)
    put("nodeType", problem.nodeType)
    put("message", problem.message)
    put("display", problem.format())
}

private fun stringArray(values: List<String>) = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun WorkflowField.valueText(): String = when (val value = value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

private fun WorkflowField.valueKind(): String = when (val value = value) {
    null, JsonNull -> "null"
    is JsonPrimitive -> when {
        value.isString -> "string"
        value.contentOrNull == "true" || value.contentOrNull == "false" -> "boolean"
        else -> "number"
    }

    else -> "json"
}

/**
 * 与 Android 的 `orderInputNodes` 保持同一排序：
 * 置顶且可达最前，其余可达按“缺值优先”，未启用/旁路节点最后。
 */
internal fun orderWorkflowNodes(
    nodes: List<WorkflowNodeView>,
    pinnedNodeIds: Set<Int>,
): List<WorkflowNodeView> = nodes.sortedWith(
    compareBy<WorkflowNodeView>(
        { !(it.reachable && it.id in pinnedNodeIds) },
        { !it.reachable },
        { !it.needsInput },
    ),
)
