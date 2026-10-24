package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.cellular
import com.comfypilot.core.wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 监控帧和进度事件共用一条 socket：只改监控条，不能打断任务状态机，
 * 也不能让旧租约/断线后的读数留在界面上。
 */
class MonitorFlowTest {

    @Test
    fun `monitor frames update the session and unrelated text is ignored`() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)

        harness.ws(lease, MONITOR_FRAME)
        val sample = assertNotNull(harness.state.monitor)
        assertEquals(5.3, sample.cpuPercent!!)
        assertEquals(harness.now, sample.receivedAtMs)

        // 普通进度事件、损坏的监控帧都不得把最后一帧读数清掉。
        harness.ws(lease, """{"type":"status","data":{"status":{"exec_info":{"queue_remaining":0}}}}""")
        harness.ws(lease, """{"type":"crystools.monitor","data":{""")
        assertEquals(5.3, assertNotNull(harness.state.monitor).cpuPercent!!)
        assertEquals(0, harness.state.tasks.size, "监控帧不能凭空造出任务")
    }

    @Test
    fun `stale leases cannot paint monitor data`() {
        val harness = Harness()
        val old = harness.startWithNetwork(wifi)
        harness.send(SessionInput.SocketClosed(old, 1006, "closed", harness.now))
        val fresh = harness.openSocketAfterBackoff()

        harness.ws(old, MONITOR_FRAME)
        assertNull(harness.state.monitor, "旧租约的监控帧必须丢弃")

        harness.ws(fresh, MONITOR_FRAME)
        assertNotNull(harness.state.monitor)
    }

    @Test
    fun `disconnect network change and new lease clear the sample`() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, MONITOR_FRAME)
        assertNotNull(harness.state.monitor)

        // 断线：读数属于旧连接，必须立刻消失。
        harness.send(SessionInput.SocketClosed(lease, 1006, "closed", harness.now))
        assertNull(harness.state.monitor)

        val next = harness.openSocketAfterBackoff()
        harness.ws(next, MONITOR_FRAME)
        assertNotNull(harness.state.monitor)

        // 换网络类别 = 新租约，旧读数同样不能留。
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))
        assertNull(harness.state.monitor)

        harness.send(SessionInput.Stop(harness.now))
        assertNull(harness.state.monitor)
    }

    private companion object {
        const val MONITOR_FRAME =
            """{"type":"crystools.monitor","data":{"cpu_utilization":5.3,"ram_used_percent":50,""" +
                """"gpus":[{"gpu_utilization":2,"gpu_temperature":49,"vram_used_percent":13.5}]}}"""
    }
}