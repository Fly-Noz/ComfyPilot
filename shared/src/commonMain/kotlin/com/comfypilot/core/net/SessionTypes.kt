package com.comfypilot.core.net

import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskId

sealed interface SessionInput {
    data class Start(val nowMs: Long) : SessionInput

    data class Stop(val nowMs: Long, val reason: String = "用户停止") : SessionInput

    data class NetworkChanged(val network: NetworkInfo, val nowMs: Long) : SessionInput

    data class SocketOpened(val lease: ConnectionLease, val nowMs: Long) : SessionInput

    data class SocketText(val lease: ConnectionLease, val text: String, val nowMs: Long) : SessionInput

    /** ComfyUI 的二进制采样预览帧。 */
    data class SocketBinary(val lease: ConnectionLease, val bytes: ByteArray, val nowMs: Long) : SessionInput

    data class SocketClosed(
        val lease: ConnectionLease,
        val code: Int,
        val reason: String,
        val nowMs: Long,
    ) : SessionInput

    data class SocketFailed(
        val lease: ConnectionLease,
        val message: String,
        val nowMs: Long,
    ) : SessionInput

    data class HttpResult(
        val lease: ConnectionLease,
        val tag: RequestTag,
        val outcome: HttpOutcome,
        val nowMs: Long,
    ) : SessionInput

    data class SubmitRequested(
        val workflowJson: String,
        val nowMs: Long,
        val workflowName: String? = null,
        val workflowPath: String? = null,
    ) : SessionInput

    data class CancelRequested(val promptId: String, val nowMs: Long) : SessionInput

    /** 拉取服务器上的工作流列表（`/userdata?dir=workflows`）。 */
    data object WorkflowCatalogRequested : SessionInput

    /** 打开某个工作流文件；原文与节点定义都从服务器现取。 */
    data class WorkflowOpenRequested(val path: String) : SessionInput

    data class UserReconnect(val nowMs: Long) : SessionInput

    data class Tick(val nowMs: Long) : SessionInput
}

/** 一次 HTTP 调用的语义标签；响应回来时靠它决定如何合入状态。 */
sealed interface RequestTag {
    data class History(val promptId: String) : RequestTag

    data object Queue : RequestTag

    data class Submit(val draftId: String, val workflowJson: String) : RequestTag

    data class Interrupt(val promptId: String) : RequestTag

    data object Catalog : RequestTag

    data class WorkflowFile(val path: String) : RequestTag

    data class ObjectInfo(val nodeClass: String) : RequestTag
}

sealed interface HttpOutcome {
    /** 收到了 HTTP 响应（含 4xx/5xx），body 可能为空。 */
    data class Received(val status: Int, val body: String?) : HttpOutcome

    /** 传输层失败：DNS、断网、超时。 */
    data class Failed(val message: String) : HttpOutcome
}

sealed interface SessionEffect {
    data class OpenSocket(val lease: ConnectionLease) : SessionEffect

    data class CloseSocket(val leaseId: Long, val reason: String) : SessionEffect

    data class SendHttp(val lease: ConnectionLease, val call: ApiCall, val tag: RequestTag) : SessionEffect

    data class Persist(val task: PromptTask) : SessionEffect

    /** 任务已从状态里消失（草稿被正式 promptId 取代）：存储里那条也要删掉。 */
    data class PersistRemoved(val id: TaskId) : SessionEffect

    data class Notify(val notice: UserNotice) : SessionEffect
}

sealed interface UserNotice {
    data class Connected(val generation: Long) : UserNotice

    data class Disconnected(val reason: String) : UserNotice

    data class TaskFailed(val promptId: String, val message: String?) : UserNotice

    data class TaskLost(val promptId: String, val message: String) : UserNotice

    data class ProgressTimeout(val promptIds: List<String>) : UserNotice

    data class ReconcileGaveUp(val promptIds: List<String>) : UserNotice

    data class SubmitRejected(val message: String) : UserNotice

    /** 服务器已接受提交并返回 prompt_id；只有收到这条通知才能显示“已提交”。 */
    data class SubmitAccepted(val promptId: String) : UserNotice
}

data class ReduceResult(
    val state: SessionState,
    val effects: List<SessionEffect> = emptyList(),
)
