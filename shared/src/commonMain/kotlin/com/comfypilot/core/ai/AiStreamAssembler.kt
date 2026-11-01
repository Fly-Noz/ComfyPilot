package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把 OpenAI 兼容的 SSE 分片拼成一条完整 message。
 *
 * 纯逻辑、不碰网络：Android 读 OkHttp 的流、鸿蒙读 requestInStream 的 chunk，
 * 两边喂同样的行，得到同样的结果。`tool_calls` 是分片到达的（name 和 arguments
 * 都会被切散），必须按 `index` 累积，拼完才能交给循环去执行。
 */
class AiStreamAssembler {

    private val calls = LinkedHashMap<Int, Call>()
    private val content = StringBuilder()
    private val reasoning = StringBuilder()

    /** 是否见过真正的数据行（用来判断「服务端压根没走流式」）。 */
    var sawData: Boolean = false
        private set

    val text: String get() = content.toString()
    val thoughts: String get() = reasoning.toString()

    /** 喂一行；`data: [DONE]` 与空行忽略，坏 JSON 也忽略（一条坏行不该打断整段）。 */
    fun feed(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return
        val data = trimmed.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return
        sawData = true
        apply(data)
    }

    /** 拼好的 message；正文和工具调用都没有时返回 null（交给整段请求重来）。 */
    fun message(): JsonObject? {
        if (content.isEmpty() && calls.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            put("content", content.toString())
            if (calls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    // toSortedMap 是 JVM 专有，K/N 上要用 sortedBy。
                    calls.entries.sortedBy { it.key }.forEach { entry ->
                        val call = entry.value
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.args.ifBlank { "{}" })
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun apply(data: String) {
        val root = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject ?: return
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { content.append(it) }
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.let { reasoning.append(it) }
        val parts = delta["tool_calls"]?.jsonArray ?: return
        for (part in parts) {
            val obj = part as? JsonObject ?: continue
            val index = (obj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val call = calls.getOrPut(index) { Call() }
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { call.id = it }
            val fn = obj["function"] as? JsonObject ?: continue
            (fn["name"] as? JsonPrimitive)?.contentOrNull?.let { call.name += it }
            (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { call.args += it }
        }
    }

    private class Call {
        var id: String = ""
        var name: String = ""
        var args: String = ""
    }
}
