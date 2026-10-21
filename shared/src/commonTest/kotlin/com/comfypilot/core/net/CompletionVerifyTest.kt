package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 真机暴露的缺口：ComfyUI 用 `executing(node=null)` 宣告一个 prompt 跑完。
 * 这是"提示"，但必须立刻用 /history 复核，而不是等静默看门狗（默认 2 分钟）顺带重连。
 */
class CompletionVerifyTest {
    private val successHistory = """
        {"p1":{"prompt":[],"outputs":{"9":{"images":[{"filename":"ComfyPilot_mock_p1.png","subfolder":"","type":"output"}]}},
        "status":{"status_str":"success","completed":true,"messages":[]}}}
    """.trimIndent()

    private fun Harness.runningTask(): ConnectionLease {
        val lease = startWithNetwork()
        val draft = submitDraft()
        acceptSubmit(draft, "p1")
        ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        ws(lease, """{"type":"progress","data":{"prompt_id":"p1","node":"3","value":50,"max":100}}""")
        return lease
    }

    @Test
    fun executingWithNullNodeTriggersHistoryOnSameLease() {
        val h = Harness()
        val lease = h.runningTask()
        val generationBefore = h.state.generation

        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":null}}""")

        // 不重连、不换租约、不递增 generation
        assertEquals(lease.leaseId, h.lease().leaseId)
        assertEquals(generationBefore, h.state.generation)
        assertTrue(h.allEffects.none { it is SessionEffect.CloseSocket })
        // 复核请求已经发出
        assertTrue(
            h.effects.any { it is SessionEffect.SendHttp && it.tag == RequestTag.History("p1") },
            "expected /history probe, got ${h.effects}",
        )
        assertIs<SessionPhase.Recovering>(h.state.phase)
    }

    @Test
    fun historySuccessCompletesTaskAndReturnsToConnected() {
        val h = Harness()
        val lease = h.runningTask()
        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":null}}""")

        h.httpOk(RequestTag.History("p1"), successHistory)

        val task = h.state.tasks.getValue("p1")
        assertEquals(TaskState.Completed, task.state)
        assertEquals("ComfyPilot_mock_p1.png", task.outputs.single().filename)
        assertIs<SessionPhase.Connected>(h.state.phase)
        assertEquals(lease.leaseId, h.lease().leaseId)
    }

    @Test
    fun executingWithNodeDoesNotStartVerification() {
        val h = Harness()
        val lease = h.runningTask()

        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":"3"}}""")

        assertTrue(h.effects.none { it is SessionEffect.SendHttp })
        assertIs<SessionPhase.Connected>(h.state.phase)
    }

    @Test
    fun emptyHistoryConsultsQueueBeforeDeclaringAnything() {
        val h = Harness()
        val lease = h.runningTask()
        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":null}}""")

        // 可读但还没有记录：既不能判完成，也不能判丢失
        h.httpOk(RequestTag.History("p1"), "{}")
        assertEquals(TaskState.Running, h.state.tasks.getValue("p1").state)
        assertIs<SessionPhase.Recovering>(h.state.phase)

        h.advance(500)
        h.send(SessionInput.Tick(h.now))
        assertTrue(h.effects.any { it is SessionEffect.SendHttp && it.tag == RequestTag.Queue })

        h.httpOk(
            RequestTag.Queue,
            """{"queue_running":[[0,"p1",{},{},[]]],"queue_pending":[]}""",
        )
        assertEquals(TaskState.Running, h.state.tasks.getValue("p1").state)
        assertIs<SessionPhase.Connected>(h.state.phase)
    }

    @Test
    fun emptyHistoryAndAbsentQueueMarksUnknownOnlyAfterQueueSnapshot() {
        val h = Harness()
        val lease = h.runningTask()
        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":null}}""")

        h.httpOk(RequestTag.History("p1"), "{}")
        // 队列快照之前不许下结论
        assertIs<TaskState.Running>(h.state.tasks.getValue("p1").state)

        h.httpOk(RequestTag.Queue, """{"queue_running":[],"queue_pending":[]}""")

        assertIs<TaskState.Unknown>(h.state.tasks.getValue("p1").state)
        assertTrue(h.notices.any { it is UserNotice.TaskLost })
    }

    @Test
    fun submitStillWorksAfterCompletionVerify() {
        val h = Harness()
        val lease = h.runningTask()
        h.ws(lease, """{"type":"executing","data":{"prompt_id":"p1","node":null}}""")
        h.httpOk(RequestTag.History("p1"), successHistory)

        val draft = h.submitDraft()
        h.acceptSubmit(draft, "p2", lease)
        h.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p2"}}""")

        assertEquals(TaskState.Running, h.state.tasks.getValue("p2").state)
        assertEquals(TaskState.Completed, h.state.tasks.getValue("p1").state)
        assertIs<SessionPhase.Connected>(h.state.phase)
    }
}
