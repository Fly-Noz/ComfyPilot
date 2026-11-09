package com.comfypilot.core.ios

import com.comfypilot.core.host.SessionHost
import com.comfypilot.core.host.restoredTasksFromJson
import com.comfypilot.core.host.sessionConfigFromJson

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
}
