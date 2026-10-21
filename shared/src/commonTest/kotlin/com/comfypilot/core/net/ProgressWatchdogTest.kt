package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 进度静默看门狗：WS 看起来还连着，但 ComfyUI 可能已经死了。
 */
class ProgressWatchdogTest {
    private fun runningHarness(): Pair<Harness, ConnectionLease> {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        return harness to lease
    }

    @Test
    fun silenceBeyondBudgetTriggersReconnect() {
        val (harness, lease) = runningHarness()

        harness.advance(4_999)
        harness.send(SessionInput.Tick(harness.now))
        assertIs<SessionPhase.Connected>(harness.state.phase)

        harness.advance(2)
        harness.send(SessionInput.Tick(harness.now))

        val reconnecting = assertIs<SessionPhase.Reconnecting>(harness.state.phase)
        assertEquals(ReconnectCause.PROGRESS_TIMEOUT, reconnecting.cause)
        assertTrue(harness.effects.filterIsInstance<SessionEffect.CloseSocket>().any { it.leaseId == lease.leaseId })
        assertTrue(harness.notices.any { it is UserNotice.ProgressTimeout })
    }

    @Test
    fun progressResetsTheSilenceClock() {
        val (harness, lease) = runningHarness()

        harness.advance(4_000)
        harness.ws(lease, """{"type":"progress","data":{"prompt_id":"p1","node":"9","value":1,"max":10}}""")
        harness.advance(4_000)
        harness.send(SessionInput.Tick(harness.now))

        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    @Test
    fun staleLeaseProgressDoesNotResetWatchdog() {
        val (harness, _) = runningHarness()
        val stale = harness.lease()

        harness.advance(5_001)
        harness.send(SessionInput.Tick(harness.now))
        assertIs<SessionPhase.Reconnecting>(harness.state.phase)

        harness.ws(stale, """{"type":"progress","data":{"prompt_id":"p1","node":"9","value":5,"max":10}}""")

        val phase = assertIs<SessionPhase.Reconnecting>(harness.state.phase)
        assertEquals(ReconnectCause.PROGRESS_TIMEOUT, phase.cause)
        assertEquals(0.0, harness.state.tasks.getValue("p1").progress?.value ?: 0.0)
    }

    @Test
    fun connectTimeoutIsTreatedAsFailure() {
        val harness = Harness()
        harness.send(SessionInput.NetworkChanged(wifi, harness.now))
        harness.send(SessionInput.Start(harness.now))
        assertIs<SessionPhase.Connecting>(harness.state.phase)

        harness.advance(1_001)
        harness.send(SessionInput.Tick(harness.now))

        val phase = assertIs<SessionPhase.Reconnecting>(harness.state.phase)
        assertEquals(ReconnectCause.CONNECT_TIMEOUT, phase.cause)
    }

    @Test
    fun reconnectResumesWithBackoffThenRecovers() {
        val (harness, _) = runningHarness()
        harness.advance(5_001)
        harness.send(SessionInput.Tick(harness.now))
        val first = assertIs<SessionPhase.Reconnecting>(harness.state.phase)

        harness.advance(999)
        harness.send(SessionInput.Tick(harness.now))
        assertIs<SessionPhase.Reconnecting>(harness.state.phase)

        harness.advance(2)
        harness.send(SessionInput.Tick(harness.now))
        val connecting = assertIs<SessionPhase.Connecting>(harness.state.phase)
        assertEquals(2, connecting.attempt)
        assertTrue(connecting.startedAtMs >= first.startedAtMs)
    }
}
