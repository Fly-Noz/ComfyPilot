package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.NetworkTransport
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Kotlin 与宿主之间的唯一契约：一行一个 JSON 对象，字段都是扁平的基本类型。
 *
 * Kotlin -> 宿主：[BridgeFrame] 里的 commands。
 * 宿主 -> Kotlin：[HostMessage]。
 *
 * 用 JsonObject 手搭而不是 @Serializable 密封类，是为了让字段增删不破坏对端：
 * 两边都只读自己认识的键。
 */
internal val bridgeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal fun jsonString(value: String?): kotlinx.serialization.json.JsonElement =
    kotlinx.serialization.json.JsonPrimitive(value)

// ---------------------------------------------------------------- Kotlin -> 宿主

fun SessionState.toBridgeJson(): JsonObject = buildJsonObject {
    put("phase", phaseName(phase))
    put("label", connectionLabel)
    put("generation", generation)
    put("leaseId", lease?.leaseId ?: 0L)
    put("network", network.transport.name)
    put("validated", network.validated)
    put("baseUrl", lease?.baseUrl ?: "")
    put("queueRunning", queue?.running?.size ?: 0)
    put("queuePending", queue?.pending?.size ?: 0)
    putJsonArray("tasks") {
        tasks.values.sortedBy { it.createdAtMs }.forEach { add(it.toBridgeJson()) }
    }
}

fun PromptTask.toBridgeJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("promptId", jsonString(promptId))
    put("name", jsonString(displayName))
    put("state", stateName(state))
    put("percent", progress?.percent ?: 0)
    put("outputs", outputs.size)
    put("error", jsonString(error ?: (state as? TaskState.Failed)?.message))
    put("updatedAtMs", updatedAtMs)
}

fun UserNotice.toBridgeJson(): JsonObject = buildJsonObject {
    when (val notice = this@toBridgeJson) {
        is UserNotice.Connected -> {
            put("kind", "connected")
            put("generation", notice.generation)
        }

        is UserNotice.Disconnected -> {
            put("kind", "disconnected")
            put("reason", notice.reason)
        }

        is UserNotice.TaskFailed -> {
            put("kind", "task_failed")
            put("promptId", notice.promptId)
            put("message", jsonString(notice.message))
        }

        is UserNotice.TaskLost -> {
            put("kind", "task_lost")
            put("promptId", notice.promptId)
            put("message", notice.message)
        }

        is UserNotice.ProgressTimeout -> {
            put("kind", "progress_timeout")
            put("promptIds", buildJsonArray { notice.promptIds.forEach { add(jsonString(it)) } })
        }

        is UserNotice.ReconcileGaveUp -> {
            put("kind", "reconcile_gave_up")
            put("promptIds", buildJsonArray { notice.promptIds.forEach { add(jsonString(it)) } })
        }

        is UserNotice.SubmitRejected -> {
            put("kind", "submit_rejected")
            put("message", notice.message)
        }
    }
}

internal fun phaseName(phase: SessionPhase): String = when (phase) {
    is SessionPhase.Idle -> "Idle"
    is SessionPhase.Connecting -> "Connecting"
    is SessionPhase.Connected -> "Connected"
    is SessionPhase.Reconnecting -> "Reconnecting"
    is SessionPhase.Recovering -> "Recovering"
}

internal fun stateName(state: TaskState): String = when (state) {
    is TaskState.Submitting -> "Submitting"
    is TaskState.Queued -> "Queued"
    is TaskState.Running -> "Running"
    is TaskState.Completed -> "Completed"
    is TaskState.Failed -> "Failed"
    is TaskState.Cancelled -> "Cancelled"
    is TaskState.Unknown -> "Unknown"
}

// ---------------------------------------------------------------- 宿主 -> Kotlin

sealed interface HostMessage {
    data class HttpResult(val id: Long, val status: Int, val body: String?, val failure: String?) : HostMessage

