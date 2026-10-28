package com.comfypilot.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 路由器契约：分派、参数解析、白名单与错误文案。 */
class AiToolRouterTest {

    private class FakeEnv : AiToolEnv {
        var submitted = false
        var opened = ""
        var waitSeen = -1

        override fun submitCurrent(): Boolean {
            submitted = true
            return true
        }

        override fun openWorkflow(path: String): Boolean {
            opened = path
            return true
        }

        override fun allowedPaths(): List<String> = listOf("a.json", "b.json")
        override fun queryGraphs(): String = """{"graphs":[]}"""
        override fun queryNodes(argsJson: String): String = """{"nodes":[]}"""
        override fun queryNode(argsJson: String): String = """{"node":null}"""

        override fun taskSummary(waitSeconds: Int): String {
            waitSeen = waitSeconds
            return """[]"""
        }

        override fun lookupPromptGuide(model: String): String = """{"ok":true,"model":"$model"}"""
        override fun fetchDoc(url: String): String = """{"ok":true}"""
    }

    @Test
    fun `stateful tools are left to the runtime`() {
        val env = FakeEnv()
        assertNull(AiToolRouter.execute("propose_edits", "{}", env))
        assertNull(AiToolRouter.execute("ask_user", "{}", env))
        assertNull(AiToolRouter.execute("nope", "{}", env))
    }

    @Test
    fun `submit reports queued and asks for verification`() {
        val env = FakeEnv()
        val result = AiToolRouter.execute("submit", "{}", env)!!
        assertTrue(env.submitted)
        assertTrue(result.contains("\"queued\":true"))
        assertTrue(result.contains("task_status"))
    }

    @Test
    fun `open workflow checks the workspace allowlist`() {
        val env = FakeEnv()
        val blocked = AiToolRouter.execute("open_workflow", """{"path":"c.json"}""", env)!!
        assertTrue(blocked.contains("\"ok\":false"))
        assertEquals("", env.opened)

        val allowed = AiToolRouter.execute("open_workflow", """{"path":"b.json"}""", env)!!
        assertTrue(allowed.contains("\"ok\":true"))
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
        val result = AiToolRouter.execute("lookup_prompt_guide", "not json", env)!!
        assertTrue(result.contains("\"ok\":false"))
        assertTrue(AiToolRouter.execute("list_nodes", "not json", env)!!.contains("nodes"))
    }
}
