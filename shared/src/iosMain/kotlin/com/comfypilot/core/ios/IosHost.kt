package com.comfypilot.core.ios

import com.comfypilot.core.host.SessionHost
import com.comfypilot.core.host.restoredTasksFromJson
import com.comfypilot.core.host.sessionConfigFromJson
import com.comfypilot.core.host.sessionWorkflowJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * iOS 侧的唯一入口。
 *
 * 只暴露四个方法，Swift 不直接接触 [SessionHost]——它的构造参数带 Kotlin 默认值，
 * 而默认参数不会导出到 Objective-C，硬调会要求把内部调优项全写一遍。
 *
 * 线程约束：所有方法必须在同一个线程（主线程）调用。底层 [SessionHost] 用的
 * PumpDispatcher 是「宿主驱动、单线程、不加锁」的设计，跨线程会直接破坏状态机。
 * Swift 侧因此把所有 URLSession 回调都先切回主线程再 deliver。
 *
 * 生命周期：start() 一次 → 宿主按固定节奏调 pump(nowMs) → 把 IO 结果 deliver 回来。
 * 没有 pump 就没有进展，这正是 iOS 进后台被冻结时想要的行为。
 */
class IosHost(configJson: String, tasksJson: String) {

    private val host: SessionHost = SessionHost(
        config = sessionConfigFromJson(configJson),
        initialTasks = restoredTasksFromJson(tasksJson),
    )

    fun start() {
        host.start()
    }

    fun shutdown() {
        host.shutdown()
    }

    /** 推进到 [nowMs]（Unix 毫秒），返回一帧 JSON：{revision, commands, state, nextDelayMs}。 */
    fun pump(nowMs: Long): String = host.pump(nowMs)

    /** 宿主执行完 IO 后把结果送回来；返回 false 表示这条消息无法识别。 */
    fun deliver(raw: String): Boolean = host.deliver(raw)

    /**
     * 按需拉取工作流视图：参数模型、节点开关、图分组、问题清单。
     *
     * 状态帧里只有轻量元数据，几百 KB 的工作流原文和字段模型走这条同步调用，
     * 免得每 250ms 的帧都被一份大工作流拖慢。请求形如：
     * `{"op":"view","overrides":{"3":{"seed":42}}}`，
     * 或 `{"op":"prompt","overrides":{...}}` 拿可提交的 prompt 图。
     *
     * 和鸿蒙的 `cp_session_workflow` 是同一个实现，只是换了调用方式。
     */
    fun workflowView(requestJson: String): String = sessionWorkflowJson(host.state, requestJson)

    /**
     * 按需取最新一帧采样预览，图片字节以 base64 放在返回的 JSON 里。
     *
     * 状态帧只带元数据（promptId / nodeId / receivedAtMs / bytes / animation*），
     * 图片本身走这条同步调用——和鸿蒙的 `cp_session_preview` 是同一份数据，
     * 免得每 250ms 的帧都被几十 KB 的 base64 拖慢。
     *
     * 返回 `{"ok":true,"image":"...","promptId":...}` 或 `{"ok":false,"message":"..."}`。
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun preview(): String {
        val frame = host.state.preview
        if (frame == null || frame.image.isEmpty()) {
            return """{"ok":false,"message":"没有预览帧"}"""
        }
        return buildString {
            append("""{"ok":true,"promptId":""")
            append(jsonString(frame.promptId ?: ""))
            append(""","nodeId":""")
            append(jsonString(frame.nodeId ?: ""))
            append(""","receivedAtMs":""")
            append(frame.receivedAtMs)
            append(""","bytes":""")
            append(frame.image.size)
            append(""","image":""")
            append(jsonString(Base64.encode(frame.image)))
            append("\"}")
        }
    }

    /** 极简 JSON 字符串转义：预览元数据只有 id 与 base64，够用。 */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> {
                    // Kotlin/Native 没有 String.format，手写四位十六进制。
                    val code = ch.code
                    append("\\u")
                    for (shift in intArrayOf(12, 8, 4, 0)) {
                        append("0123456789abcdef"[(code shr shift) and 0xF])
                    }
                }
                else -> append(ch)
            }
        }
        append('"')
    }
}
