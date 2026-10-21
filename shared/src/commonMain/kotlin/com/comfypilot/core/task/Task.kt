package com.comfypilot.core.task

import com.comfypilot.core.api.TaskOutput

typealias TaskId = String

/**
 * 提交 / 排队 / 执行中 / 完成 / 失败 / 取消 / 状态未知。
 * 注意：状态未知是显式状态，不是"既非成功也非失败"的默认分支。
 */
sealed interface TaskState {
    data object Submitting : TaskState
    data object Queued : TaskState
    data object Running : TaskState
    data object Completed : TaskState
    data class Failed(val message: String?) : TaskState
    data object Cancelled : TaskState
    data class Unknown(val reason: String?) : TaskState
}

data class TaskProgress(
    val value: Double,
    val max: Double,
    val node: String? = null,
) {
    val percent: Int
        get() = if (max <= 0.0) 0 else ((value / max) * 100.0).toInt().coerceIn(0, 100)
}

data class PromptTask(
    val id: TaskId,
    val promptId: String?,
    val displayName: String?,
    val state: TaskState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val progress: TaskProgress? = null,
    val outputs: List<TaskOutput> = emptyList(),
    val lastProgressAtMs: Long? = null,
    /** 进度静默预算；看门狗触发重连并确认任务仍在跑时会翻倍。 */
    val silenceBudgetMs: Long? = null,
    val reconcileAttempts: Int = 0,
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = state is TaskState.Completed || state is TaskState.Failed ||
            state is TaskState.Cancelled || state is TaskState.Unknown

    val isActive: Boolean get() = !isTerminal

    val needsReconcile: Boolean get() = promptId != null && !isTerminal
}

/** 落库/持久化的唯一入口。OHOS 侧由 ArkTS RDB 实现，Android 侧先用内存或 JSON 快照。 */
interface TaskStore {
    suspend fun load(): List<PromptTask>
    suspend fun save(task: PromptTask)
    suspend fun remove(id: TaskId)
}

class InMemoryTaskStore(initial: List<PromptTask> = emptyList()) : TaskStore {
    private val items = initial.associateBy { it.id }.toMutableMap()

    override suspend fun load(): List<PromptTask> = items.values.toList()

    override suspend fun save(task: PromptTask) {
        items[task.id] = task
    }

    override suspend fun remove(id: TaskId) {
        items.remove(id)
    }
}
