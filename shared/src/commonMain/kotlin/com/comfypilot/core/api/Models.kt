package com.comfypilot.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ---------- /prompt ----------

@Serializable
data class PromptRequestBody(
    val prompt: JsonObject,
    @SerialName("client_id") val clientId: String,
    @SerialName("extra_data") val extraData: JsonObject? = null,
)

@Serializable
data class PromptErrorBody(
    val type: String? = null,
    val message: String? = null,
    val details: String? = null,
)

@Serializable
data class PromptResponse(
    @SerialName("prompt_id") val promptId: String? = null,
    val number: Long? = null,
    val error: PromptErrorBody? = null,
    @SerialName("node_errors") val nodeErrors: JsonObject? = null,
) {
    val accepted: Boolean get() = error == null && promptId != null

    /** ComfyUI 的 `node_errors` 是 `{nodeId: {class_type, errors[]}}`；把常用字段摊平成可读行。 */
    val nodeErrorMessages: List<String>
        get() = nodeErrors.orEmpty().mapNotNull { (nodeId, element) ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val classType = node.stringOrNull("class_type")
            val errors = (node["errors"] as? JsonArray).orEmpty().mapNotNull { item ->
                val error = item as? JsonObject ?: return@mapNotNull null
                listOfNotNull(error.stringOrNull("message"), error.stringOrNull("details"))
                    .joinToString(" / ")
                    .ifBlank { error.stringOrNull("type") ?: "错误" }
            }
            if (errors.isEmpty()) {
                null
            } else {
                buildString {
                    append('#').append(nodeId)
                    if (!classType.isNullOrBlank()) append(' ').append(classType)
                    append('：').append(errors.joinToString("；"))
                }
            }
        }

    val failureMessage: String?
        get() {
            val details = nodeErrorMessages
            if (error == null && promptId != null) return null
            val head = when {
                error != null -> listOfNotNull(error.type, error.message, error.details)
                    .joinToString(" / ")
                    .ifBlank { "提交被 ComfyUI 拒绝" }

                else -> "ComfyUI 未返回 prompt_id"
            }
            val shown = details.take(6)
            val suffix = if (details.size > shown.size) listOf("…另有 ${details.size - shown.size} 条") else emptyList()
            return (listOf(head) + shown + suffix).joinToString("\n")
        }
}

// ---------- /queue ----------

@Serializable
data class QueueResponse(
    @SerialName("queue_running") val queueRunning: List<JsonArray> = emptyList(),
    @SerialName("queue_pending") val queuePending: List<JsonArray> = emptyList(),
)

data class QueueItem(val promptId: String, val number: Long?)

data class QueueSnapshot(
    val running: List<QueueItem> = emptyList(),
    val pending: List<QueueItem> = emptyList(),
) {
    fun contains(promptId: String): Boolean =
        running.any { it.promptId == promptId } || pending.any { it.promptId == promptId }

    val isEmpty: Boolean get() = running.isEmpty() && pending.isEmpty()

    companion object {
        val EMPTY = QueueSnapshot()

        /** queue 的条目是位置数组：[number, prompt_id, prompt, extra_data, outputs]。对未知形状保持容忍。 */
        fun from(response: QueueResponse): QueueSnapshot = QueueSnapshot(
            running = response.queueRunning.mapNotNull(::toItem),
            pending = response.queuePending.mapNotNull(::toItem),
        )

        private fun toItem(row: JsonArray): QueueItem? {
            val id = (row.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return null
            val number = (row.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            return QueueItem(id, number)
        }
    }
}

// ---------- /history/{prompt_id} ----------

@Serializable
data class HistoryStatus(
    @SerialName("status_str") val statusStr: String? = null,
    val completed: Boolean = false,
    val messages: List<JsonArray> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val outputs: Map<String, JsonObject> = emptyMap(),
    val status: HistoryStatus? = null,
) {
    /** ComfyUI 把错误塞在 status.messages 里，形如 ["execution_error", {...}]。 */
    val errorMessages: List<String>
        get() = status?.messages.orEmpty().mapNotNull { message ->
            if (message.eventType() != "execution_error") return@mapNotNull null
            val payload = message.getOrNull(1) as? JsonObject ?: return@mapNotNull "执行失败"
            (payload["exception_message"] as? JsonPrimitive)?.contentOrNull
                ?: (payload["exception_type"] as? JsonPrimitive)?.contentOrNull
                ?: "执行失败"
        }

    val isError: Boolean get() = status?.statusStr == "error" || errorMessages.isNotEmpty()

    val isInterrupted: Boolean
        get() = status?.messages.orEmpty().any { it.eventType() == "execution_interrupted" }

    val isSuccess: Boolean get() = status?.completed == true || outputs.isNotEmpty()

    val hasOutput: Boolean get() = outputs.isNotEmpty()

    /** 不假设 output 的键名是 images；任何带 filename 的对象都算产物。 */
    fun extractOutputs(): List<TaskOutput> = buildList {
        for ((nodeId, nodeOutputs) in outputs) {
            for ((kind, value) in nodeOutputs) {
                val items = value as? JsonArray ?: continue
                for (item in items) {
                    val obj = item as? JsonObject ?: continue
                    val filename = obj.stringOrNull("filename") ?: continue
                    add(
                        TaskOutput(
                            nodeId = nodeId,
                            kind = kind,
                            filename = filename,
                            subfolder = obj.stringOrNull("subfolder").orEmpty(),
                            type = obj.stringOrNull("type") ?: "output",
                        ),
                    )
                }
            }
        }
    }

    private fun JsonArray.eventType(): String? = (getOrNull(0) as? JsonPrimitive)?.contentOrNull
}

data class TaskOutput(
    val nodeId: String,
    val kind: String,
    val filename: String,
    val subfolder: String,
    val type: String,
)

fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.longOrNull(key: String): Long? = try {
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
} catch (_: IllegalArgumentException) {
    null
}

fun JsonObject.doubleOrNull(key: String): Double? = try {
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
} catch (_: IllegalArgumentException) {
    null
}

// ---------- /upload/image ----------

@Serializable
data class UploadImageResponse(
    val name: String,
    val subfolder: String = "",
    val type: String = "input",
)

// ---------- /view ----------

data class ViewRequest(
    val filename: String,
    val subfolder: String = "",
    val type: String = "output",
    val preview: Boolean = false,
)

// ---------- 通用错误 ----------

@Serializable
data class ApiErrorBody(
    val error: PromptErrorBody? = null,
    @SerialName("node_errors") val nodeErrors: JsonObject? = null,
)
