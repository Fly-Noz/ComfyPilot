package com.comfypilot.core.event

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.doubleOrNull
import com.comfypilot.core.api.longOrNull
import com.comfypilot.core.api.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * WebSocket 只是提示；这里解出来的任何结论都必须能被 /history 复核。
 */
sealed interface ComfyEvent {
    val promptId: String?

    data class Status(val queueRemaining: Int?) : ComfyEvent {
        override val promptId: String? get() = null
    }

    data class ExecutionStart(override val promptId: String) : ComfyEvent

    data class ExecutionCached(val promptIds: List<String>) : ComfyEvent {
        override val promptId: String? get() = null
    }

    data class Executing(override val promptId: String?, val node: String?) : ComfyEvent

    data class Progress(
        override val promptId: String?,
        val node: String?,
        val value: Double,
        val max: Double,
    ) : ComfyEvent

    data class Executed(
        override val promptId: String,
        val node: String?,
        val output: JsonObject?,
    ) : ComfyEvent

    data class ExecutionError(
        override val promptId: String?,
        val nodeId: String?,
        val message: String?,
    ) : ComfyEvent

    data class ExecutionInterrupted(override val promptId: String?) : ComfyEvent

    data class BinaryPreview(val node: String?) : ComfyEvent {
        override val promptId: String? get() = null
    }

    data class Unknown(val type: String?) : ComfyEvent {
        override val promptId: String? get() = null
    }
}

object EventDecoder {
    fun decode(text: String): ComfyEvent {
        val root = runCatching { ApiCodec.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return ComfyEvent.Unknown(null)
        val data = root["data"] as? JsonObject
        return when (val type = root.stringOrNull("type")) {
            "status" -> ComfyEvent.Status(queueRemaining = data?.queueRemaining())
            "execution_start" -> data?.stringOrNull("prompt_id")
                ?.let { ComfyEvent.ExecutionStart(it) }
                ?: ComfyEvent.Unknown(type)

            "execution_cached" -> ComfyEvent.ExecutionCached(
                promptIds = (data?.get("nodes") as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.stringOrNull("prompt_id") }
                    .orEmpty(),
            )

            "executing" -> ComfyEvent.Executing(
                promptId = data?.stringOrNull("prompt_id"),
                node = data?.stringOrNull("node"),
            )

            "progress" -> ComfyEvent.Progress(
                promptId = data?.stringOrNull("prompt_id"),
                node = data?.stringOrNull("node"),
                value = data?.doubleOrNull("value") ?: 0.0,
                max = data?.doubleOrNull("max") ?: 0.0,
            )

            // ComfyUI 新版只发 progress_state（节点级状态），不再发旧版 progress。
            // 取正在运行的节点；没有运行节点时，用已完成的采样节点（max > 1）兜底。
            "progress_state" -> {
                val promptId = data?.stringOrNull("prompt_id")
                val nodes = data?.get("nodes") as? JsonObject
                val running = nodes?.entries?.firstOrNull { (_, element) ->
                    (element as? JsonObject)?.stringOrNull("state") == "running"
                }
                val finished = running ?: nodes?.entries
                    ?.filter { (_, element) ->
                        val node = element as? JsonObject ?: return@filter false
                        node.stringOrNull("state") == "finished" &&
                            (node.doubleOrNull("max") ?: 0.0) > 1.0
                    }
                    ?.maxByOrNull { (_, element) ->
                        (element as? JsonObject)?.doubleOrNull("max") ?: 0.0
                    }
                val node = finished?.value as? JsonObject
                val nodeId = node?.stringOrNull("node_id") ?: finished?.key
                val value = node?.doubleOrNull("value") ?: 0.0
                val max = node?.doubleOrNull("max") ?: 0.0
                if (promptId != null && nodeId != null && max > 0.0) {
                    ComfyEvent.Progress(promptId, nodeId, value, max)
                } else {
                    ComfyEvent.Unknown(type)
                }
            }

            "executed" -> {
                val id = data?.stringOrNull("prompt_id")
                if (id == null) {
                    ComfyEvent.Unknown(type)
                } else {
                    ComfyEvent.Executed(
                        promptId = id,
                        node = data?.stringOrNull("node"),
                        output = data["output"] as? JsonObject,
                    )
                }
            }

            "execution_error" -> ComfyEvent.ExecutionError(
                promptId = data?.stringOrNull("prompt_id"),
                nodeId = data?.stringOrNull("node_id"),
                message = data?.stringOrNull("exception_message"),
            )

            "execution_interrupted" -> ComfyEvent.ExecutionInterrupted(data?.stringOrNull("prompt_id"))

            "b_preview", "b_preview_with_metadata", "preview_image" -> ComfyEvent.BinaryPreview(
                node = data?.stringOrNull("node"),
            )

            else -> ComfyEvent.Unknown(type)
        }
    }

    private fun JsonObject.queueRemaining(): Int? {
        val status = this["status"] as? JsonObject ?: return null
        val execInfo = status["exec_info"] as? JsonObject ?: return null
        return execInfo.longOrNull("queue_remaining")?.toInt()
    }
}
