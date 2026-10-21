package com.comfypilot.app

import android.content.Context
import com.comfypilot.android.net.AndroidNetworkMonitor
import com.comfypilot.android.service.SystemTimeSource
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.android.transport.OkHttpWebSocketTransport
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.net.UserNotice
import com.comfypilot.core.session.SessionRuntime
import com.comfypilot.core.task.InMemoryTaskStore
import com.comfypilot.core.transport.JitterSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 进程级宿主：连接存活于 Application 作用域，Activity 销毁不影响。
 * 阶段一只做内存态；持久化留给阶段二按平台实现。
 */
object SessionHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runtime: SessionRuntime? = null
    private var monitor: AndroidNetworkMonitor? = null
    private var collector: Job? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _lastNotice = MutableStateFlow<String?>(null)
    val lastNotice: StateFlow<String?> = _lastNotice.asStateFlow()

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL
        private set

    fun connect(context: Context, url: String) {
        stopRuntime()
        baseUrl = url

        monitor = AndroidNetworkMonitor(context.applicationContext) { info: NetworkInfo ->
            runtime?.onNetworkChanged(info)
        }.also { it.start() }

        val created = SessionRuntime(
            config = SessionConfig(baseUrl = url),
            http = OkHttpHttpTransport(),
            sockets = OkHttpWebSocketTransport(),
            store = InMemoryTaskStore(),
            clock = SystemTimeSource,
            jitter = RandomJitter(),
            scope = scope,
            onNotice = { notice -> _lastNotice.value = notice.describe() },
        )
        runtime = created
        created.start()
        created.connect()

        collector = scope.launch {
            created.state.collect { snapshot -> _state.value = snapshot }
        }
        _lastNotice.value = null
    }

    fun disconnect() {
        runtime?.disconnect()
    }

    fun reconnect() {
        runtime?.reconnect()
    }

    fun submit(workflowJson: String) {
        runtime?.submit(workflowJson)
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
}
