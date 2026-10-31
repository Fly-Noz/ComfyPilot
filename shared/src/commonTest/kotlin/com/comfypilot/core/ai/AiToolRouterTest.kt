package com.comfypilot.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 路由器契约：分派、参数解析、白名单、指令透传与结果措辞。 */
class AiToolRouterTest {

    /**
     * 默认像 Android：宿主能力都在本进程里，直接返回结果。
     * [defer] 打开后像鸿蒙：所有宿主能力都变成指令。
     */
    private class FakeEnv : AiToolEnv {
        var submitted = 0
        var opened = ""
        var waitSeen = -1
        var defer = false

        override fun allowedPaths(): List<String> = listOf("a.json", "b.json")

        override fun submit(): AiToolOutcome {
            if (defer) return AiToolOutcome.CallHost("submit")
            submitted++
            return AiToolOutcome.Value("""{"ok":true}""")
        }

        override fun openWorkflow(path: String): AiToolOutcome {
            if (defer) return AiToolOutcome.CallHost("open_workflow", """{"path":"$path"}""")
            opened = path
            return AiToolOutcome.Value("""{"ok":true}""")
        }

        override fun queryGraphs(): AiToolOutcome =
            if (defer) AiToolOutcome.CallHost("list_graphs") else AiToolOutcome.Value("""{"graphs":[]}""")

        override fun queryNodes(argsJson: String): AiToolOutcome =
            if (defer) {
                AiToolOutcome.CallHost("list_nodes", argsJson)
            } else {
                AiToolOutcome.Value("""{"nodes":[]}""")
            }

        override fun queryNode(argsJson: String): AiToolOutcome =
            if (defer) AiToolOutcome.CallHost("get_node", argsJson) else AiToolOutcome.Value("""{"node":null}""")

        override fun taskSummary(waitSeconds: Int): AiToolOutcome {
            waitSeen = waitSeconds
            return if (defer) {
                AiToolOutcome.CallHost("task_status", """{"wait_seconds":$waitSeconds}""")
            } else {
                AiToolOutcome.Value("[]")
            }
        }

        override fun lookupPromptGuide(model: String): AiToolOutcome =
            if (defer) AiToolOutcome.Fetch("https://example.com/$model") else AiToolOutcome.Value("""{"ok":true}""")

        override fun fetchDoc(url: String): AiToolOutcome =
            if (defer) AiToolOutcome.Fetch(url) else AiToolOutcome.Value("""{"ok":true}""")

        var modelClassSeen = ""

        override fun hostStatus(): AiToolOutcome =
            if (defer) AiToolOutcome.CallHost("host_status") else AiToolOutcome.Value("""{"devices":[]}""")

        override fun modelOptions(loaderClass: String): AiToolOutcome {
            modelClassSeen = loaderClass
            return if (defer) {
                AiToolOutcome.CallHost("list_models", """{"class":"$loaderClass"}""")
            } else {
                AiToolOutcome.Value("""{"ok":true}""")
            }
        }
    }

    @Test
    fun `stateful tools are left to the runtime`() {
        val env = FakeEnv()
        assertNull(AiToolRouter.execute("propose_edits", "{}", env))
        assertNull(AiToolRouter.execute("ask_user", "{}", env))
        assertNull(AiToolRouter.execute("nope", "{}", env))
    }

    @Test
    fun `submit turns a bare ok into the verification hint`() {
        val env = FakeEnv()
        val outcome = AiToolRouter.execute("submit", "{}", env)
        assertIs<AiToolOutcome.Value>(outcome)
        assertEquals(1, env.submitted)

        val result = AiToolRouter.formatResult("submit", outcome.json)
        assertTrue(result.contains("\"queued\":true"))
        assertTrue(result.contains("task_status"))
    }

    @Test
    fun `failed submit keeps the rejection wording`() {
        val result = AiToolRouter.formatResult("submit", """{"ok":false}""")
        assertEquals(AiToolRouter.SUBMIT_REJECTED, result)
        // 坏 JSON 也不该被当成成功。
        assertEquals(AiToolRouter.SUBMIT_REJECTED, AiToolRouter.formatResult("submit", "not json"))
    }

    @Test
    fun `open workflow checks the workspace allowlist`() {
        val env = FakeEnv()
        val blocked = AiToolRouter.execute("open_workflow", """{"path":"c.json"}""", env)
        assertIs<AiToolOutcome.Value>(blocked)
        assertTrue(blocked.json.contains("\"ok\":false"))
        assertEquals("", env.opened)

        val allowed = AiToolRouter.execute("open_workflow", """{"path":"b.json"}""", env)
        assertIs<AiToolOutcome.Value>(allowed)
        assertTrue(allowed.json.contains("\"ok\":true"))
        assertEquals("b.json", env.opened)
    }

    @Test
    fun `task status passes wait seconds and clamps them`() {
        val env = FakeEnv()
        AiToolRouter.execute("task_status", """{"wait_seconds":30}""", env)
        assertEquals(30, env.waitSeen)
        AiToolRouter.execute("task_status", """{"wait_seconds":999}""", env)
        assertEquals(120, env.waitSeen)
        AiToolRouter.execute("task_status", "{}", env)
        assertEquals(0, env.waitSeen)
    }