    data class SocketOpened(val id: Long) : HostMessage

    data class SocketText(val id: Long, val text: String) : HostMessage

    data class SocketClosed(val id: Long, val code: Int, val reason: String) : HostMessage

    data class SocketFailed(val id: Long, val message: String) : HostMessage

    data class NetworkChanged(val network: NetworkInfo) : HostMessage

    data object Start : HostMessage

    data object Stop : HostMessage

    data object Reconnect : HostMessage

    data class Submit(val workflowJson: String) : HostMessage

    data class Cancel(val promptId: String) : HostMessage

    companion object {
        /**
         * 无法识别或字段不全时返回 null：宿主送来的可能是它自己版本的扩展字段，
         * 静默丢弃比让整个会话崩掉好。
         */
        fun parse(raw: String): HostMessage? {
            val obj = runCatching { bridgeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            return when (obj.stringOrNull("t")) {
                "http.result" -> HttpResult(
                    id = obj.longOrNull("id") ?: return null,
                    status = (obj.longOrNull("status") ?: 0L).toInt(),
                    body = obj.stringOrNull("body"),
                    failure = obj.stringOrNull("failure"),
                )

                "ws.opened" -> SocketOpened(obj.longOrNull("id") ?: return null)

                "ws.text" -> SocketText(
                    id = obj.longOrNull("id") ?: return null,
                    text = obj.stringOrNull("text") ?: return null,
                )

                "ws.closed" -> SocketClosed(
                    id = obj.longOrNull("id") ?: return null,
                    code = (obj.longOrNull("code") ?: 1006L).toInt(),
                    reason = obj.stringOrNull("reason").orEmpty(),
                )

                "ws.failed" -> SocketFailed(
                    id = obj.longOrNull("id") ?: return null,
                    message = obj.stringOrNull("message") ?: "WebSocket 失败",
                )

                "net" -> NetworkChanged(readNetwork(obj))

                "start" -> Start
                "stop" -> Stop
                "reconnect" -> Reconnect
                "submit" -> Submit(obj.stringOrNull("workflow") ?: return null)
                "cancel" -> Cancel(obj.stringOrNull("promptId") ?: return null)
                else -> null
            }
        }

        private fun readNetwork(obj: JsonObject): NetworkInfo = NetworkInfo(
            transport = when (obj.stringOrNull("transport")?.lowercase()) {
                "wifi" -> NetworkTransport.WIFI
                "cellular" -> NetworkTransport.CELLULAR
                "ethernet" -> NetworkTransport.ETHERNET
                "none" -> NetworkTransport.NONE
                else -> NetworkTransport.OTHER
            },
            validated = obj.stringOrNull("validated")?.toBooleanStrictOrNull()
                ?: (obj.longOrNull("validated") == 1L),
            handle = obj.stringOrNull("handle"),
        )
    }
}

internal fun JsonObject.longOrNull(key: String): Long? =
    stringOrNull(key)?.toLongOrNull() ?: (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.let { if (it.isString) null else it.content.toLongOrNull() }

internal fun buildNetworkCommand(network: NetworkInfo): JsonObject = buildJsonObject {
    put("t", "net")
    put("transport", network.transport.name.lowercase())
    put("validated", network.validated)
    put("handle", jsonString(network.handle))
}

internal fun buildHttpCommand(
    id: Long,
    method: String,
    url: String,
    contentType: String?,
    headers: Map<String, String>,
    bodyBase64: String?,
    timeoutMs: Long,
): JsonObject = buildJsonObject {
    put("t", "http")
    put("id", id)
    put("method", method)
    put("url", url)
    put("timeoutMs", timeoutMs)
    if (contentType != null) put("contentType", contentType)
    if (headers.isNotEmpty()) {
        putJsonObject("headers") { headers.forEach { (key, value) -> put(key, value) } }
    }
    if (bodyBase64 != null) put("body", bodyBase64)
}
