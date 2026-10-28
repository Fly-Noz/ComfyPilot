package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 鸿蒙侧协议契约：ArkTS 发什么、拿回什么。
 *
 * 这里按 ArkTS 的真实报文（`cp_ai_step` / `cp_ai_send` 的入参）走完整条链路——
 * 请求少了字段、动作名对不上，都在 JVM 上先撞出来。
 */
class AiLoopProtocolTest {

    private val context =
        """{"model":"deepseek-chat","system":["你是助手"],"temperature":0.2,"paths":["a.json"]}"""

    private fun start(history: String = "[]"): String =
        """{"op":"start","context":$context,"history":$history,
           "userTurn":{"role":"user","content":"这个工作流有几个节点"}}"""

    private fun reply(text: String): String =
        """{"op":"reply","context":$context,"message":{"role":"assistant","content":"$text"}}"""

    private fun replyTools(name: String, args: String = "{}"): String =
        """{"op":"reply","context":$context,"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"c1","type":"function","function":{"name":"$name","arguments":"$args"}}]}}"""

    private fun tool(result: String, pause: String = "", question: String = ""): String =
        """{"op":"tool","context":$context,"callId":"c1","result":"$result",
           "pause":"$pause","question":"$question"}"""

    private fun action(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    /** JSON 字符串值要解转义：动作里的 body / args / text 都是嵌套的 JSON 文本。 */
    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    @Test
    fun `start returns the first request`() {
        val protocol = AiLoopProtocol()
        val step = action(protocol.step(start()))

        assertEquals("http", step.str("action"))
        val body = Json.parseToJsonElement(step.str("body")).jsonObject
        assertEquals("deepseek-chat", body.str("model"))
        val messages = body["messages"] as JsonArray
        assertEquals(2, messages.size)
        assertEquals("你是助手", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `history goes before the new turn`() {
        val protocol = AiLoopProtocol()
        val history = """[{"role":"user","content":"上一句"},{"role":"assistant","content":"上一答"}]"""
        val step = action(protocol.step(start(history)))
        val messages = Json.parseToJsonElement(step.str("body")).jsonObject["messages"] as JsonArray
        assertEquals(4, messages.size)
        assertEquals("上一句", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool call comes back as call_host and the result starts the next request`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())

        val call = action(protocol.send(replyTools("list_nodes", """{\"query\":\"Load\"}""")))
        assertEquals("call_host", call.str("action"))
        assertEquals("list_nodes", call.str("name"))
        assertEquals("c1", call.str("callId"))
        assertTrue(call.str("args").contains("Load"))

        val next = action(protocol.send(tool("""{\"nodes\":[]}""")))
        assertEquals("http", next.str("action"))
        val messages = Json.parseToJsonElement(next.str("body")).jsonObject["messages"] as JsonArray
        // user → assistant(tool_calls) → tool
        assertEquals("tool", messages.last().jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("c1", messages.last().jsonObject["tool_call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `answer ends with done answer`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        val done = action(protocol.send(reply("有 12 个节点")))

        assertEquals("done", done.str("action"))
        assertEquals("answer", done.str("reason"))
        assertEquals("有 12 个节点", done.str("text"))
    }

    @Test
    fun `submit result is formatted in kotlin`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        action(protocol.send(replyTools("submit")))

        // 宿主只回原始结果，核实提示由 formatResult 补上。
        val next = action(protocol.send(tool("""{\"ok\":true}""")))
        val body = next.str("body")
        assertTrue(body.contains("queued"), body)
        assertTrue(body.contains("task_status"), body)
    }

    @Test
    fun `pending and question stop the loop`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        action(protocol.send(replyTools("propose_edits", """{\"summary\":\"改\",\"ops\":[]}""")))

        val pending = action(protocol.send(tool("""{\"ok\":true}""", pause = "pending")))
        assertEquals("done", pending.str("action"))
        assertEquals("pending", pending.str("reason"))

        val protocol2 = AiLoopProtocol()
        protocol2.step(start())
        action(protocol2.send(replyTools("ask_user")))
        val asked = action(protocol2.send(tool("""{\"ok\":true}""", pause = "question", question = "要哪种风格")))
        assertEquals("question", asked.str("reason"))
        assertEquals("要哪种风格", asked.str("text"))
    }

    @Test
    fun `resume continues after the user answers`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        action(protocol.send(replyTools("ask_user")))
        action(protocol.send(tool("""{\"ok\":true}""", pause = "question", question = "要哪种风格")))

        val resumed = action(
            protocol.send(
                """{"op":"resume","context":$context,"userTurn":{"role":"user","content":"插画"}}""",
            ),
        )
        assertEquals("http", resumed.str("action"))
        val messages = Json.parseToJsonElement(resumed.str("body")).jsonObject["messages"] as JsonArray
        assertTrue(messages.last().toString().contains("插画"))
    }

    @Test
    fun `open workflow is checked against the workspace before reaching the host`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        // 不在工作区：不产生 call_host，错误直接回填进下一轮请求。
        val next = action(protocol.send(replyTools("open_workflow", """{\"path\":\"z.json\"}""")))
        assertEquals("http", next.str("action"))
        assertTrue(next.str("body").contains("不在当前 AI 工作区内"), next.str("body"))

        val allowed = AiLoopProtocol()
        allowed.step(start())
        val call = action(allowed.send(replyTools("open_workflow", """{\"path\":\"a.json\"}""")))
        assertEquals("call_host", call.str("action"))
        assertEquals("open_workflow", call.str("name"))
    }

    @Test
    fun `the same request replayed returns the same action`() {
        val protocol = AiLoopProtocol()
        protocol.step(start())
        action(protocol.send(replyTools("list_graphs")))

        // NAPI 扩容重试会把同一份报文再送一次：不能把工具结果算两遍。
        val first = protocol.send(tool("""{\"graphs\":[]}"""))
        val replay = protocol.send(tool("""{\"graphs\":[]}""")) 
        assertEquals(first, replay)
        assertEquals("http", action(replay).str("action"))
    }

    @Test
    fun `bad input never throws`() {
        val protocol = AiLoopProtocol()
        assertEquals("done", action(protocol.send(reply("抢答"))).str("action"))
        assertEquals("error", action(protocol.send(reply("抢答"))).str("reason"))
        assertEquals("error", action(protocol.step("not json")).str("reason"))
        assertEquals("error", action(protocol.step("""{"op":"nope"}""")).str("reason"))
        assertEquals("error", action(protocol.step("""{"op":"start"}""")).str("reason"))
    }
}
