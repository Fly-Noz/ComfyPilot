package com.comfypilot.core.host

import com.comfypilot.core.net.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用"假宿主"驱动 [SessionHost]：它扮演 ArkTS 的角色，读命令、给结果。
 * 这样桥接协议本身就能在 JVM 上验证，不用等真机。
 */
class SessionHostTest {

    private val config = SessionConfig(
        baseUrl = "http://127.0.0.1:8188",
        connectTimeoutMs = 5_000,
        httpTimeoutMs = 5_000,
        initialBackoffMs = 1_000,
        maxBackoffMs = 8_000,
        backoffMultiplier = 2.0,
        jitterRatio = 0.0,
        reconcileMaxAttempts = 3,
        reconcileWindowMs = 10_000,
        reconcileRetryBaseMs = 1_000,
        progressSilenceMs = 60_000,
        progressSilenceMaxMs = 120_000,
        tickIntervalMs = 500,
    )

    private inner class FakeHost(private var now: Long = 1_000) {
        val host = SessionHost(config)
        var frame: JsonObject = parse(host.pump(now))
            private set

        fun pump(advanceMs: Long = 20): JsonObject {
            now += advanceMs
            frame = parse(host.pump(now))
            return frame
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

        fun state(): JsonObject = frame["state"]!!.jsonObject

        fun stateString(key: String): String = (state()[key] as JsonPrimitive).content

        fun task(id: String): JsonObject? =
            (state()["tasks"] as JsonArray).map { it.jsonObject }
                .firstOrNull { (it["id"] as JsonPrimitive).content == id }

        fun taskState(id: String): String =
            (assertNotNull(task(id), "找不到任务 $id")["state"] as JsonPrimitive).content
    }

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    /** 建立连接，返回宿主侧的 ws id。 */
    private fun connect(fake: FakeHost): Long {
        fake.deliver("""{"t":"net","transport":"wifi","validated":true,"handle":"wlan0"}""")
        fake.deliver("""{"t":"start"}""")

        val open = assertNotNull(fake.command("ws.open"), "启动后应当要求宿主打开 WebSocket")
        val wsId = (open["id"] as JsonPrimitive).long
        val url = (open["url"] as JsonPrimitive).content
        assertEquals("ws://127.0.0.1:8188/ws", url.substringBefore('?'))
        assertTrue(url.contains("clientId="), "WebSocket URL 要带 clientId")
        assertEquals("Connecting", fake.stateString("phase"))

        fake.deliver("""{"t":"ws.opened","id":$wsId}""")
        assertEquals("Connected", fake.stateString("phase"))
        assertEquals("已连接", fake.stateString("label"))
        assertEquals("1", fake.stateString("generation"))
        return wsId
    }

    @Test
    fun `submit run and complete all flow through host commands`() {
        val fake = FakeHost()
        fake.host.start()
        val wsId = connect(fake)

        fake.deliver("""{"t":"submit","workflow":"{\"3\":{\"class_type\":\"KSampler\",\"inputs\":{}}}"}""")
        val submit = assertNotNull(fake.command("http"), "提交后应当产生 HTTP 命令")
        assertEquals("POST", (submit["method"] as JsonPrimitive).content)
        assertEquals("http://127.0.0.1:8188/prompt", (submit["url"] as JsonPrimitive).content)
        assertNotNull(submit["body"], "POST /prompt 必须带请求体")
        val submitId = (submit["id"] as JsonPrimitive).long
        assertEquals("Submitting", fake.taskState("draft-1"))

        fake.deliver("""{"t":"http.result","id":$submitId,"status":200,"body":"{\"prompt_id\":\"p1\",\"number\":1}"}""")
        assertEquals("Queued", fake.taskState("p1"))

        fake.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"progress\",\"data\":{\"value\":5,\"max\":10,\"prompt_id\":\"p1\",\"node\":\"3\"}}"}""")
        val running = assertNotNull(fake.task("p1"))
        assertEquals("50", (running["percent"] as JsonPrimitive).content)

        // executing(node=null) 只是提示，必须回到 /history 才算完成。
        fake.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"p1\"}}"}""")
        val history = assertNotNull(fake.command("http"), "executing(node=null) 后应当核实 /history")
        assertEquals("GET", (history["method"] as JsonPrimitive).content)
        assertEquals("http://127.0.0.1:8188/history/p1", (history["url"] as JsonPrimitive).content)
        val historyId = (history["id"] as JsonPrimitive).long

