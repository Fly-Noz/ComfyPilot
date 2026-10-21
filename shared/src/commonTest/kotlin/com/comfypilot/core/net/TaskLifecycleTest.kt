package com.comfypilot.core.net

import com.comfypilot.core.Harness
import com.comfypilot.core.WORKFLOW
import com.comfypilot.core.wifi
import com.comfypilot.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskLifecycleTest {
    @Test
    fun draftIsRekeyedByPromptIdWithoutDuplicates() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        assertEquals(1, harness.state.tasks.size)

        harness.acceptSubmit(draft, "p1")

        assertEquals(1, harness.state.tasks.size)
        assertEquals("p1", harness.state.tasks.getValue("p1").id)
        assertIs<TaskState.Queued>(harness.state.tasks.getValue("p1").state)
    }

    @Test
    fun rejectedPromptBecomesFailed() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()

        harness.send(
            SessionInput.HttpResult(
                harness.lease(),
                RequestTag.Submit(draft, WORKFLOW),
                HttpOutcome.Received(
                    400,
                    """{"error":{"type":"prompt_outputs_failed_validation","message":"Prompt outputs failed validation"}}""",
                ),
                harness.now,
            ),
        )

        val task = harness.state.tasks.getValue(draft)
        val state = assertIs<TaskState.Failed>(task.state)
        assertTrue(state.message!!.contains("validation"))
    }

    @Test
    fun submitWithoutConnectionIsRejected() {
        val harness = Harness()

        harness.send(SessionInput.SubmitRequested(WORKFLOW, harness.now))

        assertTrue(harness.state.tasks.isEmpty())
        assertTrue(harness.notices.any { it is UserNotice.SubmitRejected })
    }

    @Test
    fun invalidWorkflowJsonIsRejected() {
        val harness = Harness()
        harness.startWithNetwork(wifi)

        harness.send(SessionInput.SubmitRequested("{not json", harness.now))

        assertTrue(harness.state.tasks.isEmpty())
        assertTrue(harness.notices.any { it is UserNotice.SubmitRejected })
    }

    @Test
    fun cancelMarksCancelledAndSendsInterrupt() {
        val harness = Harness()
        harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()
        harness.acceptSubmit(draft, "p1")

        harness.send(SessionInput.CancelRequested("p1", harness.now))

        assertIs<TaskState.Cancelled>(harness.state.tasks.getValue("p1").state)
        val interrupts = harness.effects.filterIsInstance<SessionEffect.SendHttp>()
            .filter { it.tag is RequestTag.Interrupt }
        assertEquals(1, interrupts.size)
    }

    @Test
    fun executionErrorEventFailsTaskImmediately() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")

        harness.ws(
            lease,
            """{"type":"execution_error","data":{"prompt_id":"p1","node_id":"9","exception_message":"boom"}}""",
        )

        assertIs<TaskState.Failed>(harness.state.tasks.getValue("p1").state)
        assertTrue(harness.notices.any { it is UserNotice.TaskFailed })
    }

    @Test
    fun executedEventAccumulatesOutputs() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        harness.ws(
            lease,
            """{"type":"executed","data":{"prompt_id":"p1","node":"9","output":{"images":[{"filename":"a.png","subfolder":"","type":"output"}]}}}""",
        )

        val task = harness.state.tasks.getValue("p1")
        assertEquals(1, task.outputs.size)
        assertIs<TaskState.Running>(task.state)
    }

    @Test
    fun progressUpdatesPercent() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        harness.ws(lease, """{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        harness.ws(lease, """{"type":"progress","data":{"prompt_id":"p1","node":"9","value":3,"max":4}}""")

        assertEquals(75, harness.state.tasks.getValue("p1").progress?.percent)
    }

    @Test
    fun reconnectMarksInFlightSubmitUnknown() {
        val harness = Harness()
        val lease = harness.startWithNetwork(wifi)
        val draft = harness.submitDraft()

        harness.send(SessionInput.SocketClosed(lease, 1006, "drop", harness.now))
        harness.openSocketAfterBackoff()

        val task = harness.state.tasks.getValue(draft)
        assertIs<TaskState.Unknown>(task.state)
    }
}
