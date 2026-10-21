package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.cellular
import com.comfypilot.core.offline
import com.comfypilot.core.wifi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkSwitchTest {
    @Test
    fun wifiToCellularBumpsGenerationAndRebuildsSocket() {
        val harness = Harness()
        val first = harness.startWithNetwork(wifi)
        assertEquals(1, harness.state.generation)

        harness.send(SessionInput.NetworkChanged(cellular, harness.now))

        assertEquals(2, harness.state.generation)
        assertTrue(harness.effects.filterIsInstance<SessionEffect.CloseSocket>().any { it.leaseId == first.leaseId })
        val connecting = assertIs<SessionPhase.Connecting>(harness.state.phase)
        assertEquals(2, connecting.lease.generation)
        assertNotEquals(first.clientId, connecting.lease.clientId)
        assertEquals(1, connecting.attempt)
    }

    @Test
    fun sameTransportDoesNotRebuild() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        val before = harness.state

        harness.send(SessionInput.NetworkChanged(wifi.copy(validated = false, handle = "wlan1"), harness.now))

        assertEquals(before.generation, harness.state.generation)
        assertEquals(before.phase, harness.state.phase)
        assertTrue(harness.effects.isEmpty())
    }

    @Test
    fun networkLossWaitsWithoutSpinning() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(offline, harness.now))

        assertEquals(1, harness.state.generation)
        val reconnecting = assertIs<SessionPhase.Reconnecting>(harness.state.phase)
        assertEquals(ReconnectCause.NETWORK_LOST, reconnecting.cause)
        assertEquals(Long.MAX_VALUE, reconnecting.nextAttemptAtMs)

        // 没有网络时空转 Tick 不应产生新连接
        harness.advance(60_000)
        harness.send(SessionInput.Tick(harness.now))
        assertIs<SessionPhase.Reconnecting>(harness.state.phase)
        assertTrue(harness.effects.isEmpty())
    }

    @Test
    fun networkReturnKeepsGeneration() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(offline, harness.now))
        harness.send(SessionInput.NetworkChanged(wifi, harness.now))

        assertEquals(1, harness.state.generation)
        assertIs<SessionPhase.Connecting>(harness.state.phase)
    }

    @Test
    fun lossThenNewClassStillBumpsExactlyOnce() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        harness.send(SessionInput.NetworkChanged(offline, harness.now))
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))

        // onLost 先到也不能把 WiFi -> 蜂窝 误判成"同类恢复"
        assertEquals(2, harness.state.generation)
        assertIs<SessionPhase.Connecting>(harness.state.phase)
    }

    @Test
    fun idleStateOnlyRecordsNetwork() {
        val harness = Harness()
        harness.send(SessionInput.NetworkChanged(cellular, harness.now))

        assertIs<SessionPhase.Idle>(harness.state.phase)
        assertEquals(NetworkTransport.CELLULAR, harness.state.network.transport)
        assertTrue(harness.effects.isEmpty())
    }
}
