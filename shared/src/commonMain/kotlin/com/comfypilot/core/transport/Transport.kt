package com.comfypilot.core.transport

/**
 * 平台差异只允许出现在这三个接口的实现里。commonMain 不感知 OkHttp / @ohos.net。
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val body: ByteArray? = null,
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 20_000,
)

data class HttpResponse(
    val status: Int,
    val body: ByteArray? = null,
    /** 传输层失败（DNS、断网、超时、TLS）；此时 status 无意义，置 0。 */
    val failure: String? = null,
) {
    val isSuccess: Boolean get() = failure == null && status in 200..299

    fun text(): String? = body?.decodeToString()
}

fun httpFailure(message: String): HttpResponse = HttpResponse(status = 0, failure = message)

interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

interface WebSocketListener {
    fun onOpen()
    fun onText(text: String)

    /** 二进制帧（ComfyUI 的采样预览）；不关心的实现可以不覆盖。 */
    fun onBinary(bytes: ByteArray) {}

    fun onClosed(code: Int, reason: String)
    fun onFailure(message: String)
}

/**
 * 每个连接一个 handle。旧连接只能关掉自己，无法影响新连接——这是代际隔离的传输层基础。
 */
interface WebSocketHandle {
    suspend fun close(code: Int = 1000, reason: String = "")

    /** 发送一条文本帧：用于握手后的能力声明（feature_flags）。 */
    suspend fun send(text: String)
}

interface WebSocketTransport {
    suspend fun open(url: String, listener: WebSocketListener): WebSocketHandle
}

interface TimeSource {
    fun nowMillis(): Long
}

interface JitterSource {
    /** 返回 [-1, 1]，用于退避随机抖动。 */
    fun nextSigned(): Double
}
