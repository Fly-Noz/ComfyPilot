package com.comfypilot.core.host

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 后台冻结语义。
 *
 * 产品文案说的是"后台可能暂停监控，回来自动同步"。这里把这句话拆成可验证的断言：
 * 冻结期间没有在监控，就不能把这段时间算成 ComfyUI 的静默，也不能用来耗尽对账预算。
 * 回到前台只要照常 pump，状态机自己会把对话补上。
 */
class SessionHostFreezeTest {

    /** 连接 + 提交 + 进入 Running，作为冻结前的现场。 */
    private fun runningTask(
        progressSilenceMs: Long = 60_000,
        progressSilenceMaxMs: Long = 120_000,
    ): HostDriver {
        val driver = HostDriver(
            testSessionConfig(
                progressSilenceMs = progressSilenceMs,
                progressSilenceMaxMs = progressSilenceMaxMs,
            ),
        )
        driver.host.start()
        val wsId = driver.connect()
        val submitId = HostDriver.submit(driver)
        HostDriver.acceptSubmit(driver, submitId)
        driver.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"progress\",\"data\":{\"value\":5,\"max\":10,\"prompt_id\":\"p1\",\"node\":\"3\"}}"}""")
        assertEquals("Running", driver.taskState("p1"))
        return driver
    }

    @Test
    fun `freezing the app does not count as ComfyUI going silent`() {
        val driver = runningTask(progressSilenceMs = 60_000)

        // 系统把页面冻结十分钟后恢复：第一个 pump 的时间戳跳过 600 秒。
        driver.pump(10 * 60_000)

        assertEquals("Connected", driver.stateString("phase"), "冻结不该把会话踢进重连")
        assertFalse(
            driver.notices().contains("progress_timeout"),
            "冻结期间没有监控，不能报进度超时：${driver.notices()}",
        )
        assertNull(driver.command("ws.close"), "冻结不该主动关闭 WebSocket")
        assertEquals("Running", driver.taskState("p1"))
    }

    @Test
    fun `monitored silence still trips the watchdog`() {
        // 上限等于初始预算：不再翻倍，直接触发重连
        val driver = runningTask(progressSilenceMs = 10_000, progressSilenceMaxMs = 10_000)

        // 正常在监控：每一秒 pump 一次，累计超过静默预算。
        repeat(15) { driver.pump(1_000) }

        assertTrue(
            driver.notices().contains("progress_timeout"),
            "确实在监控的静默必须触发看门狗，实际通知：${driver.notices()}",
        )
    }

    @Test
    fun `freeze inside the reconcile window does not give up on the task`() {
        val driver = runningTask()
        val wsId = 1L

        // 进入 Recovering：WebSocket 说执行完了，等 /history 回来。
        driver.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"p1\"}}"}""")
        val history = assertNotNull(driver.command("http"), "应当发出 /history 请求")
        val historyId = (history["id"] as JsonPrimitive).content

        // 对账预算只有 10 秒；冻结一小时后回来，不能因此判定"对账失败"。
        driver.pump(60 * 60_000)

        assertEquals("Recovering", driver.stateString("phase"))
        assertFalse(driver.notices().contains("reconcile_gave_up"), "冻结不能被当成对账超时：${driver.notices()}")
        assertEquals("Running", driver.taskState("p1"), "冻结不该把任务改成 Unknown")

        // 回来后的结果照常生效。
        driver.deliver(
            """{"t":"http.result","id":$historyId,"status":200,"body":"{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\",\"type\":\"output\"}]}},\"status\":{\"status_str\":\"success\",\"completed\":true,\"messages\":[]}}}"}""",
        )
        assertEquals("Completed", driver.taskState("p1"))
    }

    @Test
    fun `freeze during backoff does not fire a burst of reconnects`() {
        val driver = HostDriver(
            testSessionConfig(tickIntervalMs = 500),
        )
        driver.host.start()
        val wsId = driver.connect()

        // 连接断开 → 进入退避，下一次尝试在 1 秒后。
        driver.deliver("""{"t":"ws.closed","id":$wsId,"code":1006,"reason":"network lost"}""")
        assertEquals("Reconnecting", driver.stateString("phase"))
        assertNull(driver.command("ws.open"), "退避期间不应立刻重连")

        // 冻结半小时后恢复：逻辑时间没有前进，所以仍然在退避里，不能一口气重连。
        driver.pump(30 * 60_000)
        assertEquals("Reconnecting", driver.stateString("phase"))
        assertNull(driver.command("ws.open"), "冻结结束后不能跳过退避立刻重连")

        // 正常再等一秒，才重连一次。
        driver.pump(1_000)
        assertNotNull(driver.command("ws.open"), "退避到期后应当重连")
    }
}
