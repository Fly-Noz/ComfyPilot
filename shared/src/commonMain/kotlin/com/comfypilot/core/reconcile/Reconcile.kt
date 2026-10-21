package com.comfypilot.core.reconcile

import com.comfypilot.core.api.HistoryEntry
import com.comfypilot.core.api.QueueSnapshot
import com.comfypilot.core.api.TaskOutput

/**
 * /history 对账的输入。三分类的第一层：读不读得到。
 */
sealed interface HistoryProbe {
    data class Readable(val entry: HistoryEntry?) : HistoryProbe

    data class Unreadable(val reason: String) : HistoryProbe
}

data class ReconcileBudget(
    val maxAttempts: Int,
    val windowMs: Long,
    val retryBaseMs: Long,
)

sealed interface ReconcileOutcome {
    data class Completed(val outputs: List<TaskOutput>) : ReconcileOutcome

    data class Failed(val message: String?) : ReconcileOutcome

    data object Interrupted : ReconcileOutcome

    /** 仍在服务端队列里。 */
    data class StillActive(val running: Boolean) : ReconcileOutcome

    /** 既不在 history，也不在 queue —— 多半是 ComfyUI 重启或队列被清。 */
    data object Lost : ReconcileOutcome

    data class Retry(val delayMs: Long, val reason: String) : ReconcileOutcome

    data class GiveUp(val reason: String) : ReconcileOutcome
}

object ReconcilePolicy {
    /**
     * 三分类 + 一个必须补的第四类：
     *  1. 不可读               -> 预算内退避重试
     *  2. 可读且含输出         -> 完成
     *  3. 可读且 error         -> 立即失败，零重试
     *  4. 可读但无输出也无 error -> 查 /queue；在队列 = 进行中，不在 = 丢失
     */
    fun decide(
        probe: HistoryProbe,
        queue: QueueSnapshot?,
        promptId: String,
        attempts: Int,
        elapsedMs: Long,
        budget: ReconcileBudget,
    ): ReconcileOutcome = when (probe) {
        is HistoryProbe.Unreadable -> {
            val exhausted = attempts >= budget.maxAttempts || elapsedMs >= budget.windowMs
            if (exhausted) {
                ReconcileOutcome.GiveUp(probe.reason)
            } else {
                ReconcileOutcome.Retry(retryDelay(attempts, budget), probe.reason)
            }
        }

        is HistoryProbe.Readable -> classify(probe.entry, queue, promptId)
    }

    private fun classify(entry: HistoryEntry?, queue: QueueSnapshot?, promptId: String): ReconcileOutcome {
        if (entry == null) return queueOutcome(queue, promptId)
        if (entry.isError) {
            return ReconcileOutcome.Failed(entry.errorMessages.joinToString("；").ifBlank { "执行失败" })
        }
        if (entry.isInterrupted) return ReconcileOutcome.Interrupted
        if (entry.isSuccess) return ReconcileOutcome.Completed(entry.extractOutputs())
        // 可读、无输出、无 error：必须借助 /queue 才能区分"还在跑"与"丢了"
        return queueOutcome(queue, promptId)
    }

    private fun queueOutcome(queue: QueueSnapshot?, promptId: String): ReconcileOutcome {
        if (queue == null) return ReconcileOutcome.Retry(delayMs = 0, reason = "等待 /queue 快照")
        if (queue.contains(promptId)) {
            return ReconcileOutcome.StillActive(running = queue.running.any { it.promptId == promptId })
        }
        return ReconcileOutcome.Lost
    }

    private fun retryDelay(attempts: Int, budget: ReconcileBudget): Long {
        var delay = budget.retryBaseMs
        repeat(attempts.coerceAtLeast(0)) { delay *= 2 }
        return delay.coerceAtMost(budget.windowMs.coerceAtLeast(budget.retryBaseMs))
    }
}
