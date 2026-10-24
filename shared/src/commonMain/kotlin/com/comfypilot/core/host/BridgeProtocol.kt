package com.comfypilot.core.host

import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ArtifactNames
import com.comfypilot.core.api.ViewRequest
import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.NetworkTransport
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import com.comfypilot.core.workflow.WorkflowCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
        // 两端任务列表都按提交时间从新到旧；鸿蒙直接按这个数组渲染。
        tasks.values.sortedByDescending { it.createdAtMs }.forEach { add(it.toBridgeJson()) }
    }
    // 预览只放元数据；图片字节走按需的 cp_session_preview，避免每帧 JSON 变大。
    put(
        "preview",
        preview?.let { frame ->
            buildJsonObject {
                put("promptId", frame.promptId)
                put("nodeId", frame.nodeId)
                put("receivedAtMs", frame.receivedAtMs)
                put("bytes", frame.image.size)
            }
        } ?: JsonNull,
    )

    // Crystools 监控采样（CPU/GPU/温度/内存/显存）。放在顶层而不是 workflow 里：
    // 宿主判断"要不要重算工作流视图"的摘要键只看 workflow 的 path/format/nodeInfo，
    // 每秒一帧的监控数据因此不会触发昂贵的视图重建，但状态帧仍然带着它。
    put(
        "monitor",
        monitor?.let { sample ->
            buildJsonObject {
                put("cpu", sample.cpuPercent)
                put("ram", sample.ramPercent)
                put("gpu", sample.gpuPercent)
                put("vram", sample.vramPercent)
                put("temperature", sample.temperatureC)
                put("gpuName", sample.gpuName)
                put("receivedAtMs", sample.receivedAtMs)
            }
        } ?: JsonNull,
    )

    // 只放轻量元数据：工作流原文可能有几百 KB，每帧序列化一次不现实。
    // 参数模型/节点定义由宿主在需要时单独拉取（后续 ABI），这里只告诉它"有没有、叫什么"。
    putJsonObject("catalog") {
        when (val catalog = this@toBridgeJson.catalog) {
            is WorkflowCatalog.Idle -> put("state", "idle")
            is WorkflowCatalog.Loading -> put("state", "loading")
            is WorkflowCatalog.Failed -> {
                put("state", "failed")
                put("error", catalog.message)
            }

            is WorkflowCatalog.Ready -> {
                put("state", "ready")
                putJsonArray("entries") {
                    catalog.entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("path", entry.path)
                                put("name", entry.name)
                                put("size", entry.sizeBytes ?: 0L)
                                put("modifiedMs", entry.modifiedAtMs ?: 0L)
                            },
                        )
                    }
                }
            }
        }
    }
    putJsonObject("workflow") {
        val selection = this@toBridgeJson.workflow
        put("loadingPath", jsonString(selection.loadingPath))
        put("path", jsonString(selection.document?.path))
        put("name", jsonString(selection.document?.name))
        put("format", selection.document?.format?.name ?: "")
        put("error", jsonString(selection.error))
        put("nodeClasses", selection.document?.nodeClasses?.size ?: 0)
        put("nodeInfo", nodeInfo.size)
    }
}

fun PromptTask.toBridgeJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("promptId", jsonString(promptId))
    put("name", jsonString(displayName))
    put("workflowPath", jsonString(workflowPath))
    put("state", stateName(state))
    put("percent", progress?.percent ?: 0)
    put("progressValue", progress?.value ?: 0.0)
    put("progressMax", progress?.max ?: 0.0)
    put("progressNode", jsonString(progress?.node))
    put("outputs", outputs.size)
    put("error", jsonString(error ?: (state as? TaskState.Failed)?.message))
    put("updatedAtMs", updatedAtMs)
    // 只在这里补完整产物引用；`outputs` 保留数量，旧宿主不会被新字段影响。
    putJsonArray("outputRefs") {
        outputs.forEach { output ->
            add(
                buildJsonObject {
                    put("nodeId", output.nodeId)
                    put("kind", output.kind)
                    put("filename", output.filename)
                    put("subfolder", output.subfolder)
                    put("type", output.type)
                    // 文件规则归属于共享层，鸿蒙侧不再自己判断图片/视频和 MIME。
                    put("displayName", ArtifactNames.sanitize(output.filename))
                    put("mime", ArtifactNames.mimeType(output.filename))
                    put("image", ArtifactNames.isImage(output.filename))
                    put("video", ArtifactNames.isVideo(output.filename))
                    put("audio", ArtifactNames.isAudio(output.filename))
                    // 相对路径：宿主拼上当前 baseUrl，避免把连接地址写进持久化快照。
                    put(
                        "path",
                        ApiPaths.viewUrl(
                            "",
                            ViewRequest(output.filename, output.subfolder, output.type),
                        ),
                    )
                },
            )
        }
    }
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

        is UserNotice.SubmitAccepted -> {
            put("kind", "submit_accepted")
            put("promptId", notice.promptId)
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

    /** WebSocket 二进制消息；鸿蒙宿主用 base64 透传预览图。 */
    data class SocketBinary(val id: Long, val bytes: ByteArray) : HostMessage

    data class SocketClosed(val id: Long, val code: Int, val reason: String) : HostMessage

    data class SocketFailed(val id: Long, val message: String) : HostMessage

    data class NetworkChanged(val network: NetworkInfo) : HostMessage

    data object Start : HostMessage

    data object Stop : HostMessage

    data object Reconnect : HostMessage

    /** 提交工作流；`name/path` 可选，旧宿主不传时任务只显示 promptId。 */
    data class Submit(
        val workflowJson: String,
        val workflowName: String? = null,
        val workflowPath: String? = null,
    ) : HostMessage

    data class Cancel(val promptId: String) : HostMessage

    /** 拉工作流列表。 */
    data object Workflows : HostMessage

    /** 打开某个工作流文件。 */
    data class OpenWorkflow(val path: String) : HostMessage

    companion object {
        /**
         * 无法识别或字段不全时返回 null：宿主送来的可能是它自己版本的扩展字段，
         * 静默丢弃比让整个会话崩掉好。
         */
        @OptIn(ExperimentalEncodingApi::class)
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

                "ws.binary" -> {
                    val encoded = obj.stringOrNull("base64") ?: return null
                    SocketBinary(
                        id = obj.longOrNull("id") ?: return null,
                        bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return null,
                    )
                }

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
                "submit" -> Submit(
                    workflowJson = obj.stringOrNull("workflow") ?: return null,
                    workflowName = obj.stringOrNull("workflowName"),
                    workflowPath = obj.stringOrNull("workflowPath"),
                )
                "cancel" -> Cancel(obj.stringOrNull("promptId") ?: return null)
                "workflows" -> Workflows
                "workflow.open" -> OpenWorkflow(obj.stringOrNull("path") ?: return null)
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
    stringOrNull(key)?.toLongOrNull()

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
