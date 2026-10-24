package com.comfypilot.core.net

import com.comfypilot.core.api.ApiCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一帧 ComfyUI-Crystools 监控采样。
 *
 * Crystools 并不保证提供 HTTP `/crystools/monitor`（很多服务器上直接 404），
 * 但它会把同样的数据以 `{"type":"crystools.monitor","data":{...}}` 文本帧
 * 推到现有 WebSocket 上。字段都可能缺失，未知一律为 null，界面据此决定省不省格子。
 *
 * [receivedAtMs] 是共享层收到这一帧的时间（宿主时钟），不是服务器时间。
 */
data class MonitorSample(
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val gpuPercent: Double? = null,
    val vramPercent: Double? = null,
    val temperatureC: Double? = null,
    val gpuName: String? = null,
    val receivedAtMs: Long = 0L,
) {
    companion object {
        /** WebSocket 文本帧的 `type` 字段。 */
        const val WS_TYPE = "crystools.monitor"

        /**
         * 解析一帧 WebSocket 文本。
         *
         * 只认 Crystools 监控帧：JSON 坏了、type 不是监控、或者没有可读的 data 对象
         * 一律返回 null（调用方安静忽略），绝不让监控帧打断任务事件。
         * 为了兼容 HTTP 兜底与调试脚本，也接受直接给的 data 对象（没有 type 外壳）。
         */
        fun parse(raw: String, receivedAtMs: Long): MonitorSample? {
            val root = runCatching { ApiCodec.json.parseToJsonElement(raw) as? JsonObject }
                .getOrNull() ?: return null
            val type = (root["type"] as? JsonPrimitive)?.contentOrNull
            if (type != null && !type.equals(WS_TYPE, ignoreCase = true)) return null
            val payload = root["data"] as? JsonObject
                ?: root.takeIf { type == null && it.looksLikeMonitorPayload() }
                ?: return null
            return fromPayload(payload, receivedAtMs)
        }

        /**
         * 从一个 data 对象提取指标；缺字段/非法值都退化成 null，不抛异常。
         *
         * `gpus` 可能是数组（取第一块）或单个对象，也可能整个缺失；GPU 字段
         * 允许直接平铺在 data 上。`-1` 是 Crystools 的"未知"哨兵，映射成 null。
         */
        fun fromPayload(payload: JsonObject, receivedAtMs: Long): MonitorSample {
            val gpus = payload.elementFor("gpus")
            val gpu = when (gpus) {
                is JsonArray -> gpus.firstOrNull() as? JsonObject
                is JsonObject -> gpus
                else -> null
            }
            return MonitorSample(
                cpuPercent = percent(null, payload, "cpu_utilization", "cpu_usage", "cpu"),
                ramPercent = percent(null, payload, "ram_used_percent", "ram_percent"),
                gpuPercent = percent(gpu, payload, "gpu_utilization", "gpu_usage", "gpu"),
                vramPercent = percent(gpu, payload, "vram_used_percent", "vram_percent"),
                temperatureC = number(gpu, payload, "gpu_temperature", "cpu_temperature", "temperature", "temp")
                    ?.takeIf { it in 0.0..300.0 },
                gpuName = text(gpu, payload, "gpu_name", "gpu_model", "device_name"),
                receivedAtMs = receivedAtMs,
            )
        }
    }
}

/** 没带 type 外壳时，用来判断这个对象是不是监控 data。 */
private fun JsonObject.looksLikeMonitorPayload(): Boolean = keys.any { key ->
    key.equals("cpu_utilization", ignoreCase = true) ||
        key.equals("cpu_usage", ignoreCase = true) ||
        key.equals("ram_used_percent", ignoreCase = true) ||
        key.equals("gpus", ignoreCase = true) ||
        key.equals("gpu_utilization", ignoreCase = true)
}

/** 按别名取字段，键名大小写不敏感；找不到返回 null。 */
private fun JsonObject.elementFor(vararg names: String): JsonElement? {
    for ((key, value) in this) {
        if (names.any { it.equals(key, ignoreCase = true) }) return value
    }
    return null
}

/** 数字或"数字字符串"都接受；JSON null / 非数字 / 非有限值一律 null。 */
private fun JsonElement?.numberOrNull(): Double? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** GPU 对象里的值优先，其次才是平铺在 data 上的同名字段。 */
private fun number(nested: JsonObject?, root: JsonObject, vararg names: String): Double? =
    nested?.elementFor(*names).numberOrNull() ?: root.elementFor(*names).numberOrNull()

private fun percent(nested: JsonObject?, root: JsonObject, vararg names: String): Double? =
    number(nested, root, *names)?.takeIf { it in 0.0..100.0 }

private fun text(nested: JsonObject?, root: JsonObject, vararg names: String): String? {
    val element = nested?.elementFor(*names) ?: root.elementFor(*names)
    return (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}