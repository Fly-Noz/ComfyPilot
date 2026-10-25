package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.testConfig
import com.comfypilot.core.wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * clientId 复用。
 *
 * ComfyUI 用 `?clientId=` 作为 WebSocket 会话 id，`progress` 与采样预览按提交任务的
 * 会话定向推送。重连换 id，旧任务的进度和画面就再也收不到——现场表现就是
 * "切后台回来，最后一帧还在，但进度和采样都冻住"。
 */
class ClientIdTest {

    @Test
    fun configuredClientIdIsReusedForEveryLease() {
        val harness = Harness(testConfig.copy(clientId = "fixed-client-id"))
        val first = harness.startWithNetwork(wifi)
        assertEquals("fixed-client-id", first.clientId)
        assertEquals("fixed-client-id", harness.lease().clientId)

        // 断线重连：新租约仍然用同一个 id，服务端才认得出是"同一个会话"
        harness.send(SessionInput.SocketFailed(first, "Software caused connection abort", harness.now))
        harness.advance(1_001)
        harness.send(SessionInput.Tick(harness.now))
        val second = (harness.state.phase as SessionPhase.Connecting).lease
        assertEquals("fixed-client-id", second.clientId)
    }

    @Test
    fun blankClientIdFallsBackToRandomPerLease() {
        val harness = Harness(testConfig)
        val first = harness.startWithNetwork(wifi)
        val second = Harness(testConfig).startWithNetwork(wifi)
        assertNotEquals("", first.clientId)
        // 空配置保持旧行为：每次建连随机，互不相同
        assertNotEquals(first.clientId, second.clientId)
    }
}
