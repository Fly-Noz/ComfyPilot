package com.comfypilot.core.selftest

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.NetworkTransport
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionEffect
import com.comfypilot.core.net.SessionInput
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionReducer
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.transport.JitterSource
import kotlinx.serialization.Serializable

@Serializable
data class SelfTestReport(
    val ok: Boolean,
    val generationAfterStart: Long,
    val generationAfterSwitch: Long,
    val connectedLabel: String,
    val phaseAfterSwitch: String,
    val openSocketEffects: Int,
    val queueRunning: Int,
    val message: String,
)

private object NoJitter : JitterSource {
    override fun nextSigned(): Double = 0.0
}

/**
 * 跨平台可执行自检：不联网、不碰平台 API，验证共享层在目标平台上确实能跑。
 * 覆盖三条最关键的不变量：
 *  1. 首次启动 generation = 1，且产生且仅产生一个 OpenSocket；
 *  2. WiFi -> 蜂窝切换 generation 递增到 2；
 *  3. /queue 响应能被序列化层解析。
 */
fun runSelfTest(): SelfTestReport {
    val reducer = SessionReducer(SessionConfig(baseUrl = "http://127.0.0.1:8188"), NoJitter)
    var state = SessionState()

    state = reducer.reduce(
        state,
        SessionInput.NetworkChanged(NetworkInfo(NetworkTransport.WIFI, validated = true), 500),
    ).state

    val start = reducer.reduce(state, SessionInput.Start(1_000))
    state = start.state
    val lease = state.phase.lease()
    val openSocketEffects = start.effects.count { it is SessionEffect.OpenSocket }
    val generationAfterStart = state.generation

    state = if (lease != null) {
        reducer.reduce(state, SessionInput.SocketOpened(lease, 2_000)).state
    } else {
        state
    }
    val connectedLabel = state.connectionLabel

    state = if (lease != null) {
        reducer.reduce(
            state,
            SessionInput.NetworkChanged(NetworkInfo(NetworkTransport.CELLULAR, validated = true), 3_000),
        ).state
    } else {
        state
    }
    val generationAfterSwitch = state.generation
    val phaseAfterSwitch = phaseName(state.phase)

    val queue = ApiCodec.decodeQueue("""{"queue_running":[[1,"p1",{}]],"queue_pending":[]}""")

    val ok = generationAfterStart == 1L &&
        openSocketEffects == 1 &&
        connectedLabel == "已连接" &&
        generationAfterSwitch == 2L &&
        phaseAfterSwitch == "Connecting" &&
        queue.running.size == 1

    return SelfTestReport(
        ok = ok,
        generationAfterStart = generationAfterStart,
        generationAfterSwitch = generationAfterSwitch,
        connectedLabel = connectedLabel,
        phaseAfterSwitch = phaseAfterSwitch,
        openSocketEffects = openSocketEffects,
        queueRunning = queue.running.size,
        message = if (ok) "共享层自检通过" else "共享层自检失败",
    )
}

fun runSelfTestJson(): String = ApiCodec.json.encodeToString(SelfTestReport.serializer(), runSelfTest())

private fun phaseName(phase: SessionPhase): String = when (phase) {
    is SessionPhase.Idle -> "Idle"
    is SessionPhase.Connecting -> "Connecting"
    is SessionPhase.Connected -> "Connected"
    is SessionPhase.Reconnecting -> "Reconnecting"
    is SessionPhase.Recovering -> "Recovering"
}