        fake.deliver(
            """{"t":"http.result","id":$historyId,"status":200,"body":"{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\",\"type\":\"output\"}]}},\"status\":{\"status_str\":\"success\",\"completed\":true,\"messages\":[]}}}"}""",
        )

        val done = assertNotNull(fake.task("p1"))
        assertEquals("Completed", (done["state"] as JsonPrimitive).content)
        assertEquals("1", (done["outputs"] as JsonPrimitive).content)
    }

    @Test
    fun `submit transport failure fails the draft instead of hanging`() {
        val fake = FakeHost()
        fake.host.start()
        connect(fake)

        fake.deliver("""{"t":"submit","workflow":"{\"3\":{\"class_type\":\"KSampler\",\"inputs\":{}}}"}""")
        val submitId = (assertNotNull(fake.command("http"))["id"] as JsonPrimitive).long

        fake.deliver("""{"t":"http.result","id":$submitId,"failure":"timeout"}""")

        assertEquals("Failed", fake.taskState("draft-1"))
    }

    @Test
    fun `history error fails the task immediately without retrying`() {
        val fake = FakeHost()
        fake.host.start()
        connect(fake)

        fake.deliver("""{"t":"submit","workflow":"{\"3\":{\"class_type\":\"KSampler\",\"inputs\":{}}}"}""")
        val submitId = (assertNotNull(fake.command("http"))["id"] as JsonPrimitive).long
        fake.deliver("""{"t":"http.result","id":$submitId,"status":200,"body":"{\"prompt_id\":\"p1\",\"number\":1}"}""")

        fake.deliver("""{"t":"ws.text","id":1,"text":"{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"p1\"}}"}""")
        val historyId = (assertNotNull(fake.command("http"))["id"] as JsonPrimitive).long

        fake.deliver(
            """{"t":"http.result","id":$historyId,"status":200,"body":"{\"p1\":{\"outputs\":{},\"status\":{\"status_str\":\"error\",\"completed\":false,\"messages\":[[\"execution_error\",{\"exception_message\":\"CUDA OOM\"}]]}}}"}""",
        )

        assertEquals("Failed", fake.taskState("p1"))
        assertNull(fake.command("http"), "可读的错误响应不允许重试")
    }

    @Test
    fun `results for unknown ids are ignored instead of crashing`() {
        val fake = FakeHost()
        fake.host.start()
        connect(fake)

        assertFalse(fake.host.deliver("""{"t":"http.result","id":9999,"status":200,"body":"{}"}"""))
        assertFalse(fake.host.deliver("""{"t":"nonsense"}"""))
        assertFalse(fake.host.deliver("not json"))
        assertEquals("Connected", fake.stateString("phase"))
    }

    @Test
    fun `switching network class bumps generation and reconnects`() {
        val fake = FakeHost()
        fake.host.start()
        val firstWs = connect(fake)

        fake.deliver("""{"t":"net","transport":"cellular","validated":true,"handle":"rmnet0"}""")

        assertEquals("2", fake.stateString("generation"))
        val reopened = assertNotNull(fake.command("ws.open"), "跨类别切换必须重连")
        assertTrue((reopened["id"] as JsonPrimitive).long != firstWs)

        fake.deliver("""{"t":"ws.text","id":$firstWs,"text":"{\"type\":\"status\",\"data\":{\"status\":{\"exec_info\":{\"queue_remaining\":3}}}}"}""")
        assertEquals("2", fake.stateString("generation"))
    }

    @Test
    fun `same network class keeps generation`() {
        val fake = FakeHost()
        fake.host.start()
        connect(fake)

        fake.deliver("""{"t":"net","transport":"wifi","validated":true,"handle":"wlan1"}""")
        assertEquals("1", fake.stateString("generation"))
    }

    @Test
    fun `frame always carries a pump delay hint`() {
        val fake = FakeHost()
        fake.host.start()
        connect(fake)

        val hint = fake.frame["nextDelayMs"]!!.jsonPrimitive.int
        assertTrue(hint in 1..5_000, "宿主需要知道下一次 pump 的时间，拿到 $hint")
    }
}
