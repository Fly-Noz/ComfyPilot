package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.wifi
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReconcileFlowTest {
    private fun recovering(promptId: String = "p1"): Harness {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        harness.acceptSubmit(draft, promptId)
        harness.send(SessionInput.SocketClosed(harness.lease(), 1006, "drop", harness.now))
        harness.openSocketAfterBackoff()
        return harness
    }

    private fun Harness.historyCalls(): Int =
        allEffects.filterIsInstance<SessionEffect.SendHttp>().count { it.tag is RequestTag.History }

    @Test
    fun reconnectQueriesQueueAndHistory() {
        val harness = recovering()

        val phase = assertIs<SessionPhase.Recovering>(harness.state.phase)
        assertEquals(setOf("p1"), phase.pending.keys)
        val calls = harness.allEffects.filterIsInstance<SessionEffect.SendHttp>()
        assertTrue(calls.any { it.tag == RequestTag.Queue })
        assertTrue(calls.any { it.tag == RequestTag.History("p1") })
    }

    @Test
    fun errorInHistoryFailsImmediatelyWithoutRetry() {
        val harness = recovering()
        assertEquals(1, harness.historyCalls())

        harness.httpOk(
            RequestTag.History("p1"),
            """{"p1":{"status":{"status_str":"error","completed":false,
            "messages":[["execution_error",{"exception_message":"CUDA OOM"}]]},"outputs":{}}}""",
        )

        assertEquals(1, harness.historyCalls())
        val task = harness.state.tasks.getValue("p1")
        assertIs<TaskState.Failed>(task.state)
        assertTrue(task.error!!.contains("CUDA OOM"))
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    @Test
    fun unreadableHistoryRetriesWithinBudgetThenGivesUp() {
        val harness = recovering()

        harness.httpFails(RequestTag.History("p1"), "timeout")
        assertEquals(1, harness.historyCalls())
        assertEquals(1, nextRetrySlot(harness).attempts)

        advanceToNextRetry(harness)
        assertEquals(2, harness.historyCalls())

        harness.httpFails(RequestTag.History("p1"), "timeout")
        advanceToNextRetry(harness)
        assertEquals(3, harness.historyCalls())

        harness.httpFails(RequestTag.History("p1"), "timeout")

        assertIs<TaskState.Unknown>(harness.state.tasks.getValue("p1").state)
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    private fun nextRetrySlot(harness: Harness): ReconcileSlot =
        (harness.state.phase as SessionPhase.Recovering).pending.getValue("p1")

    private fun advanceToNextRetry(harness: Harness) {
        val at = requireNotNull(nextRetrySlot(harness).nextAttemptAtMs) { "没有安排重试" }
        harness.advance(at - harness.now)
        harness.send(SessionInput.Tick(harness.now))
    }

    @Test
    fun completedHistoryMarksTaskCompletedWithOutputs() {
        val harness = recovering()

        harness.httpOk(
            RequestTag.History("p1"),
            """{"p1":{"status":{"status_str":"success","completed":true,"messages":[]},
            "outputs":{"9":{"images":[{"filename":"ComfyUI_0001.png","subfolder":"","type":"output"}]}}}}""",
        )

        val task = harness.state.tasks.getValue("p1")
        assertIs<TaskState.Completed>(task.state)
        assertEquals("ComfyUI_0001.png", task.outputs.single().filename)
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    @Test
    fun missingFromQueueAndHistoryBecomesUnknownNotFailed() {
        val harness = recovering()

        harness.httpOk(RequestTag.Queue, """{"queue_running":[],"queue_pending":[]}""")
        harness.httpOk(RequestTag.History("p1"), "{}")

        val task = harness.state.tasks.getValue("p1")
        assertIs<TaskState.Unknown>(task.state)
        assertTrue(harness.notices.any { it is UserNotice.TaskLost })
    }

    @Test
    fun stillQueuedKeepsTaskActiveAndDoublesSilenceBudget() {
        val harness = recovering()
        harness.httpOk(RequestTag.Queue, """{"queue_running":[[0,"p1",{},{},[]]],"queue_pending":[]}""")
        harness.httpOk(RequestTag.History("p1"), "{}")

        val task = harness.state.tasks.getValue("p1")
        assertIs<TaskState.Running>(task.state)
        assertEquals(harness.config.progressSilenceMs * 2, task.silenceBudgetMs)
        assertIs<SessionPhase.Connected>(harness.state.phase)
    }

    @Test
    fun completedTaskRestoredFromHistoryHasNoDuplicate() {
        val restored = PromptTask(
            id = "p1",
            promptId = "p1",
            displayName = null,
            state = TaskState.Running,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
        val harness = Harness(initial = SessionState(tasks = mapOf("p1" to restored)))
        harness.startWithNetwork(wifi)
        harness.send(SessionInput.SocketClosed(harness.lease(), 1006, "drop", harness.now))
        harness.openSocketAfterBackoff()

        harness.httpOk(
            RequestTag.History("p1"),
            """{"p1":{"status":{"status_str":"success","completed":true},"outputs":{"9":{"images":[{"filename":"a.png"}]}}}}""",
        )

        assertEquals(1, harness.state.tasks.size)
        assertIs<TaskState.Completed>(harness.state.tasks.getValue("p1").state)
    }
}
