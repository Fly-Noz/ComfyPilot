package com.comfypilot.core

import kotlinx.serialization.json.JsonObject

/**
 * 轻量诊断跟踪：共享层只负责"把发生了什么喊出来"，怎么存、怎么显示交给各平台。
 *
 * 存在的理由：连接/进度类问题（比如长任务没有进度、静默超时重连）在 UI 上只表现为
 * "0%"或"没反应"，没有事件序列根本没法判断是服务端没发还是客户端丢了。
 * 平台注册一个 sink（Android 的 DiagnosticsLog / 鸿蒙侧预留），就能在诊断页看到：
 * WebSocket 收到什么、发了哪些 HTTP、为什么重连。
 *
 * 注意：这里只允许写短字符串，不要做 IO；sink 为空时开销是一次 null 判断。
 */
object DiagnosticTrace {
    /** 平台注册的落点；为空表示当前平台不采集。 */
    var sink: ((String) -> Unit)? = null

    /** 单条文本上限，避免把整张工作流塞进日志。 */
    private const val MAX_TEXT = 200

    fun emit(message: String) {
        sink?.invoke(message)
    }

    /** WebSocket 文本帧：截断并压成一行，方便直接看事件类型与 prompt_id。 */
    fun socketText(text: String) {
        emit("ws<- " + clip(text))
    }

    fun socketBinary(size: Int, bytes: ByteArray? = null) {
        // 采样预览可能有 JPEG / PNG / 动图（GIF、动图 WebP）：把魔数记下来，
        // 排查"预览不动"时能直接区分"帧到了但只显示第一帧"和"根本没有帧"。
        emit("ws<- <binary ${size}B ${imageKind(bytes)}>")
    }

    private fun imageKind(bytes: ByteArray?): String {
        if (bytes == null || bytes.size < 12) return "?"
        fun at(i: Int): Int = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> {
                if (indexOf(bytes, "ANIM".encodeToByteArray()) >= 0) "webp-anim" else "webp"
            }

            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpeg"
            at(0) == 0x89 && at(1) == 0x50 -> "png"
            else -> "?"
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in 0..(haystack.size - needle.size)) {
            var hit = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    hit = false
                    break
                }
            }
            if (hit) return i
        }
        return -1
    }

    fun clip(value: String): String {
        val oneLine = value.replace('\n', ' ').replace('\r', ' ')
        return if (oneLine.length <= MAX_TEXT) oneLine else oneLine.take(MAX_TEXT) + "…"
    }

    /** 把 effects 里的关键动作翻成可读文本；null 表示这条不用记。 */
    fun describeKind(type: String, summary: String): String = "$type $summary"

    fun jsonKeys(json: JsonObject?): String {
        if (json == null) return "{}"
        return json.keys.joinToString(",", "{", "}")
    }
}
