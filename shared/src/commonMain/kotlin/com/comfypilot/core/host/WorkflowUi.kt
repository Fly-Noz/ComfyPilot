package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.BuildProblem
import com.comfypilot.core.workflow.PromptBuild
import com.comfypilot.core.workflow.WorkflowDecisions
import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowFormat
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
        WorkflowUiRequest.OP_DISABLE_IMPACT -> workflowDisableImpactJson(state, request)
        else -> workflowError("未知的工作流操作：${request.op}")
    }
}

private data class WorkflowUiRequest(
    val op: String,
    val pinnedNodeIds: Set<Int>,
    val enabledGraphIds: Set<String>?,
    val overrides: Map<Int, Map<String, JsonElement>>,
    /** 用户逐个关闭的节点；null 表示请求没带，沿用会话状态里的集合。 */
    val bypassedNodeIds: Set<Int>?,
    /** 用户逐个重新启用的节点；null 表示请求没带，沿用会话状态里的集合。 */
    val enabledNodeIds: Set<Int>?,
    /** 本次会话里被断开的连线。 */
    val removedLinkIds: Set<Int>,
    /** 本次会话里新建/改接的连线。 */
    val linkOverrides: List<com.comfypilot.core.workflow.InputLinkOverride>,
    /** 仅关闭影响探测使用：要试关的节点。 */
    val nodeId: Int?,
    /** 仅提交时使用：保存类节点的文件名追加这个短 token，避免产物互相覆盖。 */
    val runToken: String?,
) {
    companion object {
        const val OP_VIEW = "view"
        const val OP_PROMPT = "prompt"
        const val OP_DISABLE_IMPACT = "disable_impact"

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
                bypassedNodeIds = parseNodeIds(obj["bypassedNodeIds"]),
                enabledNodeIds = parseNodeIds(obj["enabledNodeIds"]),
                removedLinkIds = parseNodeIds(obj["removedLinkIds"]).orEmpty(),
                linkOverrides = parseLinkOverrides(obj["linkOverrides"] as? JsonArray),
                nodeId = (obj["nodeId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                runToken = obj.stringOrNull("run")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun parseGraphIds(obj: JsonObject): Set<String>? {
            val element = obj["graphs"] ?: return null
            if (element is JsonNull) return null
            val array = element as? JsonArray ?: return null
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        }

        private fun parseLinkOverrides(array: JsonArray?): List<com.comfypilot.core.workflow.InputLinkOverride> {
            if (array == null) return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val nodeId = (obj["nodeId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                val inputName = obj.stringOrNull("inputName") ?: return@mapNotNull null
                com.comfypilot.core.workflow.InputLinkOverride(
                    nodeId = nodeId,
                    inputName = inputName,
                    originNodeId = (obj["originNodeId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    originSlot = (obj["originSlot"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    type = obj.stringOrNull("type") ?: "",
                )
            }
        }
        /** 显式传空数组 = 全部启用；没带这个键 = 由会话状态决定。 */
        private fun parseNodeIds(element: JsonElement?): Set<Int>? {
            if (element == null || element is JsonNull) return null
            val array = element as? JsonArray ?: return null
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.toSet()
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

private fun workflowViewJson(state: SessionState, request: WorkflowUiRequest): String {
    val document = state.workflow.document
        ?: return workflowError("还没有打开工作流", path = state.workflow.loadingPath.orEmpty())
    val bypassedNodeIds = request.effectiveBypassedNodeIds(state)
    val decision = WorkflowDecisions.evaluate(
        document = document,
        definitions = state.nodeInfo,
        requestedGraphIds = request.enabledGraphIds,
        pinnedNodeIds = request.pinnedNodeIds,
        overrides = request.overrides,
        nodeInfoRequested = state.nodeInfoRequested,
        // 视图永远不带提交 token；唯一后缀只在真正提交时追加。
        runToken = null,
        bypassedNodeIds = bypassedNodeIds,
        enabledNodeIds = request.effectiveEnabledNodeIds(state),
        removedLinkIds = request.removedLinkIds,
        linkOverrides = request.linkOverrides,
    )

    return buildJsonObject {
        put("ok", true)
        put("state", "ready")
        put("path", decision.path)
        put("name", decision.name)
        put("format", decision.format.name)
        put("defaultGraphIds", stringArray(decision.defaultGraphIds))
        put("selectedGraphIds", stringArray(decision.selectedGraphIds.sorted()))
        putJsonArray("bypassedNodeIds") {
            decision.bypassedNodeIds.sorted().forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("enabledNodeIds") {
            decision.enabledNodeIds.sorted().forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("graphs") {
            decision.graphs.forEach { graph ->
                add(
                    buildJsonObject {
                        put("id", graph.id)
                        put("title", graph.title)
                        put("enabled", graph.enabled)
                        put("selected", graph.id in decision.selectedGraphIds)
                        put("parentId", graph.parentId ?: "")
                        putJsonArray("nodeIds") { graph.nodeIds.sorted().forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("outputNodeIds") {
                            graph.outputNodeIds.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        putJsonArray("nodes") {
            decision.nodes.forEach { node -> add(nodeJson(node, request.pinnedNodeIds)) }
        }
        putJsonArray("commonFields") {
            decision.commonFields.forEach { add(fieldJson(it)) }
        }
        putJsonArray("problems") { decision.problems.forEach { add(problemJson(it)) } }
        putJsonArray("warnings") { decision.warnings.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("pendingNodeClasses") {
            decision.pendingNodeClasses.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("missingNodeClasses") {
            decision.missingNodeClasses.forEach { add(JsonPrimitive(it)) }
        }
        put("graph", workflowGraphJson(document, decision, request))
        put("canSubmit", decision.canSubmit)
        put("blockReason", decision.blockReason ?: "")
    }.toString()
}

private fun workflowPromptJson(state: SessionState, request: WorkflowUiRequest): String {
    val document = state.workflow.document
        ?: return workflowError("还没有打开工作流", path = state.workflow.loadingPath.orEmpty())
    val decision = WorkflowDecisions.evaluate(
        document = document,
        definitions = state.nodeInfo,
        requestedGraphIds = request.enabledGraphIds,
        pinnedNodeIds = request.pinnedNodeIds,
        overrides = request.overrides,
        nodeInfoRequested = state.nodeInfoRequested,
        runToken = request.runToken,
        bypassedNodeIds = request.effectiveBypassedNodeIds(state),
        enabledNodeIds = request.effectiveEnabledNodeIds(state),
    )
    if (decision.format == WorkflowFormat.UI && decision.pendingNodeClasses.isNotEmpty()) {
        return buildJsonObject {
            put("ok", false)
            put("state", "waiting")
            put("message", "节点定义还在加载，请稍后再提交")
            putJsonArray("warnings") {}
            putJsonArray("problems") {
                add(
                    problemJson(
                        BuildProblem(
                            message = "等待节点定义：${decision.pendingNodeClasses.joinToString("、")}",
                        ),
                    ),
                )
            }
            put("prompt", JsonNull)
        }.toString()
    }

    return when (val build = decision.build) {
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

/**
 * 关闭单个节点前的同步影响探测（`op=disable_impact`）。
 *
 * 只构建 prompt、不落盘、不发请求；宿主在用户点开关时调用一次即可。
 */
private fun workflowDisableImpactJson(state: SessionState, request: WorkflowUiRequest): String {
    val nodeId = request.nodeId
        ?: return workflowImpactError("缺少要关闭的节点 id")
    val document = state.workflow.document
        ?: return workflowImpactError("还没有打开工作流")
    val impact = WorkflowDecisions.probeNodeDisable(
        document = document,
        definitions = state.nodeInfo,
        nodeId = nodeId,
        requestedGraphIds = request.enabledGraphIds,
        overrides = request.overrides,
        bypassedNodeIds = request.effectiveBypassedNodeIds(state),
        enabledNodeIds = request.effectiveEnabledNodeIds(state),
    )
    return buildJsonObject {
        put("ok", true)
        put("state", "ready")
        put("nodeId", nodeId)
        put("breaking", impact.breaking)
        put("reason", impact.reason ?: "")
    }.toString()
}

private fun workflowImpactError(message: String): String = buildJsonObject {
    put("ok", false)
    put("state", "error")
    put("message", message)
    put("breaking", false)
    put("reason", "")
}.toString()

/** 请求没带 `bypassedNodeIds` 时沿用会话状态：宿主刷新视图不会丢掉开关。 */
private fun WorkflowUiRequest.effectiveBypassedNodeIds(state: SessionState): Set<Int> =
    bypassedNodeIds ?: state.bypassedNodeIds

/** 同理：请求没带 `enabledNodeIds` 时沿用会话状态里重新启用的节点。 */
private fun WorkflowUiRequest.effectiveEnabledNodeIds(state: SessionState): Set<Int> =
    enabledNodeIds ?: state.enabledNodeIds

private fun workflowError(message: String, path: String = ""): String = buildJsonObject {
    put("ok", false)
    put("state", "error")
    put("message", message)
    put("path", path)
    put("name", "")
    put("format", "")
    put("defaultGraphIds", stringArray(emptyList()))
    put("selectedGraphIds", stringArray(emptyList()))
    putJsonArray("bypassedNodeIds") {}
    putJsonArray("enabledNodeIds") {}
    putJsonArray("graphs") {}
    putJsonArray("nodes") {}
    putJsonArray("commonFields") {}
    putJsonArray("problems") {}
    putJsonArray("warnings") {}
    putJsonArray("pendingNodeClasses") {}
    putJsonArray("missingNodeClasses") {}
    put("canSubmit", false)
    put("blockReason", "不可提交：还没有打开工作流")
    put("prompt", JsonNull)
}.toString()

private fun nodeJson(node: WorkflowNodeView, pinnedNodeIds: Set<Int>) = buildJsonObject {
    put("id", node.id)
    put("type", node.type)
    put("displayName", node.displayName)
    put("title", node.title)
    put("mode", node.mode)
    // 界面开关读 enabled（文件模式 + 本次会话覆盖后的生效状态），不能只看 bypassed。
    put("enabled", node.enabled)
    put("bypassed", node.bypassed)
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
  field.fromNodeId?.let { put("fromNodeId", it) }
  field.fromSlot?.let { put("fromSlot", it) }
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
 * 节点图预览数据（鸿蒙 Canvas 直接画）。
 *
 * 坐标/尺寸沿用工作流文件里的画布坐标；拓扑已经叠加本次会话的断线与改接，
 * 因此 ArkTS 不需要在本地重算连线规则。
 */
private fun workflowGraphJson(
    document: com.comfypilot.core.workflow.WorkflowDocument,
    decision: com.comfypilot.core.workflow.WorkflowDecision,
    request: WorkflowUiRequest,
): JsonObject = buildJsonObject {
    val parsed = com.comfypilot.core.workflow.UiWorkflowParser.parse(document.raw)
    if (parsed == null) return@buildJsonObject
    val graph = parsed
        .withoutLinks(request.removedLinkIds)
        .withInputLinks(request.linkOverrides)
        .withEffectiveModes(
            forcedNormalNodeIds = decision.forcedNormalNodeIds,
            bypassedNodeIds = decision.bypassedNodeIds,
            enabledNodeIds = decision.enabledNodeIds,
        )
    putJsonArray("nodes") {
        graph.nodes.forEach { node ->
            add(
                buildJsonObject {
                    put("id", node.id)
                    put("type", node.type)
                    put("title", node.title ?: "")
                    put("mode", node.mode)
                    put("x", node.x)
                    put("y", node.y)
                    put("w", node.width)
                    put("h", node.height)
                    putJsonArray("inputs") {
                        node.inputs.forEach { input ->
                            add(
                                buildJsonObject {
                                    put("name", input.name)
                                    put("type", input.type)
                                    put("linkId", input.linkId ?: 0)
                                },
                            )
                        }
                    }
                    putJsonArray("outputs") {
                        node.outputs.forEach { output ->
                            add(
                                buildJsonObject {
                                    put("name", output.name)
                                    put("type", output.type)
                                },
                            )
                        }
                    }
                },
            )
        }
    }
    putJsonArray("links") {
        graph.links.values.forEach { link ->
            add(
                buildJsonObject {
                    put("id", link.id)
                    put("originId", link.originId)
                    put("originSlot", link.originSlot)
                    put("targetId", link.targetId)
                    put("targetSlot", link.targetSlot)
                    put("type", link.type)
                },
            )
        }
    }
    putJsonArray("groups") {
        graph.groups.forEach { group ->
            add(
                buildJsonObject {
                    put("id", group.id)
                    put("title", group.title)
                    put("x", group.x)
                    put("y", group.y)
                    put("w", group.width)
                    put("h", group.height)
                },
            )
        }
    }
    putJsonArray("activeNodeIds") {
        decision.nodes.filter { it.mode == 0 }.forEach { add(JsonPrimitive(it.id)) }
    }
    putJsonArray("disabledNodeIds") {
        decision.disabledNodeIds.sorted().forEach { add(JsonPrimitive(it)) }
    }
}