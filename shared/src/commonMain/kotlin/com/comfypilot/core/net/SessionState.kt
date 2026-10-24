package com.comfypilot.core.net

import com.comfypilot.core.api.QueueSnapshot
import com.comfypilot.core.reconcile.HistoryProbe
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskId
import com.comfypilot.core.workflow.NodeDefinition
import com.comfypilot.core.workflow.WorkflowCatalog
import com.comfypilot.core.workflow.WorkflowSelection

/**
 * 连接状态是枚举化的：没有任何 isConnected / isReconnecting 布尔位。
 * 网络纪元 (generation) 与连接租约 (lease) 都挂在状态上，UI 读到的永远是一致快照。
 */
data class SessionState(
    val generation: Long = 0,
    val network: NetworkInfo = NetworkInfo.UNKNOWN,
    /** 最近一次"在线"的网络类别；用来区分"同类恢复"与"WiFi <-> 蜂窝切换"。 */
    val lastOnlineTransport: NetworkTransport = NetworkTransport.NONE,
    val phase: SessionPhase = SessionPhase.Idle("尚未启动"),
    val tasks: Map<TaskId, PromptTask> = emptyMap(),
    val queue: QueueSnapshot? = null,
    val nextLeaseId: Long = 1,
    val nextDraftId: Long = 1,
    /** 服务器上的工作流列表。 */
    val catalog: WorkflowCatalog = WorkflowCatalog.Idle,
    /** 当前打开的工作流（原文 + 格式识别）。 */
    val workflow: WorkflowSelection = WorkflowSelection(),
    /**
     * 当前工作流里被用户逐个关闭（旁路）的节点。
     *
     * 只保存在会话内存：切换工作流时清空，不写回服务器上的工作流文件。
     */
    val bypassedNodeIds: Set<Int> = emptySet(),
    /**
     * 当前工作流里被用户逐个重新启用的节点。
     *
     * 用于把工作流文件里保存为静音（2）/旁路（4）的节点恢复执行；同样只存在会话内存，
     * 切换工作流时清空。[bypassedNodeIds] 优先。
     */
    val enabledNodeIds: Set<Int> = emptySet(),
    /** 已取到的节点定义，按类名缓存；同一台服务器上可跨工作流复用。 */
    val nodeInfo: Map<String, NodeDefinition> = emptyMap(),
    /** 已发出过请求的类名：成功/失败都算，避免反复打服务端。 */
    val nodeInfoRequested: Set<String> = emptySet(),
    /** 最近一帧采样预览；只在内存里，不落库、不进桥协议状态帧。 */
    val preview: PreviewFrame? = null,
    /**
     * 最近一帧 Crystools 监控采样（CPU/GPU/温度等）。
     *
     * 和预览一样是租约内的硬件数据：断线、换网络类别或新租约都清空，
     * 免得把上一台服务器的读数画在新连接上。
     */
    val monitor: MonitorSample? = null,
) {
    val lease: ConnectionLease? get() = phase.lease()

    val activeTasks: List<PromptTask> get() = tasks.values.filter { it.needsReconcile }

    val connectionLabel: String
        get() = when (phase) {
            is SessionPhase.Idle -> "未连接"
            is SessionPhase.Connecting -> "连接中"
            is SessionPhase.Connected -> "已连接"
            is SessionPhase.Reconnecting -> "重连中"
            is SessionPhase.Recovering -> "对齐中"
        }
}

sealed interface SessionPhase {
    fun lease(): ConnectionLease? = when (this) {
        is Connecting -> lease
        is Connected -> lease
        is Recovering -> lease
        is Idle, is Reconnecting -> null
    }

    data class Idle(val reason: String) : SessionPhase

    data class Connecting(
        val lease: ConnectionLease,
        val attempt: Int,
        val startedAtMs: Long,
    ) : SessionPhase

    data class Connected(
        val lease: ConnectionLease,
        val sinceMs: Long,
    ) : SessionPhase

    data class Reconnecting(
        val attempt: Int,
        val nextAttemptAtMs: Long,
        val cause: ReconnectCause,
        val lastLeaseId: Long?,
        val startedAtMs: Long,
    ) : SessionPhase

    /** 连接已建立，但仍有任务需要 /history + /queue 对账。 */
    data class Recovering(
        val lease: ConnectionLease,
        val pending: Map<String, ReconcileSlot>,
        val queueSeen: Boolean,
        val totalCount: Int,
        val queueRequestedAtMs: Long,
    ) : SessionPhase
}

enum class ReconnectCause {
    NETWORK_LOST,
    NETWORK_SWITCHED,
    SOCKET_CLOSED,
    SOCKET_FAILED,
    CONNECT_TIMEOUT,
    PROGRESS_TIMEOUT,
    USER_REQUEST,
}

data class ReconcileSlot(
    val promptId: String,
    val startedAtMs: Long,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val probe: HistoryProbe? = null,
    val lastError: String? = null,
)

/** 采样预览帧；[promptId] 为空表示旧格式，UI 只能挂到当前在跑的任务上。 */
data class PreviewFrame(
    val promptId: String?,
    val nodeId: String?,
    val image: ByteArray,
    val receivedAtMs: Long,
)
