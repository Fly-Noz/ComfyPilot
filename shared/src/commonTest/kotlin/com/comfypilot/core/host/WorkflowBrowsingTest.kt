package com.comfypilot.core.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工作流浏览的端到端（宿主协议层）测试：
 * 请求 -> 命令 -> 回投 -> 状态帧。断言的是"宿主看到的东西"，不是内部实现。
 */
class WorkflowBrowsingTest {

    // ------------------------------------------------------------ 列表

    @Test
    fun `catalog request reads the workflows dir with full info`() {
        val driver = connected()
        driver.refreshWorkflows()

        assertEquals("loading", driver.catalogState())
        val command = assertNotNull(
            driver.httpCommand("/userdata?dir=workflows&recurse=true&full_info=true"),
        )
        assertEquals("GET", (command["method"] as JsonPrimitive).content)

        driver.deliver(
            httpResult(
                command,
                """
                [
                  {"path": "demo.json", "size": 12, "modified": 1700000000000},
                  {"path": "sub/note.json"},
                  {"path": "preview.png"}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals("ready", driver.catalogState())
        val entries = driver.catalogEntries()
        assertEquals(listOf("demo", "note"), entries.map { (it["name"] as JsonPrimitive).content })
        assertEquals(12L, (entries[0]["size"] as JsonPrimitive).long)
        assertEquals(1700000000000L, (entries[0]["modifiedMs"] as JsonPrimitive).long)
    }

    @Test
    fun `catalog without a connection fails instead of hanging`() {
        val driver = HostDriver()
        driver.host.start()

        driver.refreshWorkflows()

        assertEquals("failed", driver.catalogState())
        assertTrue(driver.httpUrls().isEmpty(), "未连接时不该发 HTTP 请求")
    }

    @Test
    fun `catalog server error is surfaced as failed`() {
        val driver = connected()
        driver.refreshWorkflows()
        val command = assertNotNull(
            driver.httpCommand("/userdata?dir=workflows&recurse=true&full_info=true"),
        )

        driver.deliver(httpResult(command, body = "", status = 500))

        assertEquals("failed", driver.catalogState())
    }

    // ------------------------------------------------------------ 读取工作流

    @Test
    fun `opening a ui workflow reads it and asks object info once per class`() {
        val driver = connected()
        driver.openWorkflow("sub/demo ui.json")

        assertEquals("sub/demo ui.json", driver.workflowField("loadingPath"))
        val read = assertNotNull(driver.httpCommand("/userdata/sub/demo%20ui.json"))

        driver.deliver(httpResult(read, UI_WORKFLOW))

        assertEquals("UI", driver.workflowField("format"))
        assertEquals("demo ui", driver.workflowField("name"))
        assertEquals("2", driver.workflowField("nodeClasses"))

        val requested = driver.httpUrls().filter { it.contains("/object_info/") }.toSet()
        assertEquals(
            setOf(
                "http://127.0.0.1:8188/object_info/CLIPTextEncode",
                "http://127.0.0.1:8188/object_info/KSampler",
            ),
            requested,
        )

        val ksampler = assertNotNull(driver.httpCommand("/object_info/KSampler"))
        driver.deliver(httpResult(ksampler, KSAMPLER_DEFINITION))

        assertEquals("1", driver.workflowField("nodeInfo"))
        val definition = assertNotNull(driver.host.state.nodeInfo["KSampler"])
        assertEquals(false, definition.missing)
        assertTrue(definition.inputs.any { it.name == "seed" && it.isWidget })
    }

    @Test
    fun `a class the server does not know is marked missing, not invented`() {
        val driver = connected()
        driver.openWorkflow("custom.json")
        val read = assertNotNull(driver.httpCommand("/userdata/custom.json"))
        driver.deliver(httpResult(read, API_WITH_CUSTOM_NODE))

        val command = assertNotNull(driver.httpCommand("/object_info/MyCustomNode"))
        // ComfyUI 对未知类返回 {}（200）。
        driver.deliver(httpResult(command, "{}"))

        val definition = assertNotNull(driver.host.state.nodeInfo["MyCustomNode"])
        assertTrue(definition.missing)
        assertTrue(definition.inputs.isEmpty(), "缺节点时不能凭空造出可调参数")
    }

    @Test
    fun `a late response for a previously selected file is dropped`() {
        val driver = connected()

        driver.openWorkflow("a.json")
        val readA = assertNotNull(driver.httpCommand("/userdata/a.json"))
        driver.openWorkflow("b.json")
        val readB = assertNotNull(driver.httpCommand("/userdata/b.json"))
        assertEquals("b.json", driver.workflowField("loadingPath"))

        driver.deliver(httpResult(readA, API_WORKFLOW))
        assertNull(driver.workflowField("path"), "a 的迟到响应不能覆盖 b")
        assertEquals("b.json", driver.workflowField("loadingPath"))

        driver.deliver(httpResult(readB, API_WORKFLOW))
        assertEquals("b.json", driver.workflowField("path"))
        assertEquals("API", driver.workflowField("format"))
    }

    @Test
    fun `reopening a workflow does not refetch cached node definitions`() {
        val driver = connected()
        driver.openWorkflow("a.json")
        val read = assertNotNull(driver.httpCommand("/userdata/a.json"))
        driver.deliver(httpResult(read, UI_WORKFLOW))
        val samplerRequest = assertNotNull(driver.httpCommand("/object_info/KSampler"))
        val clipRequest = assertNotNull(driver.httpCommand("/object_info/CLIPTextEncode"))
        driver.deliver(httpResult(samplerRequest, KSAMPLER_DEFINITION))
        driver.deliver(httpResult(clipRequest, CLIP_DEFINITION))

        val before = driver.httpUrls().count { it.contains("/object_info/") }
        driver.openWorkflow("a.json")
        val readAgain = assertNotNull(driver.httpCommand("/userdata/a.json"))
        driver.deliver(httpResult(readAgain, UI_WORKFLOW))

        val after = driver.httpUrls().count { it.contains("/object_info/") }
        assertEquals(before, after, "已缓存的类不应重复请求")
    }

    @Test
    fun `an unreadable body becomes a visible error, not an empty document`() {
        val driver = connected()
        driver.openWorkflow("broken.json")
        val read = assertNotNull(driver.httpCommand("/userdata/broken.json"))

        driver.deliver(httpResult(read, "not json"))

        assertNull(driver.workflowField("path"))
        assertTrue(driver.workflowField("error")!!.isNotBlank())
    }

    // ------------------------------------------------------------ helpers

    private fun connected(): HostDriver = HostDriver().also {
        it.host.start()
        it.connect()
    }

    private fun httpResult(command: JsonObject, body: String, status: Int = 200): String =
        buildJsonObject {
            put("t", "http.result")
            put("id", (command["id"] as JsonPrimitive).long)
            put("status", status)
            put("body", body)
        }.toString()

    private companion object {
        val UI_WORKFLOW = """
            {
              "nodes": [
                {"id": 1, "type": "KSampler", "widgets_values": []},
                {"id": 2, "type": "KSampler", "widgets_values": []},
                {"id": 3, "type": "Reroute"},
                {"id": 4, "type": "CLIPTextEncode", "widgets_values": []}
              ],
              "links": []
            }
        """.trimIndent()

        val API_WORKFLOW = """
            {
              "1": {"class_type": "KSampler", "inputs": {}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {}}
            }
        """.trimIndent()

        val API_WITH_CUSTOM_NODE = """
            {
              "1": {"class_type": "MyCustomNode", "inputs": {}}
            }
        """.trimIndent()

        val KSAMPLER_DEFINITION = """
            {
              "KSampler": {
                "input": {
                  "required": {
                    "seed": ["INT", {"default": 0, "control_after_generate": true}],
                    "sampler_name": [["euler", "dpmpp_2m"], {"default": "euler"}],
                    "model": ["MODEL", {"forceInput": true}],
                    "positive": ["CONDITIONING", {"forceInput": true}],
                    "negative": ["CONDITIONING", {"forceInput": true}],
                    "latent_image": ["LATENT", {"forceInput": true}],
                    "steps": ["INT", {"default": 20}],
                    "cfg": ["FLOAT", {"default": 8.0}],
                    "scheduler": [["normal", "karras"], {"default": "normal"}],
                    "denoise": ["FLOAT", {"default": 1.0}]
                  }
                },
                "output": ["LATENT"],
                "output_name": ["LATENT"],
                "display_name": "K 采样器"
              }
            }
        """.trimIndent()

        val CLIP_DEFINITION = """
            {
              "CLIPTextEncode": {
                "input": {
                  "required": {
                    "text": ["STRING", {"default": "", "multiline": true}],
                    "clip": ["CLIP", {"forceInput": true}]
                  }
                },
                "output": ["CONDITIONING"],
                "output_name": ["CONDITIONING"]
              }
            }
        """.trimIndent()
    }
}
