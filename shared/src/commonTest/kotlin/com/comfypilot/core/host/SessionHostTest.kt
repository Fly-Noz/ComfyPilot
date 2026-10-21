package com.comfypilot.core.host

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
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

    @Test
    fun `submit run and complete all flow through host commands`() {
        val driver = HostDriver()
        driver.host.start()
        val wsId = driver.connect()

        val submitId = HostDriver.submit(driver)
        val submit = assertNotNull(driver.command("http"), "提交后应当产生 HTTP 命令")
        assertEquals("POST", (submit["method"] as JsonPrimitive).content)
        assertEquals("http://127.0.0.1:8188/prompt", (submit["url"] as JsonPrimitive).content)
        assertNotNull(submit["body"], "POST /prompt 必须带请求体")
        assertEquals("Submitting", driver.taskState("draft-1"))

        HostDriver.acceptSubmit(driver, submitId)
        assertEquals("Queued", driver.taskState("p1"))

        // 草稿转正后，持久化里那条草稿必须删掉：否则重启会把它当成
        // "Unknown：应用重启，提交结果未知"，每提交一次就多一条幽灵任务。
        val removedDrafts = driver.allCommands()
            .filter { (it["t"] as? JsonPrimitive)?.content == "persist.remove" }
            .mapNotNull { (it["id"] as? JsonPrimitive)?.content }
        assertEquals(listOf("draft-1"), removedDrafts)

        driver.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"progress\",\"data\":{\"value\":5,\"max\":10,\"prompt_id\":\"p1\",\"node\":\"3\"}}"}""")
        val running = assertNotNull(driver.task("p1"))
        assertEquals("50", (running["percent"] as JsonPrimitive).content)

        // executing(node=null) 只是提示，必须回到 /history 才算完成。
        driver.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"p1\"}}"}""")
        val history = assertNotNull(driver.command("http"), "executing(node=null) 后应当核实 /history")
        assertEquals("GET", (history["method"] as JsonPrimitive).content)
        assertEquals("http://127.0.0.1:8188/history/p1", (history["url"] as JsonPrimitive).content)
        val historyId = (history["id"] as JsonPrimitive).long

        driver.deliver(
            """{"t":"http.result","id":$historyId,"status":200,"body":"{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\",\"type\":\"output\"}]}},\"status\":{\"status_str\":\"success\",\"completed\":true,\"messages\":[]}}}"}""",
        )

        val done = assertNotNull(driver.task("p1"))
        assertEquals("Completed", (done["state"] as JsonPrimitive).content)
        assertEquals("1", (done["outputs"] as JsonPrimitive).content)
    }

    @Test
    fun `submit transport failure fails the draft instead of hanging`() {
        val driver = HostDriver()
        driver.host.start()
        driver.connect()

        val submitId = HostDriver.submit(driver)
        driver.deliver("""{"t":"http.result","id":$submitId,"failure":"timeout"}""")

        assertEquals("Failed", driver.taskState("draft-1"))
    }

    @Test
    fun `history error fails the task immediately without retrying`() {
        val driver = HostDriver()
        driver.host.start()
        val wsId = driver.connect()

        val submitId = HostDriver.submit(driver)
        HostDriver.acceptSubmit(driver, submitId)
        driver.deliver("""{"t":"ws.text","id":$wsId,"text":"{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"p1\"}}"}""")
        val historyId = (assertNotNull(driver.command("http"))["id"] as JsonPrimitive).long

        driver.deliver(
            """{"t":"http.result","id":$historyId,"status":200,"body":"{\"p1\":{\"outputs\":{},\"status\":{\"status_str\":\"error\",\"completed\":false,\"messages\":[[\"execution_error\",{\"exception_message\":\"CUDA OOM\"}]]}}}"}""",
        )

        assertEquals("Failed", driver.taskState("p1"))
        assertNull(driver.command("http"), "可读的错误响应不允许重试")
    }

    @Test
    fun `results for unknown ids are ignored instead of crashing`() {
        val driver = HostDriver()
        driver.host.start()
        driver.connect()

        assertFalse(driver.host.deliver("""{"t":"http.result","id":9999,"status":200,"body":"{}"}"""))
        assertFalse(driver.host.deliver("""{"t":"nonsense"}"""))
        assertFalse(driver.host.deliver("not json"))
        assertEquals("Connected", driver.stateString("phase"))
    }

    @Test
    fun `switching network class bumps generation and reconnects`() {
        val driver = HostDriver()
        driver.host.start()
        val firstWs = driver.connect()

        driver.deliver("""{"t":"net","transport":"cellular","validated":true,"handle":"rmnet0"}""")

        assertEquals("2", driver.stateString("generation"))
        val reopened = assertNotNull(driver.command("ws.open"), "跨类别切换必须重连")
        assertTrue((reopened["id"] as JsonPrimitive).long != firstWs)

        // 旧 socket 迟到的消息不能把状态改回去
        driver.deliver("""{"t":"ws.text","id":$firstWs,"text":"{\"type\":\"status\",\"data\":{\"status\":{\"exec_info\":{\"queue_remaining\":3}}}}"}""")
        assertEquals("2", driver.stateString("generation"))
    }

    @Test
    fun `same network class keeps generation`() {
        val driver = HostDriver()
        driver.host.start()
        driver.connect()

        driver.deliver("""{"t":"net","transport":"wifi","validated":true,"handle":"wlan1"}""")
        assertEquals("1", driver.stateString("generation"))
        assertNull(driver.command("ws.open"), "同一网络类别的重复上报不应重建连接")
    }

    @Test
    fun `frame always carries a pump delay hint`() {
        val driver = HostDriver()
        driver.host.start()
        driver.connect()

        val hint = driver.frame["nextDelayMs"]!!.jsonPrimitive.int
        assertTrue(hint in 1..5_000, "宿主需要知道下一次 pump 的时间，拿到 $hint")
    }
}
