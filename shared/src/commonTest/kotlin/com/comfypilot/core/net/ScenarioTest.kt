package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.testConfig
import com.comfypilot.core.cellular
import com.comfypilot.core.offline
import com.comfypilot.core.wifi
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 四个必测场景的端到端（reducer 层，虚拟时钟，无真实网络）。
 */
class ScenarioTest {
    @Test
    fun scenario1_networkLossThenRestore() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        assertEquals(1, harness.state.generation)

        harness.send(SessionInput.NetworkChanged(offline, harness.now))
        assertIs<SessionPhase.Reconnecting>(harness.state.phase)

        harness.send(SessionInput.NetworkChanged(wifi, harness.now))
        val lease = (harness.state.phase as SessionPhase.Connecting).lease
        harness.send(SessionInput.SocketOpened(lease, harness.now))

        assertIs<SessionPhase.Connected>(harness.state.phase)
        assertEquals(1, harness.state.generation)
    }

    @Test
    fun scenario2_wifiToCellularKeepsTaskButChangesGeneration() {
        val harness = Harness()
        val first = harness.startWithNetwork(wifi)
        harness.ws(first, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        assertEquals(1, harness.state.generation)

        harness.send(SessionInput.NetworkChanged(cellular, harness.now))
        assertEquals(2, harness.state.generation)

        // 旧 socket 上的完成事件不得生效
        harness.ws(first, """{"type":"progress","data":{"prompt_id":"p1","node":"9","value":99,"max":100}}""")
        assertEquals(0, harness.state.tasks.getValue("p1").progress?.value?.toInt() ?: 0)

        val lease = (harness.state.phase as SessionPhase.Connecting).lease
        harness.send(SessionInput.SocketOpened(lease, harness.now))
        assertIs<SessionPhase.Recovering>(harness.state.phase)

        harness.httpOk(RequestTag.Queue, """{"queue_running":[[0,"p1",{},{},[]]],"queue_pending":[]}""")
        harness.httpOk(RequestTag.History("p1"), "{}")

        assertEquals("p1", harness.state.tasks.getValue("p1").promptId)
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    @Test
    fun scenario3_comfyRestartLeavesTaskUnknown() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")

        harness.send(SessionInput.SocketClosed(lease, 1006, "server restarted", harness.now))
        harness.openSocketAfterBackoff()

        harness.httpOk(RequestTag.Queue, """{"queue_running":[],"queue_pending":[]}""")
        harness.httpOk(RequestTag.History("p1"), "{}")

        assertIs<TaskState.Unknown>(harness.state.tasks.getValue("p1").state)
        assertTrue(harness.notices.any { it is UserNotice.TaskLost })
    }

    @Test
    fun scenario4_silentWebSocketDeathRecoversByHistory() {
        // 预算不翻倍（max 等于初始静默）时才允许直接重连
        val harness = Harness(testConfig.copy(progressSilenceMaxMs = 5_000))
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")

        harness.advance(5_001)
        harness.send(SessionInput.Tick(harness.now))
        assertIs<SessionPhase.Reconnecting>(harness.state.phase)

        harness.openSocketAfterBackoff()
        harness.httpOk(
            RequestTag.History("p1"),
            """{"p1":{"status":{"status_str":"success","completed":true},"outputs":{"9":{"images":[{"filename":"done.png"}]}}}}""",
        )

        assertEquals(1, harness.state.tasks.size)
        assertIs<TaskState.Completed>(harness.state.tasks.getValue("p1").state)
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }
}
