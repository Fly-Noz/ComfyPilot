package com.comfypilot.core.host

import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 重启后带着旧任务启动：连接建立时必须先对账，而不是重投。
 * 这些断言防的是最糟的一种 bug——用户以为自己只跑了一个任务，机器上却排了两个。
 */
class SessionHostRestoreTest {

    private fun persisted(
        id: String,
        state: TaskState,
        promptId: String? = id,
        updatedAtMs: Long = 1_000,
    ) = PromptTask(
        id = id,
        promptId = promptId,
        displayName = null,
        state = state,
        createdAtMs = 900,
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun `restored running task is reconciled instead of resubmitted`() {
        val driver = HostDriver(initialTasks = listOf(persisted("p1", TaskState.Running)))
        driver.host.start()
        driver.connect()

        assertEquals("Recovering", driver.stateString("phase"), "旧任务必须先对账才能算连接完成")
        val history = assertNotNull(driver.httpCommand("/history/p1"))
        val queue = assertNotNull(driver.httpCommand("/queue"))
        assertEquals(0, driver.submitCount(), "恢复绝不允许自动重投")

        driver.deliver("""{"t":"http.result","id":${(queue["id"] as JsonPrimitive).content},"status":200,"body":"{\"queue_running\":[],\"queue_pending\":[]}"}""")
        driver.deliver(
            """{"t":"http.result","id":${(history["id"] as JsonPrimitive).content},"status":200,"body":"{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\",\"type\":\"output\"}]}},\"status\":{\"status_str\":\"success\",\"completed\":true,\"messages\":[]}}}"}""",
        )

        assertEquals("Completed", driver.taskState("p1"))
        assertEquals("Connected", driver.stateString("phase"))
        assertEquals(0, driver.submitCount())
    }

    @Test
    fun `restored task missing from queue and history becomes unknown`() {
        val driver = HostDriver(initialTasks = listOf(persisted("p1", TaskState.Queued)))
        driver.host.start()
        driver.connect()

        val history = assertNotNull(driver.httpCommand("/history/p1"))
        val queue = assertNotNull(driver.httpCommand("/queue"))
        driver.deliver("""{"t":"http.result","id":${(queue["id"] as JsonPrimitive).content},"status":200,"body":"{\"queue_running\":[],\"queue_pending\":[]}"}""")
        driver.deliver("""{"t":"http.result","id":${(history["id"] as JsonPrimitive).content},"status":200,"body":"{}"}""")

        assertEquals("Unknown", driver.taskState("p1"))
        assertTrue("task_lost" in driver.notices(), "任务丢了要说出来，实际通知：${driver.notices()}")
        assertEquals(0, driver.submitCount(), "不知道结果也不能重投")
    }

    @Test
    fun `restored submitting task becomes unknown without touching the network`() {
        val driver = HostDriver(initialTasks = listOf(persisted("draft-3", TaskState.Submitting, promptId = null)))

        // 不需要 connect，也不该产生任何请求：没有 promptId 就无从对账。
        assertEquals("Unknown", driver.taskState("draft-3"))
        assertEquals(0, driver.allCommands().count { (it["t"] as JsonPrimitive).content == "http" })
    }

    @Test
    fun `first submit after restore does not reuse a draft id`() {
        val driver = HostDriver(initialTasks = listOf(persisted("draft-3", TaskState.Submitting, promptId = null)))
        driver.host.start()
        driver.connect()

        val submitId = HostDriver.submit(driver)

        assertEquals("Submitting", driver.taskState("draft-4"), "新草稿编号要跳过恢复出来的 draft-3")
        assertEquals("Unknown", driver.taskState("draft-3"), "旧任务不能被覆盖")
        assertNotNull(driver.command("http"))
        HostDriver.acceptSubmit(driver, submitId)
        assertEquals("Queued", driver.taskState("p1"))
        assertEquals(2, (driver.state()["tasks"] as kotlinx.serialization.json.JsonArray).size)
    }
}