    @Test
    fun `malformed args do not throw`() {
        val env = FakeEnv()
        val result = AiToolRouter.execute("lookup_prompt_guide", "not json", env)
        assertIs<AiToolOutcome.Value>(result)
        assertTrue(result.json.contains("\"ok\":false"))
        assertIs<AiToolOutcome.Value>(AiToolRouter.execute("list_nodes", "not json", env))
    }

    @Test
    fun `host instructions pass through untouched`() {
        val env = FakeEnv().apply { defer = true }

        assertEquals(AiToolOutcome.CallHost("submit"), AiToolRouter.execute("submit", "{}", env))
        assertEquals(AiToolOutcome.CallHost("list_graphs"), AiToolRouter.execute("list_graphs", "{}", env))
        assertEquals(
            AiToolOutcome.CallHost("get_node", """{"id":3}"""),
            AiToolRouter.execute("get_node", """{"id":3}""", env),
        )
        assertEquals(
            AiToolOutcome.Fetch("https://example.com/flux1-dev"),
            AiToolRouter.execute("lookup_prompt_guide", """{"model":"flux1-dev"}""", env),
        )
        assertEquals(
            AiToolOutcome.Fetch("https://example.com/doc"),
            AiToolRouter.execute("fetch_doc", """{"url":"https://example.com/doc"}""", env),
        )
        // 白名单校验仍在路由器里，不会把非法路径丢给宿主。
        val blocked = AiToolRouter.execute("open_workflow", """{"path":"c.json"}""", env)
        assertIs<AiToolOutcome.Value>(blocked)
    }

    @Test
    fun `format result leaves host payloads alone`() {
        assertEquals("""{"nodes":[1,2]}""", AiToolRouter.formatResult("list_nodes", """{"nodes":[1,2]}"""))
        assertEquals("[]", AiToolRouter.formatResult("task_status", "[]"))
    }
    @Test
    fun `list_models maps the kind to the loader class`() {
        val env = FakeEnv()
        AiToolRouter.execute("list_models", """{"kind":"lora"}""", env)
        assertEquals("LoraLoader", env.modelClassSeen)

        AiToolRouter.execute("list_models", """{"kind":"checkpoint"}""", env)
        assertEquals("CheckpointLoaderSimple", env.modelClassSeen)

        // embedding 走 /embeddings，没有对应的 loader 类，用空串表示。
        AiToolRouter.execute("list_models", """{"kind":"embedding"}""", env)
        assertEquals("", env.modelClassSeen)
    }

    @Test
    fun `unknown model kind is rejected and lists what is accepted`() {
        val env = FakeEnv()
        val outcome = AiToolRouter.execute("list_models", """{"kind":"sdxl"}""", env)
        assertIs<AiToolOutcome.Value>(outcome)
        assertTrue(outcome.json.contains("\"ok\":false"))
        assertTrue(outcome.json.contains("checkpoint"), outcome.json)
        // 参数被拦下时不该惊动宿主。
        assertEquals("", env.modelClassSeen)
    }

    @Test
    fun `host status is turned into gigabytes with a readable summary`() {
        val raw = """{"system":{"os":"nt","ram_total":68719476736,"ram_free":22858956800,"comfyui_version":"0.3.40","python_version":"3.11.9","pytorch_version":"2.4.1"},"devices":[{"name":"cuda:0 NVIDIA GeForce RTX 4090","type":"cuda","index":0,"vram_total":25769803776,"vram_free":8589934592}]}"""
        val formatted = AiToolRouter.formatResult("host_status", raw)
        assertTrue(formatted.contains("RTX 4090"), formatted)
        // 字节数换成 GB：模型读 25769803776 既费 token 又容易算错。
        assertTrue(formatted.contains("24.0"), formatted)
        assertTrue(formatted.contains("8.0"), formatted)
        assertTrue(formatted.contains("0.3.40"), formatted)
    }

    @Test
    fun `host status pass-through keeps the host error`() {
        val raw = """{"ok":false,"error":"读不到主机状态：timeout"}"""
        assertEquals(raw, AiToolRouter.formatResult("host_status", raw))
    }

    @Test
    fun `model options are extracted from object_info`() {
        val raw = """{"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["a.safetensors","b.safetensors"],{"tooltip":"x"}]}},"output":[]}}"""
        val formatted = AiToolRouter.formatResult("list_models", raw)
        assertTrue(formatted.contains("a.safetensors"), formatted)
        assertTrue(formatted.contains("\"count\":2"), formatted)
        // 只留候选，不要把整份节点定义塞给模型。
        assertFalse(formatted.contains("tooltip"))
    }

    @Test
    fun `embeddings list becomes the same options shape`() {
        val formatted = AiToolRouter.formatResult("list_models", """["easynegative","badhandv4"]""")
        assertTrue(formatted.contains("easynegative"), formatted)
        assertTrue(formatted.contains("embedding"), formatted)
    }

}
