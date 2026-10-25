package com.comfypilot.core.net

import com.comfypilot.core.DiagnosticTrace
import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.HistoryEntry
import com.comfypilot.core.api.PromptRequestBody
import com.comfypilot.core.api.PromptResponse
import com.comfypilot.core.api.QueueSnapshot
import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.event.ComfyEvent
import com.comfypilot.core.event.EventDecoder
import com.comfypilot.core.event.PreviewDecoder
import com.comfypilot.core.reconcile.HistoryProbe
import com.comfypilot.core.reconcile.ReconcileBudget
import com.comfypilot.core.reconcile.ReconcileOutcome
import com.comfypilot.core.reconcile.ReconcilePolicy
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskProgress
import com.comfypilot.core.task.TaskState
import com.comfypilot.core.transport.JitterSource
import com.comfypilot.core.util.Ids
import com.comfypilot.core.workflow.NodeDefinition
import com.comfypilot.core.workflow.WorkflowCatalog
import com.comfypilot.core.workflow.WorkflowCodec
import com.comfypilot.core.workflow.WorkflowDocument
import com.comfypilot.core.workflow.WorkflowSelection
import com.comfypilot.core.workflow.workflowNameOf
import kotlinx.serialization.json.JsonObject

/** 一份工作流最多为这么多个节点类拉定义：病态文件不能变成几百个请求。 */
private const val MAX_OBJECT_INFO_REQUESTS = 128

/**
 * 纯函数状态机：输入 -> (新状态, 副作用)。
 * 不启动协程、不碰平台 API，因此所有连接语义都能在 jvmTest 里直接断言。
 */
