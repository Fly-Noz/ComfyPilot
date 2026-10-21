package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 端到端联调夹具：一个**真的**宿主执行器。
 *
 * 单元测试里的 `HostDriver` 会自己编造 HTTP 结果；这里不编，命令怎么出栈就怎么执行：
 * `http` 用 JDK 的 HttpClient 真发，`ws.open` 真握手，收到的帧按协议回投。
 * 对端是 `tools/mock-comfyui`，所以四个连接场景在没有手机的情况下也能跑一遍。
 *
 * 它验证的是**协议本身可执行**（URL、方法、头、base64 请求体、WS 帧、持久化载荷），
 * 不验证 ArkTS 对 `@ohos.net` 的用法——那部分只有真机能证明。
 *
 * 所有回投都在测试线程上做，WS 回调只往队列里塞事件：SessionHost 是单线程的，
 * 夹具必须替它守住这个前提，否则测出来的 bug 是夹具自己的。
 */
class BridgeHarness(
    private val config: SessionConfig,
) : AutoCloseable {

    val host = SessionHost(config, pollIntervalHintMs = 10, freezeGapMs = 60_000)

    // 固定 HTTP/1.1：JDK 客户端默认会带 `Upgrade: h2c`，而 mock 的 upgrade 处理只认 /ws，
    // 会直接把连接掐掉（"header parser received no bytes"）。真实的 OkHttp / @ohos.net 都是 1.1。
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val inbox = LinkedBlockingQueue<String>()
    private val sockets = mutableMapOf<Long, WebSocket>()

    /** `persist` 命令写下来的东西，键是任务 id。模拟宿主的数据库。 */
    val persisted = mutableMapOf<String, JsonObject>()
    val notices = mutableListOf<String>()
    var httpCalls = 0
        private set

    fun connect(transport: String = "wifi", handle: String = "wlan0") {
        host.start()
        host.deliver("""{"t":"net","transport":"$transport","validated":true,"handle":"$handle"}""")
        host.deliver("""{"t":"start"}""")
    }

    fun switchNetwork(transport: String, handle: String) {
        host.deliver("""{"t":"net","transport":"$transport","validated":true,"handle":"$handle"}""")
    }

    fun submit(workflow: String = """{"3":{"class_type":"KSampler","inputs":{}}}""") {
        host.deliver(buildJsonObject { put("t", "submit"); put("workflow", workflow) }.toString())
    }

    /**
     * 一直推进到 [predicate] 成立；超时就把现场打出来，省得只有一个 false。
     */
    fun runUntil(description: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            drainInbox()
            execute(host.pump(System.currentTimeMillis()))
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("等待超时：$description；${describe()}")
    }

    /** 让时间空转一小会儿，用于"应该什么都不发生"的断言。 */
    fun settle(forMs: Long = 300) {
        val deadline = System.currentTimeMillis() + forMs
        while (System.currentTimeMillis() < deadline) {
            drainInbox()
            execute(host.pump(System.currentTimeMillis()))
            Thread.sleep(5)
        }
    }

    fun describe(): String {
        val tasks = host.state.tasks.values.joinToString { "${it.id}:${it.state}" }
        return "phase=${host.state.phase} generation=${host.state.generation} tasks=[$tasks] notices=$notices"
    }

    override fun close() {
        sockets.values.forEach { socket -> runCatching { socket.sendClose(1001, "done") } }
        sockets.clear()
        runCatching { host.shutdown() }
        runCatching { client.close() }
    }

    // ------------------------------------------------------------ 执行命令

    private fun drainInbox() {
        while (true) {
            val raw = inbox.poll() ?: return
            host.deliver(raw)
        }
    }

    private fun execute(rawFrame: String) {
        val frame = Json.parseToJsonElement(rawFrame).jsonObject
        frame["commands"]?.jsonArray?.forEach { element ->
            val command = element.jsonObject
            when (command.stringOrNull("t")) {
                "http" -> executeHttp(command)
                "ws.open" -> openSocket(command)
                "ws.close" -> closeSocket(command)
                "persist" -> {
                    val task = command["task"]!!.jsonObject
                    persisted[task.stringOrNull("id")!!] = task
                }

                "persist.remove" -> persisted.remove(command.stringOrNull("id"))
                "notice" -> notices += command["notice"]!!.jsonObject.stringOrNull("kind").orEmpty()
            }
        }
    }

    private fun executeHttp(command: JsonObject) {
        val id = command["id"]!!.jsonPrimitive.long
        val url = URI(command.stringOrNull("url")!!)
        val timeoutMs = command["timeoutMs"]?.jsonPrimitive?.long ?: 5_000
        val body = command.stringOrNull("body")?.let { Base64.getDecoder().decode(it) }

        val request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(500)))
            .apply {
                command.stringOrNull("contentType")?.let { header("Content-Type", it) }
                (command["headers"] as? JsonObject)?.forEach { (key, value) ->
                    header(key, (value as JsonPrimitive).content)
                }
            }
            .method(
                command.stringOrNull("method")!!,
                body?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()

        httpCalls++
        val raw = try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            buildJsonObject {
                put("t", "http.result")
                put("id", id)
                put("status", response.statusCode())
                put("body", response.body())
            }.toString()
        } catch (error: Exception) {
            val failure = if (error is java.net.http.HttpTimeoutException) "timeout" else "network"
            buildJsonObject {
                put("t", "http.result")
                put("id", id)
                put("failure", failure)
                put("message", error.message ?: error::class.simpleName ?: "http failed")
            }.toString()
        }
        host.deliver(raw)
    }

    private fun openSocket(command: JsonObject) {
        val id = command["id"]!!.jsonPrimitive.long
        val url = URI(command.stringOrNull("url")!!)
        client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(url, Listener(id))
            .whenComplete { socket, error ->
                if (socket != null) {
                    sockets[id] = socket
                    inbox.put("""{"t":"ws.opened","id":$id}""")
                } else {
                    inbox.put(
                        """{"t":"ws.failed","id":$id,"message":${quote(error?.message ?: "handshake failed")}}""",
                    )
                }
            }
    }

    private fun closeSocket(command: JsonObject) {
        val id = command["id"]!!.jsonPrimitive.long
        val code = command["code"]?.jsonPrimitive?.int ?: 1000
        val reason = command.stringOrNull("reason").orEmpty()
        sockets.remove(id)?.let { socket ->
            runCatching { socket.sendClose(code, reason.take(120)) }
        }
    }

    private inner class Listener(private val id: Long) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                inbox.put("""{"t":"ws.text","id":$id,"text":${quote(text)}}""")
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            inbox.put("""{"t":"ws.closed","id":$id,"code":$statusCode,"reason":${quote(reason)}}""")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            inbox.put("""{"t":"ws.failed","id":$id,"message":${quote(error.message ?: "socket error")}}""")
        }
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    companion object {
        fun config(baseUrl: String, progressSilenceMs: Long = 3_000): SessionConfig = SessionConfig(
            baseUrl = baseUrl,
            connectTimeoutMs = 2_000,
            httpTimeoutMs = 2_000,
            initialBackoffMs = 100,
            maxBackoffMs = 400,
            backoffMultiplier = 2.0,
            jitterRatio = 0.0,
            reconcileMaxAttempts = 2,
            reconcileWindowMs = 4_000,
            reconcileRetryBaseMs = 100,
            progressSilenceMs = progressSilenceMs,
            progressSilenceMaxMs = progressSilenceMs * 2,
            tickIntervalMs = 50,
        )
    }
}

