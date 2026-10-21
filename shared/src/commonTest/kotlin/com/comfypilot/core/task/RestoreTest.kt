package com.comfypilot.core.task

import com.comfypilot.core.api.TaskOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 重启恢复规则：只恢复历史，不恢复控制权。
 * 这里断言的重点是"不许自作主张"——尤其不许把没提交成功的任务当成提交过。
 */
class RestoreTest {

    private fun task(
        id: String,
        state: TaskState,
        createdAt: Long = 0,
        updatedAt: Long = createdAt,
        promptId: String? = null,
    ) = PromptTask(
        id = id,
        promptId = promptId,
        displayName = null,
        state = state,
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
    )

    @Test
    fun `submitting without a prompt id comes back unknown`() {
        val restored = restoreTasks(listOf(task("draft-1", TaskState.Submitting))).tasks.single()

        val state = assertIs<TaskState.Unknown>(restored.state)
        assertEquals(RESTART_UNKNOWN_REASON, state.reason)
        assertEquals(RESTART_UNKNOWN_REASON, restored.error)
        assertEquals(null, restored.promptId)
    }

    @Test
    fun `unfinished tasks keep their state for reconciliation`() {
        val restored = restoreTasks(
            listOf(
                task("p1", TaskState.Running, promptId = "p1").copy(
                    reconcileAttempts = 2,
                    silenceBudgetMs = 240_000,
                    progress = TaskProgress(5.0, 10.0, "3"),
                ),
                task("p2", TaskState.Queued, promptId = "p2"),
            ),
        ).tasks

        assertIs<TaskState.Running>(restored.first { it.id == "p1" }.state)
        assertIs<TaskState.Queued>(restored.first { it.id == "p2" }.state)
        // 进度是现场信息，保留；预算和次数属于上一个进程，重新算。
        assertEquals(TaskProgress(5.0, 10.0, "3"), restored.first { it.id == "p1" }.progress)
        assertEquals(0, restored.first { it.id == "p1" }.reconcileAttempts)
        assertEquals(null, restored.first { it.id == "p1" }.silenceBudgetMs)
        assertTrue(restored.all { it.isActive })
    }

    @Test
    fun `finished tasks keep their result`() {
        val output = TaskOutput("9", "images", "a.png", "", "output")
        val finished = task("p1", TaskState.Completed, promptId = "p1").copy(outputs = listOf(output))
        val failed = task("p2", TaskState.Failed("CUDA OOM"), promptId = "p2").copy(error = "CUDA OOM")

        val restored = restoreTasks(listOf(finished, failed)).tasks

        assertEquals(listOf(output), restored.first { it.id == "p1" }.outputs)
        assertIs<TaskState.Completed>(restored.first { it.id == "p1" }.state)
        assertEquals("CUDA OOM", restored.first { it.id == "p2" }.error)
    }

    @Test
    fun `only the newest tasks are restored`() {
        val many = (1..60).map { index ->
            task("t$index", TaskState.Completed, createdAt = index.toLong(), updatedAt = index.toLong())
        }

        val restored = restoreTasks(many).tasks

        assertEquals(DEFAULT_RESTORE_LIMIT, restored.size)
        assertEquals("t11", restored.first().id)
        assertEquals("t60", restored.last().id)
        // 恢复后仍按创建时间升序，UI 直接能用。
        assertEquals(restored.sortedBy { it.createdAtMs }, restored)
    }

    @Test
    fun `duplicates keep the freshest copy`() {
        val restored = restoreTasks(
            listOf(
                task("p1", TaskState.Running, updatedAt = 10),
                task("p1", TaskState.Completed, updatedAt = 99),
            ),
        ).tasks

        assertEquals(1, restored.size)
        assertIs<TaskState.Completed>(restored.single().state)
    }

    @Test
    fun `draft numbering continues after restored drafts`() {
        val drafts = listOf(
            task("draft-2", TaskState.Completed),
            task("draft-7", TaskState.Completed),
            task("p1", TaskState.Completed),
        )

        assertEquals(8L, restoreTasks(drafts).nextDraftId)
        assertEquals(1L, restoreTasks(emptyList()).nextDraftId)
        assertEquals(1L, restoreTasks(listOf(task("draft-x", TaskState.Completed))).nextDraftId)
    }

    @Test
    fun `blank ids are dropped`() {
        val restored = restoreTasks(
            listOf(task("", TaskState.Completed), task("p1", TaskState.Completed)),
        ).tasks

        assertEquals(listOf("p1"), restored.map { it.id })
    }
}