class SessionReducer(
    private val config: SessionConfig,
    private val jitter: JitterSource,
) {
    fun reduce(state: SessionState, input: SessionInput): ReduceResult = when (input) {
        is SessionInput.Start -> onStart(state, input.nowMs)
        is SessionInput.Stop -> onStop(state, input.nowMs)
        is SessionInput.NetworkChanged -> onNetworkChanged(state, input)
        is SessionInput.SocketOpened -> onSocketOpened(state, input)
        is SessionInput.SocketText -> onSocketText(state, input)
        is SessionInput.SocketBinary -> onSocketBinary(state, input)
        is SessionInput.SocketClosed -> onSocketLost(
            state, input.lease, input.nowMs, "连接关闭 code=${input.code} ${input.reason}", ReconnectCause.SOCKET_CLOSED,
        )

        is SessionInput.SocketFailed -> onSocketLost(
            state, input.lease, input.nowMs, input.message, ReconnectCause.SOCKET_FAILED,
        )

        is SessionInput.HttpResult -> onHttpResult(state, input)
        is SessionInput.SubmitRequested -> onSubmit(state, input)
        is SessionInput.CancelRequested -> onCancel(state, input)
        SessionInput.WorkflowCatalogRequested -> onCatalogRequested(state)
        is SessionInput.WorkflowOpenRequested -> onWorkflowOpenRequested(state, input)
        is SessionInput.NodeBypassChanged -> onNodeBypassChanged(state, input)
        is SessionInput.UserReconnect -> onUserReconnect(state, input.nowMs)
        is SessionInput.ReconcileRequested -> onReconcileRequested(state, input)
        is SessionInput.Tick -> onTick(state, input.nowMs)
    }

    // ---------------------------------------------------------------- start/stop

    private fun onStart(state: SessionState, now: Long): ReduceResult {
        if (state.phase !is SessionPhase.Idle) return ReduceResult(state)
        return openLease(state, now, generation = state.generation + 1, attempt = 1)
    }

    private fun onStop(state: SessionState, now: Long): ReduceResult {
        val effects = closeCurrent(state, "用户停止")
        return ReduceResult(
            state.copy(phase = SessionPhase.Idle("已停止"), preview = null, monitor = null),
            effects,
        )
    }

    private fun onUserReconnect(state: SessionState, now: Long): ReduceResult {
        if (state.phase is SessionPhase.Idle) return ReduceResult(state)
        val closed = closeCurrent(state, "用户手动重连")
        return openLease(state, now, generation = state.generation, attempt = 1)
            .let { it.copy(effects = closed + it.effects) }
    }

    // ---------------------------------------------------------------- network

    private fun onNetworkChanged(state: SessionState, input: SessionInput.NetworkChanged): ReduceResult {
        val previous = state.network
        val next = input.network

        // 同类网络波动（例如同一 WiFi 下换 AP、信号抖动）不重建连接，也不递增代际。
        if (previous.transport == next.transport) {
            return ReduceResult(
                state.copy(
                    network = next,
                    lastOnlineTransport = if (next.transport != NetworkTransport.NONE) {
                        next.transport
                    } else {
                        state.lastOnlineTransport
                    },
                ),
            )
        }

        // 仅当"在线网络类别发生变化"时才递增 generation。
        // 关键：用 lastOnlineTransport 兜住 onLost -> onAvailable 的到达顺序，
        // 否则 WiFi 切蜂窝会被误判成"断网后又连回同类网络"。
        val switchedBetweenOnlineClasses =
            next.transport != NetworkTransport.NONE &&
                state.lastOnlineTransport != NetworkTransport.NONE &&
                next.transport != state.lastOnlineTransport
        val generation = if (switchedBetweenOnlineClasses) state.generation + 1 else state.generation
        val lastOnline = if (next.transport != NetworkTransport.NONE) next.transport else state.lastOnlineTransport

        if (state.phase is SessionPhase.Idle) {
            return ReduceResult(
                state.copy(network = next, generation = generation, lastOnlineTransport = lastOnline),
            )
        }

        val closed = closeCurrent(state, "网络类别变化")
        val base = state.copy(
            network = next,
            generation = generation,
            lastOnlineTransport = lastOnline,
            queue = null,
            preview = null,
            monitor = null,
        )

        if (next.transport == NetworkTransport.NONE) {
            return ReduceResult(
                base.copy(
                    phase = SessionPhase.Reconnecting(
                        attempt = 1,
                        nextAttemptAtMs = Long.MAX_VALUE, // 等网络回来，不空转重试
                        cause = ReconnectCause.NETWORK_LOST,
                        lastLeaseId = state.lease?.leaseId,
                        startedAtMs = input.nowMs,
                    ),
                ),
                closed + SessionEffect.Notify(UserNotice.Disconnected("网络已断开")),
            )
        }

        return openLease(base, input.nowMs, generation, attempt = 1)
            .let { it.copy(effects = closed + it.effects) }
    }

    // ---------------------------------------------------------------- socket

    private fun onSocketOpened(state: SessionState, input: SessionInput.SocketOpened): ReduceResult {
        val phase = state.phase as? SessionPhase.Connecting ?: return ReduceResult(state)
        if (phase.lease.leaseId != input.lease.leaseId) return ReduceResult(state)

        val effects = mutableListOf<SessionEffect>()
        // 握手后声明能力：网页端就是靠这条让服务端发"可动"的预览（VHS 多帧 / 预览元数据）。
        // 不发就只会收到每步一张的普通 JPEG。
        effects += SessionEffect.SendSocketText(input.lease, FEATURE_FLAGS_JSON)
        // 重连不丢最后一帧：ComfyUI 只在采样过程中发 preview，重连后要等下一帧，
        // 期间清空会让界面从有图变成"等待采样画面…"，看起来像任务退回去了。
        var next = state.copy(monitor = null)

        // 建连期间还在提交的任务：结果不可知，不能假装它成功或失败。
        state.tasks.values.filter { it.state is TaskState.Submitting }.forEach { draft ->
            val unknown = draft.copy(
                state = TaskState.Unknown("提交被连接中断，结果未知"),
                updatedAtMs = input.nowMs,
            )
            next = next.copy(tasks = next.tasks + (unknown.id to unknown))
            effects += SessionEffect.Persist(unknown)
        }

        val pending = next.activeTasks
        if (pending.isEmpty()) {
            return ReduceResult(
                next.copy(phase = SessionPhase.Connected(input.lease, input.nowMs), queue = null),
                effects + SessionEffect.Notify(UserNotice.Connected(state.generation)),
            )
        }

        val slots = pending.associate { task ->
            val id = task.promptId!!
            id to ReconcileSlot(promptId = id, startedAtMs = input.nowMs)
        }
        effects += SessionEffect.SendHttp(input.lease, ApiCall.FetchQueue, RequestTag.Queue)
        slots.keys.forEach { id ->
            effects += SessionEffect.SendHttp(input.lease, ApiCall.FetchHistory(id), RequestTag.History(id))
        }
        return ReduceResult(
            next.copy(
                phase = SessionPhase.Recovering(
                    lease = input.lease,
                    pending = slots,
                    queueSeen = false,
                    totalCount = slots.size,
                    queueRequestedAtMs = input.nowMs,
                ),
                queue = null,
            ),
            effects,
        )
    }

    private fun onSocketLost(
        state: SessionState,
        lease: ConnectionLease,
        now: Long,
        reason: String,
        cause: ReconnectCause,
    ): ReduceResult {
        // 旧租约的迟到关闭回调不得影响新连接。
        if (!isCurrent(state, lease)) return ReduceResult(state)
        if (state.phase is SessionPhase.Reconnecting || state.phase is SessionPhase.Idle) {
            return ReduceResult(state)
        }
        val attempt = 1
        val delay = config.backoffMs(attempt, jitter)
        return ReduceResult(
            state.copy(
                phase = SessionPhase.Reconnecting(
                    attempt = attempt,
                    nextAttemptAtMs = now + delay,
                    cause = cause,
                    lastLeaseId = lease.leaseId,
                    startedAtMs = now,
                ),
                queue = null,
                // 与 openLease 一致：断线只是"暂时收不到"，最后一帧留着并标陈旧。
                monitor = null,
            ),
            listOf(
                SessionEffect.CloseSocket(lease.leaseId, reason),
                SessionEffect.Notify(UserNotice.Disconnected(reason)),
            ),
        )
    }

    private fun onSocketText(state: SessionState, input: SessionInput.SocketText): ReduceResult {
        if (!isCurrent(state, input.lease)) return ReduceResult(state)
        val event = EventDecoder.decode(input.text)
        val effects = mutableListOf<SessionEffect>()
        var next = state

        when (event) {
            is ComfyEvent.Progress -> {
                val id = event.promptId ?: return ReduceResult(state)
                val task = findTask(next, id) ?: newRemoteTask(next, id, input.nowMs)
                next = touchTask(
                    next,
                    task.copy(
                        state = TaskState.Running,
                        progress = TaskProgress(event.value, event.max, event.node),
                        lastProgressAtMs = input.nowMs,
                        updatedAtMs = input.nowMs,
                        silenceBudgetMs = task.silenceBudgetMs ?: config.progressSilenceMs,
                    ),
                    effects,
                )
            }

            is ComfyEvent.ExecutionStart -> {
                val task = findTask(next, event.promptId) ?: newRemoteTask(next, event.promptId, input.nowMs)
                next = touchTask(
                    next,
                    task.copy(
                        state = TaskState.Running,
                        lastProgressAtMs = input.nowMs,
                        updatedAtMs = input.nowMs,
                        silenceBudgetMs = task.silenceBudgetMs ?: config.progressSilenceMs,
                    ),
                    effects,
                )
            }

            is ComfyEvent.Executing -> {
                val id = event.promptId ?: return ReduceResult(state)
                val task = findTask(next, id) ?: return ReduceResult(state)
                // executing 也是一次"活着"的信号
                val nodeId = event.node
                val executed = if (nodeId == null || task.executedNodes.size >= MAX_EXECUTED_NODES) {
                    task.executedNodes
                } else {
                    task.executedNodes + nodeId
                }
                next = touchTask(
                    next,
                    task.copy(
                        lastProgressAtMs = input.nowMs,
                        updatedAtMs = input.nowMs,
                        executedNodes = executed,
                    ),
                    effects,
                )
                // node == null 是 ComfyUI 的"该 prompt 已执行完"信号。它仍然只是提示，
                // 但必须立刻用 /history 复核，否则完成态只能等静默看门狗（默认 2 分钟），
                // 还会把"执行完了"误判成"连接卡住"而白重连一次。
                if (event.node == null) {
                    val verify = beginVerification(next, id, input.nowMs)
                    next = verify.state
                    effects += verify.effects
                }
            }

            is ComfyEvent.Executed -> {
                val task = findTask(next, event.promptId) ?: newRemoteTask(next, event.promptId, input.nowMs)
                val produced = event.output?.let { output ->
                    HistoryEntry(outputs = mapOf((event.node ?: "unknown") to output)).extractOutputs()
                }.orEmpty()
                next = touchTask(
                    next,
                    task.copy(
                        state = TaskState.Running,
                        outputs = (task.outputs + produced).distinctBy { it.filename to it.nodeId },
                        updatedAtMs = input.nowMs,
                    ),
                    effects,
                )
            }

            is ComfyEvent.ExecutionError -> {
                val id = event.promptId ?: return ReduceResult(state)
                val task = findTask(next, id) ?: newRemoteTask(next, id, input.nowMs)
                next = touchTask(
                    next,
                    task.copy(
                        state = TaskState.Failed(event.message),
                        error = event.message,
                        updatedAtMs = input.nowMs,
                    ),
                    effects,
                ).let { failed ->
                    failed.copy(
                        preview = failed.preview?.takeIf { it.promptId != null && it.promptId != id },
                    )
                }
                effects += SessionEffect.Notify(UserNotice.TaskFailed(id, event.message))
            }

            is ComfyEvent.ExecutionInterrupted -> {
                val id = event.promptId ?: return ReduceResult(state)
                val task = findTask(next, id) ?: return ReduceResult(state)
                next = touchTask(next, task.copy(state = TaskState.Cancelled, updatedAtMs = input.nowMs), effects)
                    .let { cancelled ->
                        cancelled.copy(
                            preview = cancelled.preview?.takeIf { it.promptId != null && it.promptId != id },
                        )
                    }
            }

            // WebSocket 只是提示：status / cached / 预览帧不改变任务真相
            is ComfyEvent.AnimPreviewStart -> {
                // VHS 先声明接下来会发 length 帧：重置缓冲，收齐后替换掉旧动画
                next = state.copy(
                    previewBurst = com.comfypilot.core.event.PreviewBurst(
                        length = event.length,
                        rate = event.rate,
                        nodeId = event.nodeId,
                    ),
                    previewFrames = emptyList(),
                )
            }

            is ComfyEvent.Status, is ComfyEvent.ExecutionCached,
            is ComfyEvent.BinaryPreview,
            -> Unit

            // Crystools 的监控数据也走同一条 socket（它的 HTTP 路由在多数服务器上是 404）。
            // 只更新监控采样；不是监控帧、解析失败或旧租约的帧都由 parser/守卫安静忽略。
            is ComfyEvent.Unknown -> {
                MonitorSample.parse(input.text, input.nowMs)?.let { sample ->
                    next = state.copy(monitor = sample)
                }
            }
        }

        return ReduceResult(next, effects)
    }

    /**
     * 采样预览是"活着"的额外信号：只更新内存里的任务与预览帧，不落库
     * （预览每秒可能好几帧，写存储在手机上不划算）。
     */
    private fun onSocketBinary(state: SessionState, input: SessionInput.SocketBinary): ReduceResult {
        if (!isCurrent(state, input.lease)) return ReduceResult(state)
        val decoded = PreviewDecoder.decode(input.bytes) ?: return ReduceResult(state)
        val promptId = decoded.promptId ?: state.activeTasks.firstOrNull()?.promptId ?: return ReduceResult(state)
        val task = findTask(state, promptId) ?: return ReduceResult(state)
        if (task.isTerminal) return ReduceResult(state)

        val burst = state.previewBurst
        if (burst != null) {
            // VHS 连续发帧。为了不让界面每帧都重新解码整组，这里只在
            // "攒满一轮" 时更新预览动画（seq+1），中间的帧只进缓冲。
            val frames = state.previewFrames + decoded.image
            val trimmed = if (frames.size > burst.length * 2) frames.takeLast(burst.length) else frames
            val lastFrame = PreviewFrame(
                promptId = promptId,
                nodeId = decoded.nodeId ?: burst.nodeId,
                image = decoded.image,
                receivedAtMs = input.nowMs,
            )
            val completeRound = trimmed.size >= burst.length && trimmed.size % burst.length == 0
            val previous = state.previewAnimation
            val animation = if (completeRound) {
                com.comfypilot.core.event.PreviewAnimation(
                    promptId = promptId,
                    nodeId = burst.nodeId,
                    rate = burst.rate,
                    seq = (previous?.seq ?: 0L) + 1,
                    frames = trimmed.takeLast(burst.length),
                )
            } else {
                previous
            }
            return ReduceResult(
                state.copy(
                    previewFrames = trimmed,
                    previewAnimation = animation,
                    preview = lastFrame,
                    tasks = state.tasks + (
                        task.id to task.copy(
                            lastProgressAtMs = input.nowMs,
                            updatedAtMs = input.nowMs,
                        )
                        ),
                ),
            )
        }
        val touched = task.copy(lastProgressAtMs = input.nowMs, updatedAtMs = input.nowMs)
        return ReduceResult(
            state.copy(
                tasks = state.tasks + (touched.id to touched),
                preview = PreviewFrame(
                    promptId = promptId,
                    nodeId = decoded.nodeId,
                    image = decoded.image,
                    receivedAtMs = input.nowMs,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- http

    private fun onHttpResult(state: SessionState, input: SessionInput.HttpResult): ReduceResult {
        if (input.lease.leaseId != state.lease?.leaseId) return ReduceResult(state)

        return when (val tag = input.tag) {
            is RequestTag.History -> onHistoryResult(state, tag, input.outcome, input.nowMs)
            is RequestTag.PollHistory -> onPollHistoryResult(state, tag.promptId, input.outcome, input.nowMs)
            RequestTag.Queue -> onQueueResult(state, input.outcome, input.nowMs)
            is RequestTag.Submit -> onSubmitResult(state, tag, input.outcome, input.nowMs)
            is RequestTag.Interrupt -> ReduceResult(state)
            RequestTag.Catalog -> onCatalogResult(state, input.outcome)
            is RequestTag.WorkflowFile ->
                // 迟到的另一个文件的响应不能覆盖当前选择。
                if (state.workflow.loadingPath == tag.path) {
                    onWorkflowFileResult(state, tag, input.outcome)
                } else {
                    ReduceResult(state)
                }

            is RequestTag.ObjectInfo -> onObjectInfoResult(state, tag, input.outcome)
        }
    }

    // ---------------------------------------------------------------- workflow

    private fun onCatalogRequested(state: SessionState): ReduceResult {
        val lease = state.lease
            ?: return ReduceResult(state.copy(catalog = WorkflowCatalog.Failed("未连接 ComfyUI")))
        return ReduceResult(
            state.copy(catalog = WorkflowCatalog.Loading),
            listOf(SessionEffect.SendHttp(lease, ApiCall.ListUserData(), RequestTag.Catalog)),
        )
    }

    private fun onWorkflowOpenRequested(
        state: SessionState,
        input: SessionInput.WorkflowOpenRequested,
    ): ReduceResult {
        val lease = state.lease
            ?: return ReduceResult(
                state.copy(
                    workflow = WorkflowSelection(error = "未连接 ComfyUI"),
                    // 没打开新文档，但旧文档的开关状态不该留在"打开失败"的页面之外。
                    bypassedNodeIds = emptySet(),
                    enabledNodeIds = emptySet(),
                ),
            )
        return ReduceResult(
            // 换文档就清空单节点开关；它们只属于当前打开的工作流。
            state.copy(
                workflow = WorkflowSelection(loadingPath = input.path),
                bypassedNodeIds = emptySet(),
                enabledNodeIds = emptySet(),
            ),
            listOf(
                SessionEffect.SendHttp(
                    lease,
                    ApiCall.FetchUserData(input.path),
                    RequestTag.WorkflowFile(input.path),
                ),
            ),
        )
    }

    /** 单节点旁路开关只影响会话内存；节点是否真实存在由工作流视图决定。 */
    private fun onNodeBypassChanged(
        state: SessionState,
        input: SessionInput.NodeBypassChanged,
    ): ReduceResult {
        if (state.workflow.document == null) return ReduceResult(state)
        // 两个集合互斥：关闭 = 只进 bypassed；重新启用 = 只进 enabled（覆盖文件里的静音/旁路）。
        val nextBypassed = if (input.bypassed) {
            state.bypassedNodeIds + input.nodeId
        } else {
            state.bypassedNodeIds - input.nodeId
        }
        val nextEnabled = if (input.bypassed) {
            state.enabledNodeIds - input.nodeId
        } else {
            state.enabledNodeIds + input.nodeId
        }
        if (nextBypassed == state.bypassedNodeIds && nextEnabled == state.enabledNodeIds) {
            return ReduceResult(state)
        }
        return ReduceResult(
            state.copy(bypassedNodeIds = nextBypassed, enabledNodeIds = nextEnabled),
        )
    }

    private fun onCatalogResult(state: SessionState, outcome: HttpOutcome): ReduceResult {
        val body = bodyOf(outcome)
            ?: return ReduceResult(state.copy(catalog = WorkflowCatalog.Failed(describeFailure(outcome))))
        return ReduceResult(state.copy(catalog = WorkflowCatalog.Ready(WorkflowCodec.parseCatalog(body))))
    }

    private fun onWorkflowFileResult(
        state: SessionState,
        tag: RequestTag.WorkflowFile,
        outcome: HttpOutcome,
    ): ReduceResult {
        val body = bodyOf(outcome)
            ?: return ReduceResult(state.copy(workflow = WorkflowSelection(error = describeFailure(outcome))))
        return when (val parsed = WorkflowCodec.parseDocument(body)) {
            is WorkflowCodec.Parsed.Bad ->
                ReduceResult(state.copy(workflow = WorkflowSelection(error = parsed.reason)))

            is WorkflowCodec.Parsed.Ok -> {
                val document = WorkflowDocument(
                    path = tag.path,
                    name = workflowNameOf(tag.path),
                    raw = body,
                    format = parsed.format,
                    nodeClasses = parsed.nodeClasses,
                )
                // 只为还没取过定义的类发请求：同一台服务器上节点定义是稳定的。
                val missing = parsed.nodeClasses
                    .filter { it !in state.nodeInfo && it !in state.nodeInfoRequested }
                    .take(MAX_OBJECT_INFO_REQUESTS)
                val effects = state.lease?.let { lease ->
                    missing.map {
                        SessionEffect.SendHttp(lease, ApiCall.FetchObjectInfo(it), RequestTag.ObjectInfo(it))
                    }
                }.orEmpty()
                ReduceResult(
                    state.copy(
                        workflow = WorkflowSelection(document = document),
                        nodeInfoRequested = state.nodeInfoRequested + missing,
                    ),
                    effects,
                )
            }
        }
    }

    private fun onObjectInfoResult(
        state: SessionState,
        tag: RequestTag.ObjectInfo,
        outcome: HttpOutcome,
    ): ReduceResult {
        val body = bodyOf(outcome)
        if (body == null) {
            // 请求失败（网络抖动/502）：把类从"已请求"里移除，下次打开工作流或提交时会重试。
            // 以前这里直接 return，类永远停在 requested 里，界面上就成了
            // "不可提交：正在加载节点定义（还有 N 个类）"，而且怎么点都不会再请求。
            return ReduceResult(state.copy(nodeInfoRequested = state.nodeInfoRequested - tag.nodeClass))
        }
        // 服务端没有这个类时返回 {}（多半缺自定义节点）：记成 missing，别装成能调参。
        val definition = WorkflowCodec.parseNodeDefinition(body, tag.nodeClass)
            ?: NodeDefinition(className = tag.nodeClass, displayName = tag.nodeClass, missing = true)
        return ReduceResult(state.copy(nodeInfo = state.nodeInfo + (tag.nodeClass to definition)))
    }

    private fun bodyOf(outcome: HttpOutcome): String? = when (outcome) {
        is HttpOutcome.Failed -> null
        is HttpOutcome.Received -> outcome.body?.takeIf { outcome.status in 200..299 }
    }

    private fun describeFailure(outcome: HttpOutcome): String = when (outcome) {
        is HttpOutcome.Failed -> outcome.message
        is HttpOutcome.Received ->
            if (outcome.status in 200..299) "服务器返回空响应" else "服务器返回 ${outcome.status}"
    }

    private fun onHistoryResult(
        state: SessionState,
        tag: RequestTag.History,
        outcome: HttpOutcome,
        now: Long,
    ): ReduceResult {
        val phase = state.phase as? SessionPhase.Recovering ?: return ReduceResult(state)
        val slot = phase.pending[tag.promptId] ?: return ReduceResult(state)

        val probe = toProbe(outcome, tag.promptId)
        val updated = slot.copy(
            attempts = slot.attempts + 1,
            probe = probe,
            lastError = (probe as? HistoryProbe.Unreadable)?.reason,
        )
        val withSlot = state.copy(
            phase = phase.copy(pending = phase.pending + (tag.promptId to updated)),
        )
        return resolveSlot(withSlot, tag.promptId, now)
    }

    private fun onQueueResult(state: SessionState, outcome: HttpOutcome, now: Long): ReduceResult {
        val phase = state.phase as? SessionPhase.Recovering ?: return ReduceResult(state)
        val snapshot = readQueue(outcome)
        var next = state.copy(
            queue = snapshot ?: state.queue,
            phase = phase.copy(queueSeen = snapshot != null, queueRequestedAtMs = now),
        )
        if (snapshot == null) return ReduceResult(next)

        // 快照本身就能给已有 /history 结果的槽位下结论；这里必须把 effects 一起带出去，
        // 否则"凭 /queue 判定丢失"会静默丢弃 Persist 与通知。
        val effects = mutableListOf<SessionEffect>()
        val ids = phase.pending.filterValues { it.probe != null }.keys.toList()
        ids.forEach { id ->
            val resolved = resolveSlot(next, id, now)
            next = resolved.state
            effects += resolved.effects
        }
        return ReduceResult(next, effects)
    }

    private fun onSubmitResult(
        state: SessionState,
        tag: RequestTag.Submit,
        outcome: HttpOutcome,
        now: Long,
    ): ReduceResult {
        val draft = state.tasks[tag.draftId] ?: return ReduceResult(state)
        val response = when (outcome) {
            is HttpOutcome.Failed -> null
            is HttpOutcome.Received -> runCatching {
                ApiCodec.decodePromptResponse(outcome.body ?: "{}")
            }.getOrNull()
        }

        val message = when {
            outcome is HttpOutcome.Failed -> "提交失败：${outcome.message}"
            response != null -> response.failureMessage ?: "提交失败：ComfyUI 未返回 prompt_id"
            else -> "提交失败：服务器返回 ${(outcome as? HttpOutcome.Received)?.status ?: 0}，无法解析响应"
        }
        if (response == null || !response.accepted) {
            val failed = draft.copy(state = TaskState.Failed(message), error = message, updatedAtMs = now)
            return ReduceResult(
                state.copy(tasks = state.tasks + (failed.id to failed)),
                listOf(SessionEffect.Persist(failed), SessionEffect.Notify(UserNotice.SubmitRejected(message))),
            )
        }

        val promptId = response.promptId!!
        val queued = draft.copy(
            id = promptId,
            promptId = promptId,
            state = TaskState.Queued,
            updatedAtMs = now,
        )
        return ReduceResult(
            state.copy(tasks = state.tasks - tag.draftId + (promptId to queued)),
            // 草稿换成了正式任务：先把草稿那条持久化删掉，否则重启后它会以
            // "Unknown：应用重启，提交结果未知"回来，每提交一次就多一条。
            listOf(
                SessionEffect.Persist(queued),
                SessionEffect.PersistRemoved(tag.draftId),
                SessionEffect.Notify(UserNotice.SubmitAccepted(promptId)),
            ),
        )
    }

    private fun onSubmit(state: SessionState, input: SessionInput.SubmitRequested): ReduceResult {
        val lease = state.lease
        if (lease == null) {
            return ReduceResult(state, listOf(SessionEffect.Notify(UserNotice.SubmitRejected("当前未连接 ComfyUI"))))
        }
        val graph = runCatching {
            ApiCodec.json.parseToJsonElement(input.workflowJson) as? JsonObject
        }.getOrNull()
        if (graph == null) {
            return ReduceResult(state, listOf(SessionEffect.Notify(UserNotice.SubmitRejected("工作流 JSON 无效"))))
        }

        val draftId = "draft-${state.nextDraftId}"
        val draft = PromptTask(
            id = draftId,
            promptId = null,
            displayName = input.workflowName?.trim()?.takeIf { it.isNotEmpty() },
            workflowPath = input.workflowPath?.takeIf { it.isNotBlank() },
            state = TaskState.Submitting,
            createdAtMs = input.nowMs,
            updatedAtMs = input.nowMs,
        )
        return ReduceResult(
            state.copy(
                tasks = state.tasks + (draftId to draft),
                nextDraftId = state.nextDraftId + 1,
                preview = null,
            ),
            listOf(
                SessionEffect.Persist(draft),
                SessionEffect.SendHttp(
                    lease = lease,
                    // extra_data.client_id 必须带上：VHS 的动图预览按它定向推送，
                    // 只放在 body.client_id 里服务端不会写进 prompt 的 extra_data，
                    // 表现就是"声明收到了，但 37 帧一张都收不到"。
                    call = ApiCall.SubmitPrompt(
                        PromptRequestBody(
                            graph,
                            lease.clientId,
                            kotlinx.serialization.json.buildJsonObject {
                                put("client_id", kotlinx.serialization.json.JsonPrimitive(lease.clientId))
                                // 网页端提交时带的三个字段：VHS 的动图预览靠 preview_method 判定，
                                // comfy_usage_source 让服务端知道这是前端提交。缺了就只发普通单帧。
                                put(
                                    "comfy_usage_source",
                                    kotlinx.serialization.json.JsonPrimitive("comfyui-frontend"),
                                )
                                put(
                                    "preview_method",
                                    kotlinx.serialization.json.JsonPrimitive("auto"),
                                )
                                // VHS 的开关不在 prompt 里，而在 workflow.extra：
                                // server 端读 prompt 的 extra_data.extra_pnginfo.workflow.extra。
                                put(
                                    "extra_pnginfo",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "workflow",
                                            kotlinx.serialization.json.buildJsonObject {
                                                put(
                                                    "extra",
                                                    kotlinx.serialization.json.buildJsonObject {
                                                        put(
                                                            "VHS_latentpreview",
                                                            kotlinx.serialization.json.JsonPrimitive(true),
                                                        )
                                                        put(
                                                            "VHS_latentpreviewrate",
                                                            kotlinx.serialization.json.JsonPrimitive(8),
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        ),
                        input.workflowJson,
                    ),
                    tag = RequestTag.Submit(draftId, input.workflowJson),
                ),
            ),
        )
    }

    private fun onCancel(state: SessionState, input: SessionInput.CancelRequested): ReduceResult {
        val lease = state.lease ?: return ReduceResult(state)
        val task = findTask(state, input.promptId) ?: return ReduceResult(state)
        val cancelled = task.copy(state = TaskState.Cancelled, updatedAtMs = input.nowMs)
        return ReduceResult(
            state.copy(
                tasks = state.tasks + (task.id to cancelled),
                preview = state.preview?.takeIf {
                    it.promptId != null && it.promptId != input.promptId
                },
            ),
            listOf(
                SessionEffect.Persist(cancelled),
                SessionEffect.SendHttp(lease, ApiCall.Interrupt(input.promptId), RequestTag.Interrupt(input.promptId)),
            ),
        )
    }

    // ---------------------------------------------------------------- tick

    private fun onTick(state: SessionState, now: Long): ReduceResult {
        return when (val phase = state.phase) {
            is SessionPhase.Idle -> ReduceResult(state)

            is SessionPhase.Connecting -> {
                if (now - phase.startedAtMs > config.connectTimeoutMs) {
                    onSocketLost(
                        state,
                        phase.lease,
                        now,
                        "连接超时",
                        ReconnectCause.CONNECT_TIMEOUT,
                    )
                } else {
                    ReduceResult(state)
                }
            }

            is SessionPhase.Reconnecting -> {
                if (phase.nextAttemptAtMs > now) {
                    ReduceResult(state)
                } else {
                    openLease(state, now, state.generation, phase.attempt + 1)
                }
            }

            is SessionPhase.Recovering -> tickRecovering(state, phase, now)

            is SessionPhase.Connected -> {
                val polled = tickPollHistory(state, now)
                if (polled.effects.isNotEmpty()) return polled
                tickWatchdog(polled.state, now)
            }
        }
    }

    private fun tickRecovering(
        state: SessionState,
        phase: SessionPhase.Recovering,
        now: Long,
    ): ReduceResult {
        val effects = mutableListOf<SessionEffect>()
        var next = state

        // /queue 没拿到就周期性重试，否则无法区分"进行中"和"丢失"
        if (!phase.queueSeen && now - phase.queueRequestedAtMs > QUEUE_RETRY_MS) {
            effects += SessionEffect.SendHttp(phase.lease, ApiCall.FetchQueue, RequestTag.Queue)
            next = next.copy(phase = phase.copy(queueRequestedAtMs = now))
        }

        val due = (next.phase as? SessionPhase.Recovering)?.pending.orEmpty()
            .values
            .filter { it.nextAttemptAtMs != null && it.nextAttemptAtMs <= now }

        due.forEach { slot ->
            effects += SessionEffect.SendHttp(
                phase.lease,
                ApiCall.FetchHistory(slot.promptId),
                RequestTag.History(slot.promptId),
            )
            next = withSlot(next, slot.promptId) {
                it.copy(nextAttemptAtMs = null)
            }
        }

        // 硬超时：即使一直"可读但无结论"，也不能无限卡住
        val hardDeadline = RECONCILE_HARD_TIMEOUT_MS
        val stale = (next.phase as? SessionPhase.Recovering)?.pending.orEmpty()
            .values
            .filter { now - it.startedAtMs > hardDeadline }
        stale.forEach { slot ->
            val local = mutableListOf<SessionEffect>()
            val resolved = giveUp(next, slot.promptId, "对账超过 ${hardDeadline / 1000}s 仍无结论", now, local)
            next = resolved.state
            effects += local
        }

        if (next === state && effects.isEmpty()) return ReduceResult(state)
        return ReduceResult(next, effects)
    }

    /**
     * 回到前台：把非终态任务全部丢回对账槽，走和断线重连一样的 `/history` 复核。
     *
     * 不关 socket、不递增 generation，避免因为"看起来没动静"打断正在跑的任务。
     */
    private fun onReconcileRequested(
        state: SessionState,
        input: SessionInput.ReconcileRequested,
    ): ReduceResult {
        if (state.lease == null) return ReduceResult(state)
        val phase = state.phase
        if (phase !is SessionPhase.Connected && phase !is SessionPhase.Recovering) {
            return ReduceResult(state)
        }
        val ids = state.activeTasks.mapNotNull { it.promptId }
        if (ids.isEmpty()) return ReduceResult(state)

        var result = ReduceResult(state)
        ids.forEach { id ->
            val step = beginVerification(result.state, id, input.nowMs)
            result = ReduceResult(step.state, result.effects + step.effects)
        }
        return result
    }

    /**
     * 运行中任务的周期兜底：每 POLL_HISTORY_MS 拉一次 /history。
     *
     * 为什么必须要有：应用切后台时 socket 会被系统反复掐断（现场日志里 35 秒一次
     * "Software caused connection abort"），而自定义采样节点又不发步进进度。
     * 只靠 socket 的话，任务跑完了界面也不知道，只能一直停在"执行中"。
     */
    private fun tickPollHistory(state: SessionState, now: Long): ReduceResult {
        val phase = state.phase as? SessionPhase.Connected ?: return ReduceResult(state)
        val targets = state.tasks.values.filter { task ->
            task.state is TaskState.Running &&
                task.promptId != null &&
                now - (task.lastPollAtMs ?: task.updatedAtMs) >= config.historyPollMs
        }
        if (targets.isEmpty()) return ReduceResult(state)

        var next = state
        val effects = mutableListOf<SessionEffect>()
        targets.forEach { task ->
            val promptId = task.promptId ?: return@forEach
            val updated = task.copy(lastPollAtMs = now)
            next = next.copy(tasks = next.tasks + (updated.id to updated))
            effects += SessionEffect.SendHttp(
                phase.lease,
                ApiCall.FetchHistory(promptId),
                RequestTag.PollHistory(promptId),
            )
        }
        return ReduceResult(next, effects)
    }

    /** 兜底轮询的结果：只认"确定收尾"的三态，其余继续等。 */
    private fun onPollHistoryResult(
        state: SessionState,
        promptId: String,
        outcome: HttpOutcome,
        now: Long,
    ): ReduceResult {
        val task = findTask(state, promptId) ?: return ReduceResult(state)
        if (task.isTerminal) return ReduceResult(state)
        val probe = toProbe(outcome, promptId)
        if (probe !is HistoryProbe.Readable) return ReduceResult(state)
        val entry = probe.entry ?: return ReduceResult(state)
        val effects = mutableListOf<SessionEffect>()
        return when {
            entry.isSuccess -> finishSlot(
                state,
                promptId,
                TaskState.Completed,
                entry.extractOutputs(),
                now,
                effects,
            )

            entry.isError -> finishSlot(
                state,
                promptId,
                TaskState.Failed(entry.errorMessages.joinToString("；").ifBlank { "执行失败" }),
                null,
                now,
                effects,
            )

            entry.isInterrupted -> finishSlot(state, promptId, TaskState.Cancelled, null, now, effects)

            else -> ReduceResult(state)
        }
    }

    private fun tickWatchdog(state: SessionState, now: Long): ReduceResult {
        val overdue = state.tasks.values.filter { task ->
        // 排队中的任务也可能因为 WebSocket 半死而永远等不到事件：
        // 静默超过 QUEUED_SILENCE_MS 先拿 /history 复核一次，不直接判死。
        val staleQueued = state.tasks.values
            .filter { task ->
                task.state is TaskState.Queued &&
                    task.promptId != null &&
                    now - task.updatedAtMs > QUEUED_SILENCE_MS
            }
            .mapNotNull { it.promptId }
        if (staleQueued.isNotEmpty()) {
            var result = ReduceResult(state)
            staleQueued.forEach { id ->
                val step = beginVerification(result.state, id, now)
                result = ReduceResult(step.state, result.effects + step.effects)
            }
            if (result.effects.isNotEmpty()) return result
        }

            task.state is TaskState.Running &&
                now - (task.lastProgressAtMs ?: task.updatedAtMs) >
                (task.silenceBudgetMs ?: config.progressSilenceMs)
        }
        if (overdue.isEmpty()) return ReduceResult(state)

        // 视频大模型单步可能就要几分钟，而且模型加载/offload 阶段一条进度都没有。
        // 先翻倍静默预算（最多到 progressSilenceMaxMs），别急着断开重连：
        // 重连会让界面上的进度归零，长任务就永远停在 0%。
        val extendable = overdue.filter { task ->
            (task.silenceBudgetMs ?: config.progressSilenceMs) < config.progressSilenceMaxMs
        }
        val hopeless = overdue.filter { task ->
            (task.silenceBudgetMs ?: config.progressSilenceMs) >= config.progressSilenceMaxMs
        }
        if (hopeless.isEmpty()) {
            var next = state
            extendable.forEach { task ->
                val capped = ((task.silenceBudgetMs ?: config.progressSilenceMs) * 2)
                    .coerceAtMost(config.progressSilenceMaxMs)
                next = next.copy(
                    tasks = next.tasks + (
                        task.id to task.copy(silenceBudgetMs = capped, lastProgressAtMs = now)
                        ),
                )
            }
            return ReduceResult(next)
        }

        val lease = state.lease ?: return ReduceResult(state)
        val ids = hopeless.mapNotNull { it.promptId }
        val delay = config.backoffMs(1, jitter)
        return ReduceResult(
            state.copy(
                phase = SessionPhase.Reconnecting(
                    attempt = 1,
                    nextAttemptAtMs = now + delay,
                    cause = ReconnectCause.PROGRESS_TIMEOUT,
                    lastLeaseId = lease.leaseId,
                    startedAtMs = now,
                ),
                queue = null,
                monitor = null,
            ),
            listOf(
                SessionEffect.CloseSocket(lease.leaseId, "进度静默超时"),
                SessionEffect.Notify(UserNotice.ProgressTimeout(ids)),
            ),
        )
    }

    /**
     * WebSocket 说"这个 prompt 完了"——只是提示。立即在**同一个租约**上取 /history 复核：
     * 不关 socket、不递增 generation，只在复核期间把阶段标成 Recovering（"对齐中"）。
     */
    private fun beginVerification(state: SessionState, promptId: String, now: Long): ReduceResult {
        val lease = state.lease ?: return ReduceResult(state)
        val pending = when (val phase = state.phase) {
            is SessionPhase.Recovering -> {
                if (phase.pending.containsKey(promptId)) return ReduceResult(state)
                phase.pending
            }

            is SessionPhase.Connected -> emptyMap()
            else -> return ReduceResult(state)
        }
        val slot = ReconcileSlot(promptId = promptId, startedAtMs = now)
        val phase = when (val current = state.phase) {
            is SessionPhase.Recovering -> current.copy(pending = pending + (promptId to slot))
            is SessionPhase.Connected -> SessionPhase.Recovering(
                lease = current.lease,
                pending = mapOf(promptId to slot),
                queueSeen = false,
                totalCount = 1,
                // 让下一个 tick 立刻补一次 /queue：history 若"可读但无输出"，需要它区分进行中/丢失
                queueRequestedAtMs = now - QUEUE_RETRY_MS,
            )

            else -> return ReduceResult(state)
        }
        return ReduceResult(
            state.copy(phase = phase),
            listOf(
                SessionEffect.SendHttp(
                    lease,
                    ApiCall.FetchHistory(promptId),
                    RequestTag.History(promptId),
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- reconcile plumbing

    private fun toProbe(outcome: HttpOutcome, promptId: String): HistoryProbe = when (outcome) {
        is HttpOutcome.Failed -> HistoryProbe.Unreadable(outcome.message)
        is HttpOutcome.Received -> when {
            outcome.status !in 200..299 -> HistoryProbe.Unreadable("HTTP ${outcome.status}")
            else -> runCatching {
                HistoryProbe.Readable(ApiCodec.decodeHistory(outcome.body ?: "{}", promptId))
            }.getOrElse { HistoryProbe.Unreadable("响应解析失败：${it.message}") }
        }
    }

    private fun readQueue(outcome: HttpOutcome): QueueSnapshot? = when (outcome) {
        is HttpOutcome.Failed -> null
        is HttpOutcome.Received -> when {
            outcome.status !in 200..299 -> null
            else -> runCatching { ApiCodec.decodeQueue(outcome.body ?: "{}") }.getOrNull()
        }
    }

    private fun resolveSlot(state: SessionState, promptId: String, now: Long): ReduceResult {
        val phase = state.phase as? SessionPhase.Recovering ?: return ReduceResult(state)
        val slot = phase.pending[promptId] ?: return ReduceResult(state)
        val probe = slot.probe ?: return ReduceResult(state)

        val budget = ReconcileBudget(
            maxAttempts = config.reconcileMaxAttempts,
            windowMs = config.reconcileWindowMs,
            retryBaseMs = config.reconcileRetryBaseMs,
        )
        val elapsed = now - slot.startedAtMs
        var outcome = ReconcilePolicy.decide(probe, state.queue, promptId, slot.attempts, elapsed, budget)
        if (outcome is ReconcileOutcome.Retry && outcome.delayMs == 0L && elapsed >= budget.windowMs) {
            outcome = ReconcileOutcome.GiveUp("等待 /queue 超时")
        }

        val effects = mutableListOf<SessionEffect>()
        return when (outcome) {
            is ReconcileOutcome.Completed -> finishSlot(
                state, promptId, TaskState.Completed, outcome.outputs, now, effects,
            )

            is ReconcileOutcome.Failed -> {
                effects += SessionEffect.Notify(UserNotice.TaskFailed(promptId, outcome.message))
                finishSlot(state, promptId, TaskState.Failed(outcome.message), null, now, effects)
            }

            ReconcileOutcome.Interrupted -> finishSlot(
                state, promptId, TaskState.Cancelled, null, now, effects,
            )

            is ReconcileOutcome.StillActive -> {
                val task = findTask(state, promptId)
                if (task != null) {
                    val budgetAfter = ((task.silenceBudgetMs ?: config.progressSilenceMs) * 2)
                        .coerceAtMost(config.progressSilenceMaxMs)
                    val updated = task.copy(
                        state = if (outcome.running) TaskState.Running else TaskState.Queued,
                        silenceBudgetMs = budgetAfter,
                        lastProgressAtMs = now,
                        updatedAtMs = now,
                    )
                    effects += SessionEffect.Persist(updated)
                    ReduceResult(
                        removeSlot(state.copy(tasks = state.tasks + (updated.id to updated)), promptId, now, effects),
                        effects,
                    )
                } else {
                    ReduceResult(removeSlot(state, promptId, now, effects), effects)
                }
            }

            ReconcileOutcome.Lost -> {
                effects += SessionEffect.Notify(
                    UserNotice.TaskLost(promptId, "任务不在 ComfyUI 队列与历史中（可能服务重启或队列被清）"),
                )
                finishSlot(
                    state,
                    promptId,
                    TaskState.Unknown("任务已从服务端消失"),
                    null,
                    now,
                    effects,
                )
            }

            is ReconcileOutcome.Retry -> {
                val retryAt = now + outcome.delayMs.coerceAtLeast(MIN_RETRY_MS)
                val updatedSlot = slot.copy(nextAttemptAtMs = retryAt, lastError = outcome.reason)
                ReduceResult(state.copy(phase = phase.copy(pending = phase.pending + (promptId to updatedSlot))))
            }

            is ReconcileOutcome.GiveUp -> giveUp(state, promptId, outcome.reason, now, effects)
        }
    }

    private fun finishSlot(
        state: SessionState,
        promptId: String,
        taskState: TaskState,
        outputs: List<TaskOutput>?,
        now: Long,
        effects: MutableList<SessionEffect>,
    ): ReduceResult {
        val existing = findTask(state, promptId) ?: newRemoteTask(state, promptId, now)
        val updated = existing.copy(
            state = taskState,
            outputs = outputs ?: existing.outputs,
            error = (taskState as? TaskState.Failed)?.message,
            updatedAtMs = now,
        )
        effects += SessionEffect.Persist(updated)
        var next = state.copy(
            tasks = state.tasks + (updated.id to updated),
            // 完成/失败后预览帧就过期了；别的任务还在跑时保留它。
            preview = state.preview?.takeIf { it.promptId != null && it.promptId != promptId },
            previewAnimation = state.previewAnimation?.takeIf { it.promptId != promptId },
            previewBurst = null,
            previewFrames = emptyList(),
        )
        next = removeSlot(next, promptId, now, effects)
        return ReduceResult(next, effects)
    }

    private fun giveUp(
        state: SessionState,
        promptId: String,
        reason: String,
        now: Long,
        effects: MutableList<SessionEffect>,
    ): ReduceResult {
        effects += SessionEffect.Notify(UserNotice.ReconcileGaveUp(listOf(promptId)))
        return finishSlot(state, promptId, TaskState.Unknown(reason), null, now, effects)
    }

    private fun removeSlot(
        state: SessionState,
        promptId: String,
        now: Long,
        effects: MutableList<SessionEffect>,
    ): SessionState {
        val phase = state.phase as? SessionPhase.Recovering ?: return state
        val remaining = phase.pending - promptId
        if (remaining.isNotEmpty()) {
            return state.copy(phase = phase.copy(pending = remaining))
        }
        effects += SessionEffect.Notify(UserNotice.Connected(state.generation))
        return state.copy(phase = SessionPhase.Connected(phase.lease, now))
    }

    private fun withSlot(
        state: SessionState,
        promptId: String,
        transform: (ReconcileSlot) -> ReconcileSlot,
    ): SessionState {
        val phase = state.phase as? SessionPhase.Recovering ?: return state
        val slot = phase.pending[promptId] ?: return state
        return state.copy(phase = phase.copy(pending = phase.pending + (promptId to transform(slot))))
    }

    // ---------------------------------------------------------------- task helpers

    private fun findTask(state: SessionState, promptId: String): PromptTask? =
        state.tasks.values.firstOrNull { it.promptId == promptId }

    private fun newRemoteTask(now: Long, promptId: String): PromptTask = PromptTask(
        id = promptId,
        promptId = promptId,
        displayName = null,
        state = TaskState.Queued,
        createdAtMs = now,
        updatedAtMs = now,
    )

    private fun newRemoteTask(state: SessionState, promptId: String, now: Long): PromptTask =
        state.tasks[promptId] ?: newRemoteTask(now, promptId)

    private fun touchTask(
        state: SessionState,
        task: PromptTask,
        effects: MutableList<SessionEffect>,
    ): SessionState {
        effects += SessionEffect.Persist(task)
        return state.copy(tasks = state.tasks + (task.id to task))
    }

    // ---------------------------------------------------------------- lease helpers

    private fun isCurrent(state: SessionState, lease: ConnectionLease): Boolean =
        state.lease?.leaseId == lease.leaseId

    private fun closeCurrent(state: SessionState, reason: String): List<SessionEffect> =
        state.lease?.let { listOf(SessionEffect.CloseSocket(it.leaseId, reason)) } ?: emptyList()

    private fun openLease(
        state: SessionState,
        now: Long,
        generation: Long,
        attempt: Int,
    ): ReduceResult {
        val leaseId = state.nextLeaseId
        // sid == clientId：重连必须复用同一个，否则服务端按原会话定向推送的
        // progress / 预览帧都收不到（表现为切后台回来进度和画面一起冻住）。
        val clientId = config.clientId.ifBlank { Ids.clientId() }
        DiagnosticTrace.emit("lease gen$generation clientId=${clientId.take(8)}")
        val lease = ConnectionLease(
            generation = generation,
            leaseId = leaseId,
            clientId = clientId,
            baseUrl = config.baseUrl,
            wsUrl = wsUrlFor(config.baseUrl, clientId),
        )
        val next = state.copy(
            generation = generation,
            phase = SessionPhase.Connecting(lease, attempt, now),
            nextLeaseId = leaseId + 1,
            queue = null,
            // preview 保留：重连期间旧帧仍然是"最后一次看到的画面"，
            // 由界面按 receivedAtMs 标注陈旧，而不是直接消失。
            monitor = null,
        )
        return ReduceResult(next, listOf(SessionEffect.OpenSocket(lease)))
    }

    private companion object {
        const val QUEUE_RETRY_MS = 3_000L

/** 排队中的任务静默多久就主动对账一次。 */
const val QUEUED_SILENCE_MS = 10_000L

    /** 与网页端一致的客户端能力声明（抓包原文）。 */
    const val FEATURE_FLAGS_JSON =
        "{\"type\":\"feature_flags\",\"data\":{\"supports_preview_metadata\":true," +
            "\"supports_manager_v4_ui\":true,\"supports_progress_text_metadata\":true}}"

    /** 运行中任务的 /history 兜底间隔：socket 只是加速通道，不能当唯一真相。 */
    const val POLL_HISTORY_MS = 20_000L

    /** executedNodes 上限，避免超长工作流把任务对象撑大。 */
    const val MAX_EXECUTED_NODES = 512
        const val MIN_RETRY_MS = 500L
        const val RECONCILE_HARD_TIMEOUT_MS = 60_000L
    }
}
