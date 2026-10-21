package com.comfypilot.core.host

import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.session.SessionRuntime
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskId
import com.comfypilot.core.task.TaskStore
import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpResponse
import com.comfypilot.core.transport.HttpTransport
import com.comfypilot.core.transport.JitterSource
import com.comfypilot.core.transport.TimeSource
import com.comfypilot.core.transport.WebSocketHandle
import com.comfypilot.core.transport.WebSocketListener
import com.comfypilot.core.transport.WebSocketTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 平台无关的宿主：把 [SessionRuntime] 接到"宿主执行 IO"的模式上。
 *
 * 所有让状态机前进的事情只有两件：
 *  - [pump]：宿主按自己的节奏调用（带当前毫秒时间戳），跑完协程队列，返回一帧命令；
 *  - [deliver]：宿主把 IO 结果送回来，Kotlin 只做入队，下一次 [pump] 才推进。
 *
 * 因此没有后台线程、没有跨线程回调，超时/重连完全由宿主喂进来的时间决定。
 * 这也意味着：宿主不调 [pump]，会话就是静止的——后台被系统冻结时正好是这个效果。
 */
@OptIn(ExperimentalEncodingApi::class)
class SessionHost(
    private val config: SessionConfig,
    initialTasks: List<PromptTask> = emptyList(),
    private val pollIntervalHintMs: Long = 250,
) {
    private val dispatcher = PumpDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var clockNowMs = 0L
    private val clock = object : TimeSource {
        override fun nowMillis(): Long = clockNowMs
    }
    private val jitter = object : JitterSource {
        // 固定种子：鸿蒙侧不需要密码学随机，可复现的抖动更容易对照日志。
        private var seed = 0x2545F491L
        override fun nextSigned(): Double {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 7)
            seed = seed xor (seed shl 17)
            val unit = (seed and 0xFFFFFF).toDouble() / 0xFFFFFF.toDouble()
            return unit * 2.0 - 1.0
        }
    }

    private val outbox = mutableListOf<JsonObject>()
    private var revision = 0L
    private var stateFingerprint = ""
    private var nextId = 1L
    private val httpWaiters = mutableMapOf<Long, CancellableContinuation<HttpResponse>>()
    private val socketWaiters = mutableMapOf<Long, CancellableContinuation<Unit>>()
    private val socketListeners = mutableMapOf<Long, WebSocketListener>()
    private var started = false

    private val store = BridgeTaskStore(initialTasks.associateBy { it.id }.toMutableMap())

    val runtime = SessionRuntime(
        config = config,
        http = HttpBridge(),
        sockets = SocketBridge(),
        store = store,
        clock = clock,
        jitter = jitter,
        scope = scope,
        initialTasks = initialTasks,
        onNotice = { notice ->
            // 通知也是命令，宿主自己决定怎么展示（Toast/系统通知/日志）。
            enqueue(
                buildJsonObject {
                    put("t", "notice")
                    put("notice", notice.toBridgeJson())
                },
            )
        },
    )

    val state: SessionState get() = runtime.state.value

    /** 启动协程泵与心跳；重复调用无副作用。 */
    fun start() {
        if (started) return
        started = true
        runtime.start()
    }

    fun shutdown() {
        runtime.shutdown()
        socketWaiters.values.forEach { it.cancel() }
        socketWaiters.clear()
        socketListeners.clear()
        httpWaiters.values.forEach { it.cancel() }
        httpWaiters.clear()
        scope.cancel()
    }

    /**
     * 推进到 [nowMs]：先跑到期定时器与协程队列，再产出一帧。
     * 返回值是 JSON 字符串，直接交给宿主解析。
     */
    fun pump(nowMs: Long): String {
        clockNowMs = nowMs
        dispatcher.pump(nowMs)
        return renderFrame(nowMs)
    }

    /**
     * 接收宿主的一条消息。返回 false 表示消息无法识别（被忽略），
     * 调用方应当紧接着 [pump] 一次，否则状态不会推进。
     */
    fun deliver(raw: String): Boolean {
        return when (val message = HostMessage.parse(raw)) {
            null -> false

            is HostMessage.HttpResult -> {
                val waiter = httpWaiters.remove(message.id) ?: return false
                waiter.resume(
                    if (message.failure != null) {
                        com.comfypilot.core.transport.httpFailure(message.failure)
                    } else {
                        HttpResponse(
                            status = message.status,
                            body = message.body?.encodeToByteArray(),
                        )
                    },
                )
                true
            }

            is HostMessage.SocketOpened -> {
                socketListeners[message.id]?.onOpen()
                socketWaiters.remove(message.id)?.resume(Unit)
                true
            }

            is HostMessage.SocketText -> {
                socketListeners[message.id]?.onText(message.text)
                true
            }

            is HostMessage.SocketClosed -> {
                socketListeners.remove(message.id)?.onClosed(message.code, message.reason)
                socketWaiters.remove(message.id)?.cancel()
                true
            }

            is HostMessage.SocketFailed -> {
                socketListeners.remove(message.id)?.onFailure(message.message)
                socketWaiters.remove(message.id)?.cancel()
                true
            }

            is HostMessage.NetworkChanged -> {
                enqueue(buildNetworkCommand(message.network))
                runtime.onNetworkChanged(message.network)
                true
            }

            HostMessage.Start -> {
                start()
                runtime.connect()
                true
            }

            HostMessage.Stop -> {
                runtime.disconnect()
                true
            }

            HostMessage.Reconnect -> {
                runtime.reconnect()
                true
            }

            is HostMessage.Submit -> {
                runtime.submit(message.workflowJson)
                true
            }

            is HostMessage.Cancel -> {
                runtime.cancel(message.promptId)
                true
            }
        }
    }

    private fun renderFrame(nowMs: Long): String {
        val stateJson = runtime.state.value.toBridgeJson()
        val fingerprint = stateJson.toString()
        if (fingerprint != stateFingerprint) {
            stateFingerprint = fingerprint
            revision++
        }
        val frame = buildJsonObject {
            put("revision", revision)
            putJsonArray("commands") {
                outbox.forEach { add(it) }
                outbox.clear()
            }
            put("state", stateJson)
            put("nextDelayMs", dispatcher.nextDelayMs(nowMs) ?: pollIntervalHintMs)
        }
        return frame.toString()
    }

    private fun enqueue(command: JsonObject) {
        outbox += command
        revision++
    }

    private inner class HttpBridge : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse =
            suspendCancellableCoroutine { continuation ->
                val id = nextId++
                httpWaiters[id] = continuation
                continuation.invokeOnCancellation { httpWaiters.remove(id) }
                enqueue(
                    buildHttpCommand(
                        id = id,
                        method = request.method,
                        url = request.url,
                        contentType = request.contentType,
                        headers = request.headers,
                        bodyBase64 = request.body?.let { Base64.encode(it) },
                        timeoutMs = request.timeoutMs,
                    ),
                )
            }
    }

    /**
     * socket 的 handle 与监听器都按 id 分开登记：旧 socket 迟到的回调只会落到自己的
     * 监听器上，而 SessionRuntime 会用租约把陈旧输入丢掉，两侧都不会串台。
     */
    private inner class SocketBridge : WebSocketTransport {
        override suspend fun open(url: String, listener: WebSocketListener): WebSocketHandle {
            val id = nextId++
            socketListeners[id] = listener
            suspendCancellableCoroutine<Unit> { continuation ->
                socketWaiters[id] = continuation
                continuation.invokeOnCancellation {
                    socketWaiters.remove(id)
                    socketListeners.remove(id)
                }
                enqueue(
                    buildJsonObject {
                        put("t", "ws.open")
                        put("id", id)
                        put("url", url)
                    },
                )
            }
            return object : WebSocketHandle {
                override suspend fun close(code: Int, reason: String) {
                    socketListeners.remove(id)
                    enqueue(
                        buildJsonObject {
                            put("t", "ws.close")
                            put("id", id)
                            put("code", code)
                            put("reason", reason)
                        },
                    )
                }
            }
        }
    }

    /** OHOS 侧暂时只有内存存储；真正的 RDB 由宿主实现 [TaskStore] 时接入。 */
    private inner class BridgeTaskStore(
        private val items: MutableMap<TaskId, PromptTask>,
    ) : TaskStore {
        override suspend fun load(): List<PromptTask> = items.values.toList()

        override suspend fun save(task: PromptTask) {
            items[task.id] = task
            enqueue(
                buildJsonObject {
                    put("t", "persist")
                    put("task", task.toBridgeJson())
                },
            )
        }

        override suspend fun remove(id: TaskId) {
            items.remove(id)
            enqueue(
                buildJsonObject {
                    put("t", "persist.remove")
                    put("id", id)
                },
            )
        }
    }

    companion object {
        /** 供宿主日志/UI 使用的一行摘要，不含用户数据。 */
        fun describe(state: SessionState): String =
            "phase=${state.connectionLabel} generation=${state.generation} tasks=${state.tasks.size}"
    }
}
