package com.comfypilot.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EventDecoderTest {
    @Test
    fun statusCarriesQueueRemaining() {
        val event = EventDecoder.decode(
            """{"type":"status","data":{"status":{"exec_info":{"queue_remaining":3}}}}""",
        )
        assertEquals(3, assertIs<ComfyEvent.Status>(event).queueRemaining)
    }

    @Test
    fun executionStart() {
        val event = EventDecoder.decode("""{"type":"execution_start","data":{"prompt_id":"p1"}}""")
        assertEquals("p1", assertIs<ComfyEvent.ExecutionStart>(event).promptId)
    }

    @Test
    fun progress() {
        val event = EventDecoder.decode(
            """{"type":"progress","data":{"prompt_id":"p1","node":"9","value":4,"max":20}}""",
        )
        val progress = assertIs<ComfyEvent.Progress>(event)
        assertEquals(4.0, progress.value)
        assertEquals(20.0, progress.max)
        assertEquals("9", progress.node)
    }

    @Test
    fun executedCarriesNodeOutput() {
        val event = EventDecoder.decode(
            """{"type":"executed","data":{"prompt_id":"p1","node":"9","output":{"images":[{"filename":"a.png"}]}}}""",
        )
        assertEquals("p1", assertIs<ComfyEvent.Executed>(event).promptId)
    }

    @Test
    fun executionError() {
        val event = EventDecoder.decode(
            """{"type":"execution_error","data":{"prompt_id":"p1","node_id":"9","exception_message":"boom"}}""",
        )
        val error = assertIs<ComfyEvent.ExecutionError>(event)
        assertEquals("boom", error.message)
        assertEquals("9", error.nodeId)
    }

    @Test
    fun executionInterrupted() {
        val event = EventDecoder.decode("""{"type":"execution_interrupted","data":{"prompt_id":"p1"}}""")
        assertIs<ComfyEvent.ExecutionInterrupted>(event)
    }

    @Test
    fun executingIdleIsNullPrompt() {
        val event = EventDecoder.decode("""{"type":"executing","data":{"node":null,"prompt_id":null}}""")
        val executing = assertIs<ComfyEvent.Executing>(event)
        assertNull(executing.promptId)
        assertNull(executing.node)
    }

    @Test
    fun binaryPreviewIsNotMistakenForText() {
        val event = EventDecoder.decode("""{"type":"b_preview","data":{"node":"9"}}""")
        assertIs<ComfyEvent.BinaryPreview>(event)
    }

    @Test
    fun unknownTypeIsIgnoredNotCrashing() {
        val event = EventDecoder.decode("""{"type":"future_event","data":{"x":1}}""")
        assertEquals("future_event", assertIs<ComfyEvent.Unknown>(event).type)
    }

    @Test
    fun malformedJsonIsUnknown() {
        assertIs<ComfyEvent.Unknown>(EventDecoder.decode("not json"))
    }
}
