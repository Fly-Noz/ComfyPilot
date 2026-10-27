package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * 宿主无关的 AI 桥：把 ArkTS/Android 传进来的工作流视图与编辑状态转成
 * digest，或校验并应用模型给出的 proposal。
 *
 * 请求/响应都是 JSON，鸿蒙侧通过 `cp_ai_digest` / `cp_ai_apply` 调用，
 * Android 也可以复用（当前 Android 直接调用 Kotlin API）。
 */
object AiBridge {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun digest(requestJson: String): String = runCatching {
        val request = json.parseToJsonElement(requestJson).jsonObject
        val view = parseView(request)
        val state = parseState(request["state"] as? JsonObject)
        val digest = AiDigest.build(view, state)
        buildJsonObject {
            put("ok", true)
            put("digest", digest)
            put("tokens", AiDigest.estimateTokens(digest))
        }.toString()
    }.getOrElse { error ->
        buildJsonObject {
            put("ok", false)
            put("message", error.message ?: "digest failed")
        }.toString()
    }

    fun apply(requestJson: String): String = runCatching {
        val request = json.parseToJsonElement(requestJson).jsonObject
        val view = parseView(request)
        val state = parseState(request["state"] as? JsonObject)
        val proposalElement = request["proposal"] ?: error("proposal 为空")
        val proposal = AiEdits.parse(proposalElement.toString()) ?: error("proposal 结构不合法")
        val result = AiEdits.apply(view, state, proposal)
        buildJsonObject {
            put("ok", result.ok)
            putJsonArray("changes") {
                result.changes.forEach { add(JsonPrimitive(it.label)) }
            }
            putJsonArray("errors") {
                result.errors.forEach { add(JsonPrimitive(it)) }
            }
            put("state", stateJson(result.state))
            putJsonArray("edits") {
                result.state.overrides.forEach { (nodeId, fields) ->
                    fields.forEach { (name, value) ->
                        add(
                            buildJsonObject {
                                put("node", nodeId)
                                put("input", name)
                                put("value", value)
                            },
                        )
                    }
                }
            }
            put(
                "bypassed",
                buildJsonArray { result.state.bypassed.sorted().forEach { add(JsonPrimitive(it)) } },
            )
            put(
                "enabled",
                buildJsonArray { result.state.enabled.sorted().forEach { add(JsonPrimitive(it)) } },
            )
            val graphs = result.state.graphs
            if (graphs == null) {
                put("graphs", JsonNull)
            } else {
                put("graphs", buildJsonArray { graphs.sorted().forEach { add(JsonPrimitive(it)) } })
            }
            put("openWorkflow", result.openWorkflow ?: "")
        }.toString()
    }.getOrElse { error ->
        buildJsonObject {
            put("ok", false)
            put("message", error.message ?: "apply failed")
        }.toString()
    }

    private fun parseView(request: JsonObject): AiWorkflowView {
        val nodes = (request["nodes"] as? JsonArray).orEmpty().map { element ->
            val node = element.jsonObject
            AiNode(
                id = node.int("id"),
                className = node.string("class"),
                title = node.string("title"),
                enabled = node["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                reachable = node["reachable"]?.jsonPrimitive?.booleanOrNull ?: true,
                fields = (node["fields"] as? JsonArray).orEmpty().map { fieldElement ->
                    val field = fieldElement.jsonObject
                    AiField(
                        nodeId = node.int("id"),
                        input = field.string("name"),
                        type = field.string("type"),
                        value = field["value"],
                        editable = field["editable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                },
            )
        }
        val graphs = (request["graphs"] as? JsonArray).orEmpty().map { element ->
            val graph = element.jsonObject
            AiGraph(
                id = graph.string("id"),
                title = graph.string("title"),
                selected = graph["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        return AiWorkflowView(path = request.string("path"), nodes = nodes, graphs = graphs)
    }

    private fun parseState(element: JsonObject?): AiEditState {
        if (element == null) return AiEditState()
        val overrides = mutableMapOf<Int, Map<String, JsonElement>>()
        (element["overrides"] as? JsonObject)?.forEach { (nodeKey, fieldsElement) ->
            val nodeId = nodeKey.toIntOrNull() ?: return@forEach
            val fields = (fieldsElement as? JsonObject) ?: return@forEach
            overrides[nodeId] = fields.mapValues { it.value }
        }
        val graphs = (element["graphs"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
        return AiEditState(
            overrides = overrides,
            bypassed = intSet(element["bypassed"]),
            enabled = intSet(element["enabled"]),
            graphs = graphs,
        )
    }

    private fun stateJson(state: AiEditState): JsonObject = buildJsonObject {
        putJsonObject("overrides") {
            state.overrides.forEach { (nodeId, fields) ->
                putJsonObject(nodeId.toString()) {
                    fields.forEach { (name, value) -> put(name, value) }
                }
            }
        }
        put(
            "bypassed",
            buildJsonArray { state.bypassed.sorted().forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "enabled",
            buildJsonArray { state.enabled.sorted().forEach { add(JsonPrimitive(it)) } },
        )
        val graphs = state.graphs
        if (graphs == null) {
            put("graphs", JsonNull)
        } else {
            put(
                "graphs",
                buildJsonArray { graphs.sorted().forEach { add(JsonPrimitive(it)) } },
            )
        }
    }

    private fun intSet(element: JsonElement?): Set<Int> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
