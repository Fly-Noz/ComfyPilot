package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.WORKFLOW
import com.comfypilot.core.cellular
import com.comfypilot.core.wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 网络切换后，旧代际的一切迟到回调都必须被丢弃。
 */
class GenerationIsolationTest {
    @Test
    fun staleSocketCloseDoesNotKillNewConnection() {
        val harness = Harness()
        val old = harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))
        val phaseBefore = harness.state.phase

        harness.send(SessionInput.SocketClosed(old, 1006, "stale", harness.now))

        assertEquals(phaseBefore, harness.state.phase)
        assertTrue(harness.effects.isEmpty())
    }

    @Test
    fun staleHttpResponseDoesNotPolluteState() {
        val harness = Harness()
        val old = harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))

        harness.send(
            SessionInput.HttpResult(
                old,
                RequestTag.Submit(draft, WORKFLOW),
                HttpOutcome.Received(200, """{"prompt_id":"from-old-generation"}"""),
                harness.now,
            ),
        )

        assertNull(harness.state.tasks.values.firstOrNull { it.promptId == "from-old-generation" })
        assertTrue(harness.effects.isEmpty())
    }

    @Test
    fun staleWebSocketFrameDoesNotMoveTasks() {
        val harness = Harness()
        val old = harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))

        harness.ws(old, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")

        assertTrue(harness.state.tasks.isEmpty())
        assertTrue(harness.effects.isEmpty())
    }

    @Test
    fun newLeaseIdsAreMonotonic() {
        val harness = Harness()
        val l1 = harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))
        val l2 = (harness.state.phase as SessionPhase.Connecting).lease
        harness.send(SessionInput.NetworkChanged(wifi, harness.now))
        val l3 = (harness.state.phase as SessionPhase.Connecting).lease

        assertTrue(l1.leaseId < l2.leaseId)
        assertTrue(l2.leaseId < l3.leaseId)
        assertEquals(3, harness.state.generation)
    }

    @Test
    fun oldLeaseHttpDoesNotAdvanceReconcile() {
        val harness = Harness()
        val old = harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        harness.acceptSubmit(draft, "p1", old)
        harness.send(SessionInput.SocketClosed(old, 1006, "drop", harness.now))
        val newLease = harness.openSocketAfterBackoff()

        harness.send(
            SessionInput.HttpResult(
                old,
                RequestTag.History("p1"),
                HttpOutcome.Received(200, """{"p1":{"status":{"status_str":"success","completed":true},"outputs":{"9":{"images":[{"filename":"old.png"}]}}}}"""),
                harness.now,
            ),
        )

        val phase = assertIs<SessionPhase.Recovering>(harness.state.phase)
        assertEquals(newLease.leaseId, phase.lease.leaseId)
        assertTrue(harness.state.tasks.getValue("p1").outputs.isEmpty())
    }
}
