package com.comfypilot.app

import android.content.Context
import com.comfypilot.android.net.AndroidNetworkMonitor
import com.comfypilot.android.service.SystemTimeSource
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.android.transport.OkHttpWebSocketTransport
import com.comfypilot.app.task.JsonFileTaskStore
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.session.SessionRuntime
import com.comfypilot.core.task.restoreTasks
import com.comfypilot.core.transport.JitterSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * 进程级宿主：连接存活于 Application 作用域，Activity 销毁不影响。
 * 阶段一只做内存态；持久化留给阶段二按平台实现。
 */
object SessionHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastPhaseLog: String = ""
    private var lastTasksLog: String = ""

    init {
        // 诊断页要能看到 WebSocket 事件与重连原因；共享层只负责发事件，这里负责落点。
        DiagnosticsLog.install()
    }
    private var runtime: SessionRuntime? = null
    private var monitor: AndroidNetworkMonitor? = null
    private var collector: Job? = null
    private var taskStore: JsonFileTaskStore? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _lastNotice = MutableStateFlow<String?>(null)
    val lastNotice: StateFlow<String?> = _lastNotice.asStateFlow()

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL
        private set

    /** 按服务器地址持久化 clientId：冷启动也复用，否则重启后旧任务的进度会断。 */
    private fun clientIdFor(context: Context, url: String): String {
        val prefs = context.getSharedPreferences("client_ids", Context.MODE_PRIVATE)
        val key = url.trim()
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(key, created).apply()
        return created
    }

    private fun describeTasks(state: SessionState): String {
        val active = state.activeTasks
        if (active.isEmpty()) return "none"
        return active.joinToString("; ") { task ->
            val p = task.progress
            val step = if (p == null || p.max <= 0.0) "-" else "${p.value.toInt()}/${p.max.toInt()}"
            "${task.id.take(8)}:${task.state::class.simpleName}:$step"
        }
    }

    private fun describePhase(phase: SessionPhase): String = when (phase) {
        is SessionPhase.Idle -> "idle(${phase.reason})"
        is SessionPhase.Connecting -> "connecting#${phase.attempt}"
        is SessionPhase.Connected -> "connected(gen${phase.lease.generation})"
        is SessionPhase.Reconnecting -> "reconnecting#${phase.attempt}:${phase.cause}"
        is SessionPhase.Recovering -> "recovering(${phase.pending.size})"
    }

    fun connect(context: Context, url: String, progressSilenceMs: Long? = null) {
        stopRuntime()
        baseUrl = url
        _lastNotice.value = null

        // 进程内复用同一个 store，避免每次重连都重新读文件；真正重启后新实例从 filesDir 恢复。
        val store = taskStore ?: JsonFileTaskStore(
            File(context.applicationContext.filesDir, "tasks.json"),
        ).also { taskStore = it }
        val restored = restoreTasks(runBlocking { store.load() })

        val created = SessionRuntime(
            config = SessionConfig(
                baseUrl = url,
                // 同一地址固定复用同一个 clientId：ComfyUI 的 sid 就是它，
                // 重连/重启后换 id 会让旧任务的 progress 与预览帧再也推不过来。
                clientId = clientIdFor(context.applicationContext, url),
                // 仅调试入口可覆盖：真机联调时用来缩短"静默看门狗"的等待
                progressSilenceMs = progressSilenceMs ?: SessionConfig.DEFAULT_PROGRESS_SILENCE_MS,
            ),
            http = OkHttpHttpTransport(),
            sockets = OkHttpWebSocketTransport(),
            store = store,
            clock = SystemTimeSource,
            jitter = RandomJitter(),
            scope = scope,
            initialTasks = restored.tasks,
            nextDraftId = restored.nextDraftId,
            onNotice = { notice -> _lastNotice.value = notice.describe() },
        )
        runtime = created

        collector = scope.launch {
            created.state.collect { snapshot ->
                _state.value = snapshot

                val phase = describePhase(snapshot.phase)
                if (phase != lastPhaseLog) {
                    lastPhaseLog = phase
                    DiagnosticsLog.append("phase $phase")
                }
                // 任务侧也要有痕迹：切后台回来后进度停在原处时，能看出是"没有新事件"
                // 还是"状态机把任务丢了/改成别的状态了"。
                val tasks = describeTasks(snapshot)
                if (tasks != lastTasksLog) {
                    lastTasksLog = tasks
                    DiagnosticsLog.append("tasks $tasks")
                }
            }
        }

        // 必须在 runtime 就绪后再注册：registerDefaultNetworkCallback 会立即回调一次，
        // 否则首帧网络信息会丢，导致第一次 WiFi <-> 蜂窝切换不递增 generation。
        monitor = AndroidNetworkMonitor(context.applicationContext) { info: NetworkInfo ->
            created.onNetworkChanged(info)
        }.also { it.start() }

        created.start()
        created.connect()
    }

    fun disconnect() {
        runtime?.disconnect()
    }

    fun reconnect() {
        runtime?.reconnect()
    }


    /** 回到前台时补一次对账，避免 WebSocket 半死导致任务一直停在排队中。 */
    fun reconcile() {
        runtime?.reconcile()
    }
    fun submit(workflowJson: String, workflowName: String? = null, workflowPath: String? = null) {
        runtime?.submit(workflowJson, workflowName, workflowPath)
    }

    fun refreshWorkflows() {
        runtime?.refreshWorkflows()
    }

    fun openWorkflow(path: String) {
        runtime?.openWorkflow(path)
    }

    /** 关闭/重新启用当前工作流里的单个节点；只存在会话内存，不写回服务器文件。 */
    fun setNodeBypass(nodeId: Int, bypassed: Boolean) {
        runtime?.setNodeBypass(nodeId, bypassed)
    }

    fun cancel(promptId: String) {
        runtime?.cancel(promptId)
    }

    fun shutdown() {
        stopRuntime()
        _state.value = SessionState()
    }

    private fun stopRuntime() {
        collector?.cancel()
        monitor?.stop()
        monitor = null
        runtime?.shutdown()
        runtime = null
        collector = null
        _state.value = SessionState()
    }

    private class RandomJitter : JitterSource {
        private val random = Random(System.currentTimeMillis())
        override fun nextSigned(): Double = random.nextDouble() * 2.0 - 1.0
    }

    const val DEFAULT_BASE_URL = "http://10.0.2.2:8188"
}

fun UserNotice.describe(): String = when (this) {
    is UserNotice.Connected -> "已连接（generation $generation）"
    is UserNotice.Disconnected -> "连接断开：$reason"
    is UserNotice.TaskFailed -> "任务 $promptId 失败：${message ?: "未知原因"}"
    is UserNotice.TaskLost -> "任务 $promptId 状态未知：$message"
    is UserNotice.ProgressTimeout -> "长时间无进度，正在重连对齐：${promptIds.joinToString()}"
    is UserNotice.ReconcileGaveUp -> "对账失败：${promptIds.joinToString()}"
    is UserNotice.SubmitRejected -> "提交被拒绝：$message"
    is UserNotice.SubmitAccepted -> "已提交：$promptId"
}
