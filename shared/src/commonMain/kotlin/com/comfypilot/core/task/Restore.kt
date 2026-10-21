package com.comfypilot.core.task

/**
 * 进程重启后的任务恢复规则。
 *
 * 持久化只恢复"历史与线索"，不恢复"控制权"：
 *
 * - 恢复出来的任务**永远不会被自动重投**；提交是用户动作，不能由重启触发；
 * - `Submitting` 没有 `promptId`，无法对账，直接记为 [TaskState.Unknown]；
 * - `Queued` / `Running` 保留原状态，等下一次连上 WebSocket 时按 `/history`、`/queue`
 *   核对——核对不上的结果是 `Unknown`，不是"重跑一遍"；
 * - 已经结束的任务原样保留，只是历史记录。
 *
 * 这几条是"不能假装知道"的直接后果：我们不在现场，就不能替 ComfyUI 下结论。
 */
data class RestoredTasks(
    /** 按创建时间升序，最多 [DEFAULT_RESTORE_LIMIT] 条。 */
    val tasks: List<PromptTask>,
    /** 新草稿编号，保证重启后不会和恢复出来的 `draft-N` 撞车。 */
    val nextDraftId: Long,
)

const val DEFAULT_RESTORE_LIMIT = 50

const val RESTART_UNKNOWN_REASON = "应用重启，提交结果未知"

private const val DRAFT_PREFIX = "draft-"

fun restoreTasks(
    persisted: List<PromptTask>,
    limit: Int = DEFAULT_RESTORE_LIMIT,
): RestoredTasks {
    val kept = persisted
        .filter { it.id.isNotBlank() }
        .sortedByDescending { it.updatedAtMs }
        .distinctBy { it.id }
        .take(limit.coerceAtLeast(1))
        .map { it.recovered() }
        .sortedBy { it.createdAtMs }
    return RestoredTasks(tasks = kept, nextDraftId = nextDraftIdAfter(kept))
}

private fun PromptTask.recovered(): PromptTask = when (state) {
    is TaskState.Submitting -> copy(
        state = TaskState.Unknown(RESTART_UNKNOWN_REASON),
        error = RESTART_UNKNOWN_REASON,
        // 没有 promptId，本来也无法对账。
    )

    // 保留"可能还在跑"的线索，但把上一次进程的对账计数清掉：
    // 新进程的预算要按自己的监控时间重新算。
    is TaskState.Queued, is TaskState.Running -> copy(
        reconcileAttempts = 0,
        silenceBudgetMs = null,
    )

    else -> this
}

private fun nextDraftIdAfter(tasks: List<PromptTask>): Long =
    (tasks.maxOfOrNull { task ->
        task.id.removePrefix(DRAFT_PREFIX)
            .toLongOrNull()
            ?.takeIf { task.id.startsWith(DRAFT_PREFIX) && task.id.length > DRAFT_PREFIX.length }
            ?: 0L
    } ?: 0L) + 1L
