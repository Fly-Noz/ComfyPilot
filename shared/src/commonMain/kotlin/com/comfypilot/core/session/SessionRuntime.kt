package com.comfypilot.core.session

import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.net.ConnectionLease
import com.comfypilot.core.net.HttpOutcome
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionEffect
import com.comfypilot.core.net.SessionInput
import com.comfypilot.core.net.SessionReducer
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskStore
import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpResponse
import com.comfypilot.core.transport.HttpTransport
import com.comfypilot.core.transport.JitterSource
import com.comfypilot.core.transport.TimeSource
import com.comfypilot.core.transport.WebSocketHandle
import com.comfypilot.core.transport.WebSocketListener
import com.comfypilot.core.transport.WebSocketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 唯一驱动状态机的地方：所有输入串行进入，副作用异步执行。
 * 平台层只提供 HttpTransport / WebSocketTransport / TimeSource / TaskStore。
 */
class SessionRuntime(
    private val config: SessionConfig,
    private val http: HttpTransport,
    private val sockets: WebSocketTransport,
    private val store: TaskStore,
    private val clock: TimeSource,
    private val jitter: JitterSource,
    private val scope: CoroutineScope,
    initialTasks: List<PromptTask> = emptyList(),
    nextDraftId: Long = 1L,
    private val onNotice: (UserNotice) -> Unit = {},
) {
    private val reducer = SessionReducer(config, jitter)
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(
        SessionState(tasks = initialTasks.associateBy { it.id }, nextDraftId = nextDraftId),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val handles = mutableMapOf<Long, WebSocketHandle>()
    private var pump: Job? = null
    private var ticker: Job? = null

    fun start() {
        if (pump != null) return
        pump = scope.launch {
            for (input in inputs) {
                val result = reducer.reduce(_state.value, input)
                _state.value = result.state
                dispatch(result.effects)
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(config.tickIntervalMs)
                enqueue(SessionInput.Tick(clock.nowMillis()))
            }
        }
    }

    fun connect() = enqueue(SessionInput.Start(clock.nowMillis()))

    fun disconnect() = enqueue(SessionInput.Stop(clock.nowMillis()))

    fun reconnect() = enqueue(SessionInput.UserReconnect(clock.nowMillis()))

    /** 回到前台时补一次对账：不重连，只对非终态任务查 /history。 */
    fun reconcile(reason: String = "回到前台") =
        enqueue(SessionInput.ReconcileRequested(clock.nowMillis(), reason))

    fun submit(
        workflowJson: String,
        workflowName: String? = null,
        workflowPath: String? = null,
    ) = enqueue(
        SessionInput.SubmitRequested(
            workflowJson = workflowJson,
            nowMs = clock.nowMillis(),
            workflowName = workflowName,
            workflowPath = workflowPath,
        ),
    )

    fun cancel(promptId: String) =
        enqueue(SessionInput.CancelRequested(promptId, clock.nowMillis()))

    fun refreshWorkflows() = enqueue(SessionInput.WorkflowCatalogRequested)

    fun openWorkflow(path: String) = enqueue(SessionInput.WorkflowOpenRequested(path))

    /** 关闭/重新启用当前工作流里的一个节点；只改会话内存。 */
    fun setNodeBypass(nodeId: Int, bypassed: Boolean) =
        enqueue(SessionInput.NodeBypassChanged(nodeId, bypassed))

    fun onNetworkChanged(network: NetworkInfo) =
        enqueue(SessionInput.NetworkChanged(network, clock.nowMillis()))

    fun shutdown() {
        enqueue(SessionInput.Stop(clock.nowMillis()))
        ticker?.cancel()
        pump?.cancel()
        inputs.close()
        handles.values.forEach { handle -> scope.launch { handle.close(1001, "shutdown") } }
        handles.clear()
    }

    private fun enqueue(input: SessionInput) {
        inputs.trySend(input)
    }

    private fun dispatch(effects: List<SessionEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is SessionEffect.OpenSocket -> scope.launch { openSocket(effect.lease) }
                is SessionEffect.CloseSocket -> scope.launch { closeSocket(effect.leaseId) }
                is SessionEffect.SendHttp -> scope.launch { sendHttp(effect) }
                is SessionEffect.Persist -> scope.launch { store.save(effect.task) }
                is SessionEffect.PersistRemoved -> scope.launch { store.remove(effect.id) }
                is SessionEffect.Notify -> onNotice(effect.notice)
            }
        }
    }

    private suspend fun openSocket(lease: ConnectionLease) {
        try {
            val handle = sockets.open(
                lease.wsUrl,
                object : WebSocketListener {
                    override fun onOpen() = onSocketEvent(SessionInput.SocketOpened(lease, clock.nowMillis()))

                    override fun onText(text: String) =
                        onSocketEvent(SessionInput.SocketText(lease, text, clock.nowMillis()))

                    override fun onBinary(bytes: ByteArray) =
                        onSocketEvent(SessionInput.SocketBinary(lease, bytes, clock.nowMillis()))

                    override fun onClosed(code: Int, reason: String) =
                        onSocketEvent(SessionInput.SocketClosed(lease, code, reason, clock.nowMillis()))

                    override fun onFailure(message: String) =
                        onSocketEvent(SessionInput.SocketFailed(lease, message, clock.nowMillis()))
                },
            )
            handles[lease.leaseId] = handle
        } catch (t: Throwable) {
            enqueue(SessionInput.SocketFailed(lease, t.message ?: "WebSocket 打开失败", clock.nowMillis()))
        }
    }

    private suspend fun closeSocket(leaseId: Long) {
        handles.remove(leaseId)?.close(1000, "superseded")
    }

    private suspend fun sendHttp(effect: SessionEffect.SendHttp) {
        val resolved = runCatching { ApiPaths.resolve(effect.lease.baseUrl, effect.call) }
            .getOrElse { error ->
                enqueue(
                    SessionInput.HttpResult(
                        effect.lease,
                        effect.tag,
                        HttpOutcome.Failed(error.message ?: "请求构造失败"),
                        clock.nowMillis(),
                    ),
                )
                return
            }
        val request = HttpRequest(
            method = resolved.method,
            url = resolved.url,
            body = resolved.body,
            contentType = resolved.contentType,
            timeoutMs = config.httpTimeoutMs,
        )
        val outcome = try {
            http.execute(request).toOutcome()
        } catch (t: Throwable) {
            HttpOutcome.Failed(t.message ?: "HTTP 调用失败")
        }
        enqueue(SessionInput.HttpResult(effect.lease, effect.tag, outcome, clock.nowMillis()))
    }

    private fun onSocketEvent(input: SessionInput) {
        // 传输层回调可能来自任意线程；统一进入单线程 actor。
        enqueue(input)
    }

    private fun HttpResponse.toOutcome(): HttpOutcome =
        if (failure != null) HttpOutcome.Failed(failure) else HttpOutcome.Received(status, text())
}
