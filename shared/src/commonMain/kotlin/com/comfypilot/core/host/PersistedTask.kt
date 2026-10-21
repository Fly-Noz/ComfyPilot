package com.comfypilot.core.host

import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.api.longOrNull
import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskProgress
import com.comfypilot.core.task.TaskState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 持久化的任务快照，schema 由 Kotlin 单方面拥有。
 *
 * 宿主（ArkTS RDB、Android 文件或内存）只负责把这段 JSON 原样存进去、原样取出来，
 * 不解析字段、不判断状态——两边各写一份状态映射，早晚会不一致。
 *
 * 兼容策略：不认识的键忽略；不认识的 `state` 整条丢弃。宁可少一条历史，
 * 也不能把一条记录猜成"完成"或"还在跑"。
 */
const val PERSIST_SCHEMA_VERSION = 1

fun PromptTask.toPersistJson(): JsonObject = buildJsonObject {
    put("v", PERSIST_SCHEMA_VERSION)
    put("id", id)
    put("promptId", jsonString(promptId))
    put("name", jsonString(displayName))
    put("state", stateName(state))
    put("reason", jsonString(stateReason(state)))
    put("createdAtMs", createdAtMs)
    put("updatedAtMs", updatedAtMs)
    lastProgressAtMs?.let { put("lastProgressAtMs", it) }
    progress?.let { progress ->
        putJsonObject("progress") {
            put("value", progress.value)
            put("max", progress.max)
            put("node", jsonString(progress.node))
        }
    }
    putJsonArray("outputs") {
        outputs.forEach { output ->
            add(
                buildJsonObject {
                    put("node", output.nodeId)
                    put("kind", output.kind)
                    put("filename", output.filename)
                    put("subfolder", output.subfolder)
                    put("type", output.type)
                },
            )
        }
    }
}

/** 一条坏记录只丢自己，不影响其他记录。 */
fun parsePersistedTask(obj: JsonObject): PromptTask? {
    val id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
    val reason = obj.stringOrNull("reason")
    val state = parseState(obj.stringOrNull("state"), reason) ?: return null
    val createdAtMs = obj.longOrNull("createdAtMs") ?: 0L
    return PromptTask(
        id = id,
        promptId = obj.stringOrNull("promptId")?.takeIf { it.isNotBlank() },
        displayName = obj.stringOrNull("name")?.takeIf { it.isNotBlank() },
        state = state,
        createdAtMs = createdAtMs,
        updatedAtMs = obj.longOrNull("updatedAtMs") ?: createdAtMs,
        progress = (obj["progress"] as? JsonObject)?.let { progress ->
            val value = progress["value"]?.jsonPrimitive?.doubleOrNull ?: return@let null
            val max = progress["max"]?.jsonPrimitive?.doubleOrNull ?: return@let null
            TaskProgress(value = value, max = max, node = progress.stringOrNull("node"))
        },
        outputs = (obj["outputs"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { output -> parseOutput(output) },
        lastProgressAtMs = obj.longOrNull("lastProgressAtMs"),
        error = obj.stringOrNull("reason"),
    )
}

fun parsePersistedTasks(array: JsonArray, limit: Int = Int.MAX_VALUE): List<PromptTask> =
    array.asSequence()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { parsePersistedTask(it) }
        .take(limit.coerceAtLeast(0))
        .toList()

private fun parseState(name: String?, reason: String?): TaskState? = when (name) {
    "Submitting" -> TaskState.Submitting
    "Queued" -> TaskState.Queued
    "Running" -> TaskState.Running
    "Completed" -> TaskState.Completed
    "Cancelled" -> TaskState.Cancelled
    "Failed" -> TaskState.Failed(reason)
    "Unknown" -> TaskState.Unknown(reason)
    else -> null
}

private fun parseOutput(obj: JsonObject): TaskOutput? {
    val filename = obj.stringOrNull("filename")?.takeIf { it.isNotBlank() } ?: return null
    return TaskOutput(
        nodeId = obj.stringOrNull("node") ?: "",
        kind = obj.stringOrNull("kind") ?: "",
        filename = filename,
        subfolder = obj.stringOrNull("subfolder") ?: "",
        type = obj.stringOrNull("type") ?: "output",
    )
}

private fun stateReason(state: TaskState): String? = when (state) {
    is TaskState.Failed -> state.message
    is TaskState.Unknown -> state.reason
    else -> null
}
