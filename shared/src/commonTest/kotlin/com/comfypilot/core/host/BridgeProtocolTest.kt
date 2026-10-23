package com.comfypilot.core.host

import com.comfypilot.core.net.PreviewFrame
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskProgress
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BridgeProtocolTest {

    @Test
    fun `submit carries optional workflow identity`() {
        val parsed = HostMessage.parse(
            """{"t":"submit","workflow":"{}","workflowName":"Qwen","workflowPath":"workflows/qwen.json"}""",
        )

        val submit = parsed as HostMessage.Submit
        assertEquals("{}", submit.workflowJson)
        assertEquals("Qwen", submit.workflowName)
        assertEquals("workflows/qwen.json", submit.workflowPath)
    }

    @Test
    fun `older submit messages still parse without identity`() {
        val parsed = HostMessage.parse("""{"t":"submit","workflow":"{}"}""")

        val submit = parsed as HostMessage.Submit
        assertEquals("{}", submit.workflowJson)
        assertNull(submit.workflowName)
        assertNull(submit.workflowPath)
    }

    @Test
    fun `binary websocket frame parses from base64`() {
        val parsed = HostMessage.parse("""{"t":"ws.binary","id":7,"base64":"AQID"}""")

        val binary = parsed as HostMessage.SocketBinary
        assertEquals(7L, binary.id)
        assertContentEquals(byteArrayOf(1, 2, 3), binary.bytes)
    }

    @Test
    fun `bridge tasks are newest first`() {
        val state = SessionState(
            tasks = mapOf(
                "old" to task("old", createdAtMs = 1),
                "new" to task("new", createdAtMs = 2),
            ),
        )

        val ids = state.toBridgeJson()["tasks"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }

        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun `bridge state carries preview metadata without image bytes`() {
        val state = SessionState(
            preview = PreviewFrame(
                promptId = "p1",
                nodeId = "3",
                image = byteArrayOf(1, 2, 3),
                receivedAtMs = 42,
            ),
        )

        val preview = state.toBridgeJson()["preview"]!!.jsonObject

        assertEquals("p1", preview["promptId"]!!.jsonPrimitive.content)
        assertEquals(3, preview["bytes"]!!.jsonPrimitive.int)
        assertFalse(preview.containsKey("image"))
    }

    @Test
    fun `bridge task carries step progress for the host`() {
        val state = SessionState(
            tasks = mapOf(
                "t" to task("t", createdAtMs = 1).copy(
                    progress = TaskProgress(value = 8.0, max = 20.0, node = "481"),
                ),
            ),
        )

        val json = state.toBridgeJson()["tasks"]!!.jsonArray.single().jsonObject

        assertEquals(8.0, json["progressValue"]!!.jsonPrimitive.double)
        assertEquals(20.0, json["progressMax"]!!.jsonPrimitive.double)
        assertEquals("481", json["progressNode"]!!.jsonPrimitive.content)
    }

    private fun task(id: String, createdAtMs: Long) = PromptTask(
        id = id,
        promptId = id,
        displayName = id,
        state = TaskState.Completed,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )
}
