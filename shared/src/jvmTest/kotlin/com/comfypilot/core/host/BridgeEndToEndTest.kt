package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.task.TaskState
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端到端：真 HTTP、真 WebSocket、真 JSON，对端是 `tools/mock-comfyui`。
 *
 * 覆盖的就是阶段一那四个场景，只不过宿主换成了 JVM 版执行器（见 [BridgeHarness]）。
 * 它证明"桥协议本身能跑"，不证明 ArkTS 那层能跑——后者仍然只有真机说话。
 *
 * 需要本机有 node 和 `tools/mock-comfyui/node_modules`；缺了会 skip 而不是失败。
 */
class BridgeEndToEndTest {

    companion object {
        private var server: MockComfyUi? = null
        private var unavailable: String? = null

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }
                .getOrDefault(false)
            if (!node) {
                unavailable = "需要 node"
                return
            }
            server = runCatching { MockComfyUi.start() }.getOrElse { error ->
                unavailable = error.message
                null
            }
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server?.close()
        }
    }

    private val mock: MockComfyUi get() = requireNotNull(server) { "mock ComfyUI 未启动" }

    private fun harness(progressSilenceMs: Long = 3_000, scenario: String): BridgeHarness {
        val running = server
        assumeTrue(unavailable ?: "需要 tools/mock-comfyui 依赖", running != null)
        requireNotNull(running).reset()
        running.scenario(scenario)
        return BridgeHarness(BridgeHarness.config(running.baseUrl, progressSilenceMs))
    }

    private fun BridgeHarness.onlyTask() = host.state.tasks.values.singleOrNull()

    @Test
    fun `normal run completes and persists the result`() {
        harness(scenario = "normal").use { driver ->
            driver.connect()
            driver.runUntil("连上 ComfyUI") { driver.host.state.phase is SessionPhase.Connected }

            driver.submit()
            driver.runUntil("任务完成") { driver.onlyTask()?.state is TaskState.Completed }

            val task = assertNotNull(driver.onlyTask())
            assertEquals(1, task.outputs.size, "完成的任务必须带产物")
            assertEquals(1, mock.hits()["prompt"], "只应提交一次")
            assertTrue(driver.notices.contains("connected"), "通知里应当有连接成功：${driver.notices}")

            // persist 命令带的是完整快照，宿主可以直接落库。
            val snapshot = assertNotNull(driver.persisted[task.id], "完成的任务应当被持久化")
            assertEquals("Completed", snapshot.stringOrNull("state"))
            val outputs = snapshot["outputs"]!!.jsonArray
            assertEquals(1, outputs.size)
            assertEquals(task.outputs.single().filename, outputs.single().jsonObject.stringOrNull("filename"))
            // 草稿已经转正，宿主那边不能再留着 draft-*：重启会把它变成幽灵 Unknown。
            assertTrue(driver.persisted.keys.none { it.startsWith("draft-") }, "持久化里不该残留草稿：${driver.persisted.keys}")
        }
    }

    @Test
    fun `history error fails at once and is never retried`() {
        harness(scenario = "history_error").use { driver ->
            driver.connect()
            driver.runUntil("连上 ComfyUI") { driver.host.state.phase is SessionPhase.Connected }

            driver.submit()
            driver.runUntil("任务失败") { driver.onlyTask()?.state is TaskState.Failed }

            val failed = assertIs<TaskState.Failed>(assertNotNull(driver.onlyTask()).state)
            assertTrue(failed.message.orEmpty().contains("history-only error"), "错误信息来自 /history：${failed.message}")
            assertEquals(1, mock.hits()["history"], "可读的错误响应不允许重试")
            assertEquals(1, mock.hits()["prompt"], "失败也不能重投")
        }
    }

    @Test
    fun `silent server trips the watchdog and the task survives reconciliation`() {
        harness(progressSilenceMs = 800, scenario = "silent").use { driver ->
            driver.connect()
            driver.runUntil("连上 ComfyUI") { driver.host.state.phase is SessionPhase.Connected }

            driver.submit()
            driver.runUntil("开始执行") { driver.onlyTask()?.state is TaskState.Running }

            driver.runUntil("看门狗触发") { "progress_timeout" in driver.notices }

            driver.runUntil("对账后仍在跑") {
                driver.host.state.phase is SessionPhase.Connected &&
                    driver.onlyTask()?.state is TaskState.Running &&
                    driver.onlyTask()?.silenceBudgetMs == 1_600L
            }

            assertTrue(mock.hits()["queue"].orZero() >= 1, "重连后必须核对 /queue")
            assertEquals(1, mock.hits()["prompt"], "静默不能导致重投")
        }
    }

    @Test
    fun `task lost by a restarted server becomes unknown and is not resubmitted`() {
        harness(scenario = "lost").use { driver ->
            driver.connect()
            driver.runUntil("连上 ComfyUI") { driver.host.state.phase is SessionPhase.Connected }

            driver.submit()
            driver.runUntil("提交被接受") { driver.onlyTask()?.promptId != null }

            // 等 mock 把任务从 /queue 里丢掉，且不写 /history——等价于 ComfyUI 重启。
            driver.settle(500)
            assertEquals(0, mock.get("/mock/state")["pending"]!!.jsonPrimitive.int)

            driver.switchNetwork("cellular", "rmnet0")
            driver.runUntil("对账后判为 Unknown") { driver.onlyTask()?.state is TaskState.Unknown }

            assertTrue("task_lost" in driver.notices, "任务丢了要明确说出来：${driver.notices}")
            assertEquals(1, mock.hits()["prompt"], "不知道结果也绝不允许重投")
            assertEquals(2L, driver.host.state.generation)
        }
    }

    private fun Int?.orZero(): Int = this ?: 0
}
