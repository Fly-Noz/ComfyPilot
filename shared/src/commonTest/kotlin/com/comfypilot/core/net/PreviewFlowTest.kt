package com.comfypilot.core.net

import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import com.comfypilot.core.transport.JitterSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewFlowTest {

    private val config = SessionConfig(baseUrl = "http://host:8188")
    private val lease = ConnectionLease(
        generation = 1,
        leaseId = 1,
        clientId = "client",
        baseUrl = config.baseUrl,
        wsUrl = "ws://host:8188/ws?clientId=client",
    )
    private val reducer = SessionReducer(
        config,
        object : JitterSource {
            override fun nextSigned(): Double = 0.0
        },
    )

    @Test
    fun `binary preview attaches to the running task`() {
        val state = connected(taskState = TaskState.Running)
        val result = reducer.reduce(
            state,
            SessionInput.SocketBinary(lease, frame(promptId = "p1"), nowMs = 100),
        )

        val preview = result.state.preview
        assertEquals("p1", preview?.promptId)
        assertEquals(2, preview?.image?.size)
        assertEquals(100L, result.state.tasks["p1"]?.lastProgressAtMs)
    }

    @Test
    fun `preview without metadata uses the only active task`() {
        val state = connected(taskState = TaskState.Running)
        val result = reducer.reduce(
            state,
            SessionInput.SocketBinary(lease, frame(promptId = null), nowMs = 200),
        )

        assertEquals("p1", result.state.preview?.promptId)
    }

    @Test
    fun `preview for a finished task or stale lease is ignored`() {
        val done = connected(taskState = TaskState.Completed)
        val finished = reducer.reduce(
            done,
            SessionInput.SocketBinary(lease, frame(promptId = "p1"), nowMs = 300),
        )
        assertNull(finished.state.preview)

        val staleLease = lease.copy(leaseId = 99)
        val stale = reducer.reduce(
            connected(taskState = TaskState.Running),
            SessionInput.SocketBinary(staleLease, frame(promptId = "p1"), nowMs = 400),
        )
        assertNull(stale.state.preview)
        assertTrue(stale.effects.isEmpty())
    }

    private fun connected(taskState: TaskState): SessionState = SessionState(
        generation = 1,
        phase = SessionPhase.Connected(lease, sinceMs = 0),
        tasks = mapOf(
            "p1" to PromptTask(
                id = "p1",
                promptId = "p1",
                displayName = null,
                state = taskState,
                createdAtMs = 0,
                updatedAtMs = 0,
            ),
        ),
    )

    private fun frame(promptId: String?): ByteArray {
        val meta = if (promptId == null) {
            null
        } else {
            """{"prompt_id":"$promptId","node_id":"3"}""".encodeToByteArray()
        }
        val type = if (meta == null) 1 else 4
        val head = intBytes(type) + if (meta == null) ByteArray(0) else intBytes(meta.size) + meta
        return head + byteArrayOf(1, 2)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
