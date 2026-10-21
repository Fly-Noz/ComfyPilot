package com.comfypilot.core.host

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * 由宿主（目前是 ArkTS）用固定节奏驱动的单线程调度器。
 *
 * 为什么不用 Dispatchers.Default：Kotlin/Native 上跨线程回调要和 UI 线程抢锁，
 * 而这里所有工作都只是解析 JSON、跑状态机，主线程序列化更简单也更好复现。
 * 代价是"Kotlin 不主动跑"：没有 [pump] 就没有进展，超时/重连全部由宿主的时间推动。
 *
 * 线程约束：只能在同一个线程上调用，不做任何同步。
 */
@OptIn(InternalCoroutinesApi::class)
class PumpDispatcher(private val maxStepsPerPump: Int = 10_000) : CoroutineDispatcher(), Delay {

    private val ready = ArrayDeque<Runnable>()
    private val timers = ArrayList<Timer>()
    private var nowMs = 0L
    private var sequence = 0L

    private class Timer(val dueAtMs: Long, val sequence: Long, val block: Runnable)

    /** 运行所有到期任务，返回本轮执行的步数。返回 0 表示无事可做。 */
    fun pump(nowMs: Long): Int {
        this.nowMs = nowMs
        var steps = 0
        while (steps < maxStepsPerPump) {
            val due = timers
                .filter { it.dueAtMs <= nowMs }
                .minWithOrNull(compareBy({ it.dueAtMs }, { it.sequence }))
            if (due != null) {
                timers.remove(due)
                due.block.run()
                steps++
                continue
            }
            val task = ready.removeFirstOrNull() ?: break
            task.run()
            steps++
        }
        return steps
    }

    /** 距离下一次定时器到期还有多久；没有定时器时返回 null。宿主据此决定下次 pump 的时间。 */
    fun nextDelayMs(nowMs: Long): Long? =
        timers.minOfOrNull { it.dueAtMs }?.let { (it - nowMs).coerceAtLeast(0) }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ready.addLast(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        timers += Timer(
            dueAtMs = nowMs + timeMillis.coerceAtLeast(0),
            sequence = sequence++,
            block = Runnable { continuation.resume(Unit) },
        )
    }
}
