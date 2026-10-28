package com.comfypilot.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