/**
 * 用 node 拉起 `tools/mock-comfyui`。端口由 [freePort] 现挑，避免和开发机上
 * 已经开着的 8188 打架。
 */
class MockComfyUi private constructor(
    private val process: Process,
    private val port: Int,
) : AutoCloseable {

    val baseUrl: String = "http://127.0.0.1:$port"

    fun reset() = post("/mock/reset", "{}")

    fun scenario(name: String) = post("/mock/scenario", """{"scenario":"$name"}""")

    /** `/mock/state` 里的 hits，用来验证"不重试""不重投"。 */
    fun hits(): Map<String, Int> {
        val state = get("/mock/state").jsonObject
        val hits = state["hits"]?.jsonObject ?: return emptyMap()
        return hits.mapValues { (_, value) -> value.jsonPrimitive.int }
    }

    fun get(path: String): JsonObject {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI(baseUrl + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun post(path: String, body: String) {
        httpClient.send(
            HttpRequest.newBuilder(URI(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build()

        fun start(): MockComfyUi {
            val port = freePort()
            val root = repoRoot()
            val log = File(System.getProperty("java.io.tmpdir"), "comfypilot-mock-$port.log")
            val process = try {
                ProcessBuilder("node", "tools/mock-comfyui/server.mjs")
                    .directory(root)
                    .apply {
                        environment()["PORT"] = port.toString()
                        environment()["HOST"] = "127.0.0.1"
                        redirectErrorStream(true)
                        redirectOutput(log)
                    }
                    .start()
            } catch (error: Exception) {
                throw AssertionError("启动 node 失败（cwd=$root）：${error.message}", error)
            }
            val server = MockComfyUi(process, port)
            var lastError: Throwable? = null
            for (attempt in 0 until 100) {
                if (process.isAlive) {
                    val probe = runCatching { server.get("/mock/state") }
                    if (probe.isSuccess) return server
                    lastError = probe.exceptionOrNull()
                } else {
                    break
                }
                Thread.sleep(100)
            }
            val output = runCatching { log.readText() }.getOrDefault("").takeLast(2_000)
            server.close()
            throw AssertionError(
                "mock ComfyUI 启动失败（端口 $port，cwd=$root）：$output；最后一次探测失败：$lastError",
            )
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun repoRoot(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "tools/mock-comfyui/server.mjs").isFile) return dir
                dir = dir.parentFile
            }
            throw AssertionError("找不到仓库根目录（tools/mock-comfyui/server.mjs）")
        }
    }
}
