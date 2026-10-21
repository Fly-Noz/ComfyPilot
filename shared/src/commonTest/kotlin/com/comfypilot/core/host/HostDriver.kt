package com.comfypilot.core.host

import com.comfypilot.core.net.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlin.test.assertNotNull

/**
 * 测试用的"假宿主"：扮演 ArkTS 的角色——按时间推进、读命令、回投结果。
 *
 * 它同时也是这份协议的活文档：宿主只需要 pump / deliver 两个动作。
 */
internal fun testSessionConfig(
    baseUrl: String = "http://127.0.0.1:8188",
    progressSilenceMs: Long = 60_000,
    progressSilenceMaxMs: Long = 120_000,
    tickIntervalMs: Long = 500,
    reconcileWindowMs: Long = 10_000,
) = SessionConfig(
    baseUrl = baseUrl,
    connectTimeoutMs = 5_000,
    httpTimeoutMs = 5_000,
    initialBackoffMs = 1_000,
    maxBackoffMs = 8_000,
    backoffMultiplier = 2.0,
    jitterRatio = 0.0,
    reconcileMaxAttempts = 3,
    reconcileWindowMs = reconcileWindowMs,
    reconcileRetryBaseMs = 1_000,
    progressSilenceMs = progressSilenceMs,
    progressSilenceMaxMs = progressSilenceMaxMs,
    tickIntervalMs = tickIntervalMs,
)

internal class HostDriver(
    val config: SessionConfig = testSessionConfig(),
    startAtMs: Long = 1_000,
    freezeGapMs: Long = 2_000,
) {
    val host = SessionHost(config, pollIntervalHintMs = 250, freezeGapMs = freezeGapMs)

    private var now = startAtMs
    var frame: JsonObject = parse(host.pump(now))
        private set

    /** 通知只出现在产生它的那一帧里，测试要跨多帧看就必须自己累计。 */
    private val seenNotices = mutableListOf<String>()

    init {
        recordNotices()
    }

    fun pump(advanceMs: Long = 20): JsonObject {
        now += advanceMs
        frame = parse(host.pump(now))
        recordNotices()
        return frame
    }

    private fun recordNotices() {
        seenNotices += commands
            .filter { (it["t"] as? JsonPrimitive)?.content == "notice" }
            .mapNotNull { command ->
                (command["notice"] as? JsonObject)?.get("kind")?.let { kind -> (kind as JsonPrimitive).content }
            }
    }

    fun deliver(raw: String): Boolean {
        val accepted = host.deliver(raw)
        pump()
        return accepted
    }

    val commands: List<JsonObject>
        get() = (frame["commands"] as? JsonArray).orEmpty().map { it.jsonObject }

    fun command(type: String): JsonObject? =
        commands.lastOrNull { (it["t"] as? JsonPrimitive)?.content == type }

    fun notices(): List<String> = seenNotices.toList()

    fun state(): JsonObject = frame["state"]!!.jsonObject

    fun stateString(key: String): String = (state()[key] as JsonPrimitive).content

    fun task(id: String): JsonObject? =
        (state()["tasks"] as JsonArray).map { it.jsonObject }
            .firstOrNull { (it["id"] as JsonPrimitive).content == id }

    fun taskState(id: String): String =
        (assertNotNull(task(id), "找不到任务 $id")["state"] as JsonPrimitive).content

    /** 建立连接，返回宿主侧的 ws id。 */
    fun connect(): Long {
        deliver("""{"t":"net","transport":"wifi","validated":true,"handle":"wlan0"}""")
        deliver("""{"t":"start"}""")

        val open = assertNotNull(command("ws.open"), "启动后应当要求宿主打开 WebSocket")
        val wsId = (open["id"] as JsonPrimitive).long
        val url = (open["url"] as JsonPrimitive).content
        check(url.substringBefore('?') == "ws://127.0.0.1:8188/ws") { "WebSocket URL 不对：$url" }
        check(url.contains("clientId=")) { "WebSocket URL 要带 clientId" }

        deliver("""{"t":"ws.opened","id":$wsId}""")
        return wsId
    }

    companion object {
        private const val WORKFLOW = """{"3":{"class_type":"KSampler","inputs":{}}}"""

        fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

        /** 提交一个任务并让 ComfyUI 接受它，返回提交请求的 id。 */
        fun submit(driver: HostDriver): Long {
            driver.deliver("""{"t":"submit","workflow":"${WORKFLOW.replace("\"", "\\\"")}"}""")
            val submit = assertNotNull(driver.command("http"), "提交后应当产生 HTTP 命令")
            return (submit["id"] as JsonPrimitive).long
        }

        fun acceptSubmit(driver: HostDriver, submitId: Long, promptId: String = "p1") {
            driver.deliver(
                """{"t":"http.result","id":$submitId,"status":200,"body":"{\"prompt_id\":\"$promptId\",\"number\":1}"}""",
            )
        }
    }
}
