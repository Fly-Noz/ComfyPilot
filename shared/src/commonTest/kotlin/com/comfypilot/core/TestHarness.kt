package com.comfypilot.core

import com.comfypilot.core.net.ConnectionLease
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.NetworkTransport
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionEffect
import com.comfypilot.core.net.SessionInput
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionReducer
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.transport.JitterSource

val testConfig = SessionConfig(
    baseUrl = "http://127.0.0.1:8188",
    connectTimeoutMs = 1_000,
    httpTimeoutMs = 5_000,
    initialBackoffMs = 1_000,
    maxBackoffMs = 8_000,
    backoffMultiplier = 2.0,
    jitterRatio = 0.0,
    reconcileMaxAttempts = 3,
    reconcileWindowMs = 10_000,
    reconcileRetryBaseMs = 1_000,
    progressSilenceMs = 5_000,
    progressSilenceMaxMs = 40_000,
    tickIntervalMs = 500,
)

val wifi = NetworkInfo(NetworkTransport.WIFI, validated = true, handle = "wlan0")
val cellular = NetworkInfo(NetworkTransport.CELLULAR, validated = true, handle = "rmnet0")
val offline = NetworkInfo(NetworkTransport.NONE, validated = false)

object NoJitter : JitterSource {
    override fun nextSigned(): Double = 0.0
}

const val WORKFLOW = """{"3":{"class_type":"KSampler","inputs":{}}}"""

class Harness(
    val config: SessionConfig = testConfig,
    val jitter: JitterSource = NoJitter,
    initial: SessionState = SessionState(),
) {
    var now: Long = 1_000
    var state: SessionState = initial
    private val reducer = SessionReducer(config, jitter)

    var effects: List<SessionEffect> = emptyList()
        private set
    val allEffects = mutableListOf<SessionEffect>()
    val notices = mutableListOf<UserNotice>()

    fun send(input: SessionInput): SessionState {
        val result = reducer.reduce(state, input)
        state = result.state
        effects = result.effects
        allEffects += result.effects
        notices += result.effects.filterIsInstance<SessionEffect.Notify>().map { it.notice }
        return state
    }

    fun advance(ms: Long) {
        now += ms
    }

    fun lease(): ConnectionLease = requireNotNull(state.lease) { "当前状态没有租约：${state.phase}" }

    fun startWithNetwork(network: NetworkInfo = wifi): ConnectionLease {
        send(SessionInput.NetworkChanged(network, now))
        send(SessionInput.Start(now))
        val lease = (state.phase as SessionPhase.Connecting).lease
        send(SessionInput.SocketOpened(lease, now))
        return lease
    }

    fun openSocketAfterBackoff(): ConnectionLease {
        advance(config.initialBackoffMs)
        send(SessionInput.Tick(now))
        val lease = (state.phase as SessionPhase.Connecting).lease
        send(SessionInput.SocketOpened(lease, now))
        return lease
    }

    fun submitDraft(): String {
        send(SessionInput.SubmitRequested(WORKFLOW, now))
        val draft = state.tasks.values.first { it.state is com.comfypilot.core.task.TaskState.Submitting }
        return draft.id
    }

    fun acceptSubmit(draftId: String, promptId: String, lease: ConnectionLease = lease()) {
        send(
            SessionInput.HttpResult(
                lease,
                com.comfypilot.core.net.RequestTag.Submit(draftId, WORKFLOW),
                com.comfypilot.core.net.HttpOutcome.Received(200, """{"prompt_id":"$promptId","number":1}"""),
                now,
            ),
        )
    }

    fun httpOk(tag: com.comfypilot.core.net.RequestTag, body: String, lease: ConnectionLease = lease()) {
        send(
            SessionInput.HttpResult(
                lease,
                tag,
                com.comfypilot.core.net.HttpOutcome.Received(200, body),
                now,
            ),
        )
    }

    fun httpFails(tag: com.comfypilot.core.net.RequestTag, message: String = "timeout", lease: ConnectionLease = lease()) {
        send(SessionInput.HttpResult(lease, tag, com.comfypilot.core.net.HttpOutcome.Failed(message), now))
    }

    fun ws(lease: ConnectionLease, json: String) {
        send(SessionInput.SocketText(lease, json, now))
    }
}

fun sessionInputsKinds(effects: List<SessionEffect>): List<String> = effects.map { it::class.simpleName ?: "?" }
