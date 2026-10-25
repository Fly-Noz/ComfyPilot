package com.comfypilot.core.session

import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.net.ConnectionLease
import com.comfypilot.core.net.HttpOutcome
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionEffect
import com.comfypilot.core.DiagnosticTrace
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** leaseId -> clientId：同一 clientId 只允许一条 socket。 */
    private val handleClients = mutableMapOf<Long, String>()
    /** 已经要求关闭但还没建好的租约：建好后立刻关，避免留下"幽灵 socket"。 */
    private val closedLeases = mutableSetOf<Long>()
    /** 每条 socket 的"已关闭"信号：换连接时要等旧连接真的关掉再建新的。 */
    private val closeSignals = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val handleLock = Mutex()
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
            // 诊断：只记"会改变连接/任务可见状态"的动作，Persist 太吵不记
            when (effect) {
                is SessionEffect.SendHttp -> DiagnosticTrace.emit("http-> ${effect.call}")
                is SessionEffect.OpenSocket -> DiagnosticTrace.emit("ws.open gen${effect.lease.generation}")
                is SessionEffect.CloseSocket -> DiagnosticTrace.emit("ws.close: ${effect.reason}")
                is SessionEffect.Notify -> DiagnosticTrace.emit("notice " + effect.notice)
                else -> Unit
            }
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
        // 连接建立过程中网络回调可能再发一次 OpenSocket。同一租约、或同一 clientId
        // 已经有 socket 时都不再建：ComfyUI 的 sockets 以 sid(=clientId) 为 key，
        // 两条同 sid 的连接会互相覆盖，旧那条关闭时还会把新连接的注册一起删掉，
        // 结果就是"连接看着正常，但再也收不到 progress / 预览帧"。
        var superseded: WebSocketHandle? = null
        var staleSignal: CompletableDeferred<Unit>? = null
        handleLock.withLock {
            if (handles.containsKey(lease.leaseId)) {
                DiagnosticTrace.emit("ws.open skipped dup gen${lease.generation}")
                return
            }
            // 同一 clientId 只允许一条 socket：ComfyUI 用 sid(=clientId) 做 key，
            // 两条同时存在会互相覆盖，旧连接关闭时还会 pop 掉新的注册。
            val staleLease = handleClients.entries.firstOrNull { it.value == lease.clientId }?.key
            if (staleLease != null) {
                superseded = handles.remove(staleLease)
                handleClients.remove(staleLease)
                staleSignal = closeSignals[staleLease]
            }
        }
        if (superseded != null) {
            superseded.close(1000, "superseded")
            // 等旧连接真的关掉再建新的：服务端是"关闭时按 sid 删除"，
            // 迟到的删除会把新连接一起从表里抹掉，之后收不到任何事件。
            val closed = withTimeoutOrNull(2_000) { staleSignal?.await() ?: Unit }
            DiagnosticTrace.emit(
                "ws.supersede gen${lease.generation} closed=${closed != null}",
            )
        }
        try {
            val signal = CompletableDeferred<Unit>()
        handleLock.withLock { closeSignals[lease.leaseId] = signal }
        val handle = sockets.open(
                lease.wsUrl,
                object : WebSocketListener {
                    override fun onOpen() {
                        DiagnosticTrace.emit("ws.ok gen${lease.generation}")
                        onSocketEvent(SessionInput.SocketOpened(lease, clock.nowMillis()))
                    }

                    override fun onText(text: String) {
                        DiagnosticTrace.socketText(text)
                        onSocketEvent(SessionInput.SocketText(lease, text, clock.nowMillis()))
                    }

                    override fun onBinary(bytes: ByteArray) {
                        DiagnosticTrace.socketBinary(bytes.size)
                        onSocketEvent(SessionInput.SocketBinary(lease, bytes, clock.nowMillis()))
                    }

                    override fun onClosed(code: Int, reason: String) {
                        DiagnosticTrace.emit("ws.closed $code $reason")
                        signal.complete(Unit)
                        onSocketEvent(SessionInput.SocketClosed(lease, code, reason, clock.nowMillis()))
                    }

                    override fun onFailure(message: String) {
                        DiagnosticTrace.emit("ws.fail $message")
                        signal.complete(Unit)
                        onSocketEvent(SessionInput.SocketFailed(lease, message, clock.nowMillis()))
                    }
                },
            )
            var closeNow = false
            handleLock.withLock {
                // 建连期间租约可能已被关闭（网络切换/重连）：直接关掉这条，避免幽灵 socket
                if (closedLeases.remove(lease.leaseId)) {
                    closeNow = true
                } else {
                    handles[lease.leaseId] = handle
                    handleClients[lease.leaseId] = lease.clientId
                }
            }
            if (closeNow) {
                handle.close(1000, "superseded")
                DiagnosticTrace.emit("ws.open closed race gen${lease.generation}")
            }
        } catch (t: Throwable) {
            enqueue(SessionInput.SocketFailed(lease, t.message ?: "WebSocket 打开失败", clock.nowMillis()))
        }
    }

    private suspend fun closeSocket(leaseId: Long) {
        var handle: WebSocketHandle? = null
        handleLock.withLock {
            handle = handles.remove(leaseId)
            handleClients.remove(leaseId)
            if (handle == null) {
                // 建连还没返回：记下来，openSocket 完成后立刻关掉，避免泄漏一条 socket
                closedLeases.add(leaseId)
            }
        }
        handle?.close(1000, "superseded")
        if (handle != null) {
            // 等关闭回调到达，调用方（例如换租约）可以据此确认服务端已注销旧 sid
            withTimeoutOrNull(2_000) { closeSignals[leaseId]?.await() }
        }
        handleLock.withLock { closeSignals.remove(leaseId) }
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
        when (outcome) {
            is HttpOutcome.Received -> DiagnosticTrace.emit(
                "http<- ${effect.tag} ${outcome.status} ${DiagnosticTrace.clip(outcome.body ?: "")}",
            )

            is HttpOutcome.Failed -> DiagnosticTrace.emit("http<- ${effect.tag} failed: ${outcome.message}")
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
