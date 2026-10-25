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

    fun socketBinary(size: Int) {
        emit("ws<- <binary ${size}B>")
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
