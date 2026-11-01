package com.comfypilot.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 流式拼装：正文累积、tool_calls 分片合并、坏行容错。 */
class AiStreamAssemblerTest {

    @Test
    fun `content deltas accumulate and reasoning is kept separately`() {
        val assembler = AiStreamAssembler()
        assembler.feed("""data: {"choices":[{"delta":{"reasoning_content":"想一想"}}]}""")
        assembler.feed("""data: {"choices":[{"delta":{"content":"你好"}}]}""")
        assembler.feed("""data: {"choices":[{"delta":{"content":"，世界"}}]}""")
        assembler.feed("data: [DONE]")

        assertEquals("你好，世界", assembler.text)
        assertEquals("想一想", assembler.thoughts)
        assertEquals("""
            {"role":"assistant","content":"你好，世界"}
        """.trimIndent(), assembler.message().toString())
    }

    @Test
    fun `tool call fragments are merged by index`() {
        val assembler = AiStreamAssembler()
        assembler.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_","arguments":""}}]}}]}""")
        assembler.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"node","arguments":"{\"id\":"}}]}}]}""")
        assembler.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"517}"}}]}}]}""")

        val message = assertNotNull(assembler.message())
        val call = message["tool_calls"]!!.jsonArray.first().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertEquals("get_node", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("""{"id":517}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("assistant", message["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty stream yields no message`() {
        val assembler = AiStreamAssembler()
        assembler.feed("event: ping")
        assembler.feed("data: [DONE]")
        assertNull(assembler.message())
        assertEquals(false, assembler.sawData)
    }

    @Test
    fun `broken line does not throw and keeps earlier content`() {
        val assembler = AiStreamAssembler()
        assembler.feed("""data: {"choices":[{"delta":{"content":"A"}}]}""")
        assembler.feed("data: not json")
        assembler.feed("""data: {"choices":[{"delta":{"content":"B"}}]}""")
        assertEquals("AB", assembler.text)
    }

    @Test
    fun `missing arguments default to empty object`() {
        val assembler = AiStreamAssembler()
        assembler.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"submit"}}]}}]}""")
        val message = assertNotNull(assembler.message())
        val fn = message["tool_calls"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject
        assertEquals("{}", fn["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assembled message is valid json for the next request`() {
        val assembler = AiStreamAssembler()
        assembler.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"list_nodes","arguments":"{}"}}]}}]}""")
        val raw = assertNotNull(assembler.message()).toString()
        // 下一轮会原样发回去，必须能被解析且带 role / type。
        val parsed = Json.parseToJsonElement(raw).jsonObject
        assertEquals("assistant", parsed["role"]!!.jsonPrimitive.content)
        assertEquals("function", parsed["tool_calls"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
    }
}
