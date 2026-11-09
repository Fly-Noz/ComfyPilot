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
     * 按需取最新一帧采样预览，直接返回 base64（没有帧时返回空串）。
     *
     * 不再套一层 JSON：Swift 侧少一次解析，失败点也就只剩 base64 解码和图片解码两处。
     * 和鸿蒙的 `cp_session_preview` 是同一份数据，只是那边包在 JSON 里方便 C ABI。
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun previewBase64(): String {
        val frame = host.state.preview ?: return ""
        if (frame.image.isEmpty()) return ""
        return Base64.encode(frame.image)
    }
}
