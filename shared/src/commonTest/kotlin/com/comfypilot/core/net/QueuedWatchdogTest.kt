package com.comfypilot.core.net

import com.comfypilot.core.Harness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 排队中的任务也要能自愈：Android 上出现过 WebSocket 半死、任务在主机上已经跑完，
 * 客户端却一直显示"排队中"的情况；修复靠两处——排队静默看门狗和回到前台的对账入口。
 */
class QueuedWatchdogTest {

    private fun Harness.queuedTask(promptId: String = "p1"): String {
        val draft = submitDraft()
        acceptSubmit(draft, promptId)
        return promptId
    }

    @Test
    fun `silent queued task triggers a history probe`() {
        val h = Harness()
        h.startWithNetwork()
        h.queuedTask()

        h.advance(11_000)
        h.send(SessionInput.Tick(h.now))

        val probes = h.effects.filterIsInstance<SessionEffect.SendHttp>()
            .filter { it.tag is RequestTag.History }
        assertEquals(listOf(RequestTag.History("p1")), probes.map { it.tag })
        val phase = h.state.phase as SessionPhase.Recovering
        assertTrue(phase.pending.containsKey("p1"))
        // 只查历史，不关 socket、不改 generation
        assertTrue(h.effects.none { it is SessionEffect.CloseSocket })
    }

    @Test
    fun `queued task inside the silence window is left alone`() {
        val h = Harness()
        h.startWithNetwork()
        h.queuedTask()

        h.advance(4_000)
        h.send(SessionInput.Tick(h.now))

        assertTrue(h.effects.isEmpty())
    }

    @Test
    fun `reconcile request probes active tasks without dropping the socket`() {
        val h = Harness()
        h.startWithNetwork()
        h.queuedTask("px")

        h.send(SessionInput.ReconcileRequested(h.now, "回到前台"))

        val probes = h.effects.filterIsInstance<SessionEffect.SendHttp>()
        assertEquals(listOf(RequestTag.History("px")), probes.map { it.tag })
        assertTrue(h.effects.none { it is SessionEffect.CloseSocket })
        assertTrue(h.state.lease != null)
    }

    @Test
    fun `reconcile request with nothing in flight does nothing`() {
        val h = Harness()
        h.startWithNetwork()

        h.send(SessionInput.ReconcileRequested(h.now, "回到前台"))

        assertTrue(h.effects.isEmpty())
        assertTrue(h.state.phase is SessionPhase.Connected)
    }
}