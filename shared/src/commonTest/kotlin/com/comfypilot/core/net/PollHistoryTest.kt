package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.testConfig
import com.comfypilot.core.wifi
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 兜底轮询 + 节点进度。
 *
 * 背景：视频工作流的采样节点是自定义节点，只发 `executing` 不发 `progress`；
 * 应用切后台时 socket 还会被系统反复掐断。这两条决定了不能只靠 socket 判断"在跑"。
 */
class PollHistoryTest {

    /** 连接 + 提交 + 进入 Running。 */
    private fun running(): Pair<Harness, ConnectionLease> {
        val harness = Harness(testConfig.copy(historyPollMs = 20_000))
        val lease = harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        harness.acceptSubmit(draft, "p1")
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        return harness to lease
    }

    @Test
    fun executingEventsAreRecordedAsNodeProgress() {
        val (harness, lease) = running()

        harness.ws(lease, """{"type":"executing","data":{"node":"7","prompt_id":"p1"}}""")
        harness.ws(lease, """{"type":"executing","data":{"node":"9","prompt_id":"p1"}}""")
        harness.ws(lease, """{"type":"executing","data":{"node":"9","prompt_id":"p1"}}""")

        val task = harness.state.tasks.getValue("p1")
        assertEquals(setOf("7", "9"), task.executedNodes)
        assertEquals(TaskState.Running, task.state)
    }

    @Test
    fun runningTaskPollsHistoryAfterInterval() {
        val (harness, lease) = running()

        // 还没到间隔：不应该发请求
        harness.advance(20_000L - 1)
        harness.send(SessionInput.Tick(harness.now))
        assertTrue(
            harness.effects.filterIsInstance<SessionEffect.SendHttp>()
                .none { it.tag is RequestTag.PollHistory },
            "未到间隔不应轮询",
        )

        harness.advance(2)
        harness.send(SessionInput.Tick(harness.now))
        val poll = harness.effects.filterIsInstance<SessionEffect.SendHttp>()
            .firstOrNull { it.tag is RequestTag.PollHistory }
        assertIs<SessionEffect.SendHttp>(poll, "到间隔后应当发 /history 兜底查询")
        assertEquals(RequestTag.PollHistory("p1"), poll.tag)
    }

    @Test
    fun pollResultCompletesTaskWithoutWebSocketEvents() {
        val (harness, _) = running()

        harness.advance(20_000L + 1)
        harness.send(SessionInput.Tick(harness.now))
        harness.httpOk(
            RequestTag.PollHistory("p1"),
            """{"p1":{"status":{"status_str":"success","completed":true},
               "outputs":{"9":{"images":[{"filename":"done.mp4","subfolder":"","type":"output"}]}}}}""",
        )

        val task = harness.state.tasks.getValue("p1")
        assertEquals(TaskState.Completed, task.state)
        assertEquals(listOf("done.mp4"), task.outputs.map { it.filename })
    }

    @Test
    fun pollResultWithNoOutcomeKeepsTaskRunning() {
        val (harness, _) = running()

        harness.advance(20_000L + 1)
        harness.send(SessionInput.Tick(harness.now))
        // 可读但没输出、没 error：说明还在跑，不能误判完成/失败
        harness.httpOk(RequestTag.PollHistory("p1"), """{"p1":{"status":{"completed":false}}}""")

        assertEquals(TaskState.Running, harness.state.tasks.getValue("p1").state)
    }

    @Test
    fun lastPreviewSurvivesReconnect() {
        val (harness, lease) = running()
        harness.send(SessionInput.SocketBinary(lease, previewFrame(), harness.now))
        assertTrue(harness.state.preview != null, "收到预览帧后应当有画面")

        // socket 断掉并重连（对应切后台被系统掐断）
        harness.send(SessionInput.SocketFailed(lease, "Software caused connection abort", harness.now))
        harness.advance(1_001)
        harness.send(SessionInput.Tick(harness.now))
        val nextLease = (harness.state.phase as SessionPhase.Connecting).lease
        harness.send(SessionInput.SocketOpened(nextLease, harness.now))

        assertTrue(
            harness.state.preview != null,
            "重连不应清空最后一帧：否则界面会从有图退回等待画面",
        )
    }

    /** type=1 的预览帧：4 字节 type + JPEG 头。 */
    private fun previewFrame(): ByteArray = byteArrayOf(
        0, 0, 0, 1,
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    )
}
