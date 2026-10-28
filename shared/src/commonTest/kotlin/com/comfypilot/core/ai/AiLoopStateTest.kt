package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 循环状态机的契约：会话拼装、工具往返、轮数上限、暂停与恢复。 */
class AiLoopStateTest {

    private val context = AiLoopContext(model = "deepseek-chat", system = listOf("你是助手", "当前工作流：a.json"))

    private fun user(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun replyText(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    /** 模型的工具调用响应：id、工具名、参数。 */
    private fun replyTools(vararg calls: Triple<String, String, String>): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", JsonNull)
        putJsonArray("tool_calls") {
            calls.forEach { (id, name, args) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", name)
                            put("arguments", args)
                        }
                    },
                )
            }
        }
    }

    @Test
    fun `no tool calls end the loop after one round`() {
        val state = AiLoopState()
        state.start(user("这个工作流有几个节点？"))

        val request = state.nextRequest(context)
        assertIs<AiLoopStep.Http>(request)
        assertEquals(1, state.round)

        val done = state.acceptReply(replyText("有 12 个节点。"))
        assertIs<AiLoopStep.Done>(done)
        assertEquals(AiDoneReason.ANSWER, done.reason)
        assertEquals("有 12 个节点。", done.text)
        assertTrue(state.finished)
        assertEquals(2, state.messages.size)
        assertFalse(state.paused)
    }

    @Test
    fun `tool round trip issues a second request`() {
        val state = AiLoopState()
        state.start(user("列出节点"))
        assertIs<AiLoopStep.Http>(state.nextRequest(context))

        val call = state.acceptReply(replyTools(Triple("c1", "list_nodes", """{"query":"Load"}""")))
        assertIs<AiLoopStep.CallTool>(call)
        assertEquals("c1", call.callId)
        assertEquals("list_nodes", call.name)
        assertEquals("""{"query":"Load"}""", call.args)

        val next = state.acceptToolResult("c1", """{"nodes":[]}""", context)
        assertIs<AiLoopStep.Http>(next)
        assertEquals(2, state.round)

        // 工具结果按标准 OpenAI 形状回填，assistant 的 tool_calls 原样保留。
        assertEquals("assistant", state.messages[1]["role"]?.toString()?.trim('"'))
        assertEquals("tool", state.messages[2]["role"]?.toString()?.trim('"'))
        assertEquals("c1", state.messages[2]["tool_call_id"]?.toString()?.trim('"'))

        val done = state.acceptReply(replyText("查完了"))
        assertEquals(AiDoneReason.ANSWER, (done as AiLoopStep.Done).reason)
        assertEquals(4, state.messages.size)
    }

    @Test
    fun `round limit stops the loop`() {
        val state = AiLoopState(maxRounds = 2)
        state.start(user("一直查"))

        var step: AiLoopStep = state.nextRequest(context)
        assertIs<AiLoopStep.Http>(step)
        repeat(2) { index ->
            val call = state.acceptReply(replyTools(Triple("c$index", "list_graphs", "{}")))
            assertEquals("c$index", (call as AiLoopStep.CallTool).callId)
            step = state.acceptToolResult("c$index", """{"ok":true}""", context)
        }

        val done = step as AiLoopStep.Done
        assertEquals(AiDoneReason.ROUND_LIMIT, done.reason)
        assertEquals(2, state.round)
        assertEquals("工具调用轮数过多，已停止", done.text)
    }

    @Test
    fun `pending proposal pauses the loop`() {
        val state = AiLoopState()
        state.start(user("把步数改成 30"))
        state.nextRequest(context)

        val call = state.acceptReply(
            replyTools(Triple("c1", "propose_edits", """{"summary":"改步数","ops":[]}""")),
        )
        assertIs<AiLoopStep.CallTool>(call)

        // 宿主生成待确认提案后先标记，再回填结果。
        state.markPending()
        val paused = state.acceptToolResult("c1", """{"ok":true,"pending":true}""", context)
        assertEquals(AiDoneReason.PENDING, (paused as AiLoopStep.Done).reason)
        assertTrue(state.paused)
        assertEquals(1, state.round)

        // 暂停期间不会再发请求。
        assertEquals(AiDoneReason.PENDING, (state.nextRequest(context) as AiLoopStep.Done).reason)
        assertEquals(1, state.round)

        // 用户确认后重新开始计数。
        assertTrue(state.resume())
        assertFalse(state.paused)
        assertIs<AiLoopStep.Http>(state.nextRequest(context))
        assertEquals(1, state.round)
    }

    @Test
    fun `question pauses until the user answers`() {
        val state = AiLoopState()
        state.start(user("换个风格"))
        state.nextRequest(context)
        state.acceptReply(
            replyTools(Triple("c1", "ask_user", """{"question":"要哪种风格？","options":["写实","插画"]}""")),
        )
        state.markQuestion("要哪种风格？")

        val paused = state.acceptToolResult("c1", """{"ok":true,"waiting":true}""", context)
        assertEquals(AiDoneReason.QUESTION, (paused as AiLoopStep.Done).reason)
        assertEquals("要哪种风格？", paused.text)

        assertTrue(state.resume(user("插画")))
        // 回答写进会话，下一轮请求看得到。
        assertEquals("插画", state.messages.last()["content"]?.toString()?.trim('"'))
        assertIs<AiLoopStep.Http>(state.nextRequest(context))
    }

    @Test
    fun `multiple calls are handed out in order`() {
        val state = AiLoopState()
        state.start(user("查两个节点"))
        state.nextRequest(context)

        val first = state.acceptReply(
            replyTools(
                Triple("c1", "get_node", """{"id":1}"""),
                Triple("c2", "get_node", """{"id":2}"""),
            ),
        )
        assertEquals("c1", (first as AiLoopStep.CallTool).callId)

        val second = state.acceptToolResult("c1", """{"node":1}""", context)
        assertEquals("c2", (second as AiLoopStep.CallTool).callId)

        // 两个结果都在，才发下一次请求。
        val next = state.acceptToolResult("c2", """{"node":2}""", context)
        assertIs<AiLoopStep.Http>(next)
        assertEquals(4, state.messages.size)
    }

    @Test
    fun `request body carries system conversation and tools`() {
        val state = AiLoopState()
        state.start(user("你好"))
        val body = (state.nextRequest(context) as AiLoopStep.Http).body
        val root = Json.parseToJsonElement(body).jsonObject

        assertEquals("deepseek-chat", root["model"]?.toString()?.trim('"'))
        assertEquals("auto", root["tool_choice"]?.toString()?.trim('"'))
        assertEquals(10, (root["tools"] as JsonArray).size)

        val messages = root["messages"] as JsonArray
        assertEquals(3, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.toString()?.trim('"'))
        assertEquals("你是助手", messages[0].jsonObject["content"]?.toString()?.trim('"'))
        assertEquals("当前工作流：a.json", messages[1].jsonObject["content"]?.toString()?.trim('"'))
        assertEquals("user", messages[2].jsonObject["role"]?.toString()?.trim('"'))
    }

    @Test
    fun `reply before request is a protocol error`() {
        val state = AiLoopState()
        state.start(user("你好"))

        val step = state.acceptReply(replyText("抢答"))
        assertEquals(AiDoneReason.ERROR, (step as AiLoopStep.Done).reason)
        // 会话不能被半截回复写坏。
        assertEquals(1, state.messages.size)
        assertFalse(state.finished)
    }

    @Test
    fun `tool result without a pending call is rejected`() {
        val state = AiLoopState()
        state.start(user("你好"))
        state.nextRequest(context)

        val step = state.acceptToolResult("c9", """{}""", context)
        assertEquals(AiDoneReason.ERROR, (step as AiLoopStep.Done).reason)
    }

    @Test
    fun `replacing the user turn retries without spending a round`() {
        val state = AiLoopState()
        state.start(
            buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "看图") })
                    add(buildJsonObject { put("type", "image_url") })
                }
            },
        )
        state.nextRequest(context)

        // 模型不认图片：换成纯文本，再取一次请求。
        assertTrue(state.replaceUserTurn(user("看图（只有文件名）")))
        val retry = state.nextRequest(context) as AiLoopStep.Http
        assertEquals(1, state.round)

        val messages = Json.parseToJsonElement(retry.body).jsonObject["messages"] as JsonArray
        val users = messages.filter { it.jsonObject["role"]?.toString()?.trim('"') == "user" }
        assertEquals(1, users.size)
        assertEquals("看图（只有文件名）", users.first().jsonObject["content"]?.toString()?.trim('"'))
    }

    @Test
    fun `stale tool result is rejected instead of being paired`() {
        val state = AiLoopState()
        state.start(user("查"))
        state.nextRequest(context)
        state.acceptReply(replyTools(Triple("c1", "list_graphs", "{}")))

        val step = state.acceptToolResult("c0", """{"ok":true}""", context)
        assertEquals(AiDoneReason.ERROR, (step as AiLoopStep.Done).reason)
        // 结果没被写进会话，队列也没被消耗。
        assertEquals(2, state.messages.size)
        assertIs<AiLoopStep.Http>(state.acceptToolResult("c1", """{"ok":true}""", context))
    }

    @Test
    fun `stop wins over every other step`() {
        val state = AiLoopState()
        state.start(user("你好"))
        state.nextRequest(context)
        state.finish()

        assertEquals(AiDoneReason.STOPPED, (state.nextRequest(context) as AiLoopStep.Done).reason)
        assertEquals(AiDoneReason.STOPPED, (state.acceptReply(replyText("迟到的回复")) as AiLoopStep.Done).reason)
        assertFalse(state.resume())
    }

    @Test
    fun `malformed tool call does not break the conversation`() {
        val state = AiLoopState()
        state.start(user("查"))
        state.nextRequest(context)

        // 没有 function / 名字为空：当作无工具响应，直接收工。
        val weird = buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                add(buildJsonObject { put("id", "c1") })
                add(buildJsonObject { putJsonObject("function") { put("name", "") } })
            }
            put("content", "没法调用")
        }
        val step = state.acceptReply(weird)
        assertEquals(AiDoneReason.ANSWER, (step as AiLoopStep.Done).reason)
        assertEquals("没法调用", step.text)
    }

    @Test
    fun `object shaped arguments are kept as json text`() {
        val state = AiLoopState()
        state.start(user("查"))
        state.nextRequest(context)

        val message = buildJsonObject {
            put("role", "assistant")
            putJsonArray("tool_calls") {
                add(
                    buildJsonObject {
                        put("id", "c1")
                        putJsonObject("function") {
                            put("name", "get_node")
                            putJsonObject("arguments") { put("id", 3) }
                        }
                    },
                )
            }
        }
        val step = state.acceptReply(message) as AiLoopStep.CallTool
        assertEquals("""{"id":3}""", step.args)
    }
}
