package com.comfypilot.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiCodecTest {
    @Test
    fun promptSuccessCarriesPromptId() {
        val response = ApiCodec.decodePromptResponse("""{"prompt_id":"abc-123","number":7}""")
        assertTrue(response.accepted)
        assertEquals("abc-123", response.promptId)
        assertNull(response.failureMessage)
    }

    @Test
    fun promptValidationErrorCarriesMessage() {
        val response = ApiCodec.decodePromptResponse(
            """{"error":{"type":"prompt_outputs_failed_validation","message":"Prompt outputs failed validation","details":"bad node"},"node_errors":{}}""",
        )
        assertFalse(response.accepted)
        assertTrue(response.failureMessage!!.contains("failed validation"))
    }

    @Test
    fun promptNodeErrorsAreFlattenedForTheUser() {
        val response = ApiCodec.decodePromptResponse(
            """
            {
              "error": {"type": "prompt_outputs_failed_validation", "message": "Prompt outputs failed validation"},
              "node_errors": {
                "3": {
                  "class_type": "KSampler",
                  "errors": [{"message": "value not in list", "details": "sampler_name"}]
                },
                "4": {
                  "class_type": "LoadImage",
                  "errors": [{"type": "invalid_image"}]
                }
              }
            }
            """.trimIndent(),
        )

        val message = response.failureMessage!!
        assertTrue(message.contains("failed validation"))
        assertTrue(message.contains("#3 KSampler"))
        assertTrue(message.contains("value not in list"))
        assertTrue(message.contains("#4 LoadImage"))
    }

    @Test
    fun queueParsesPositionArrays() {
        val snapshot = ApiCodec.decodeQueue(
            """{"queue_running":[[0,"p-run",{}, {}, []]],"queue_pending":[[1,"p-wait",{}, {}, []]]}""",
        )
        assertEquals(listOf("p-run"), snapshot.running.map { it.promptId })
        assertEquals(listOf("p-wait"), snapshot.pending.map { it.promptId })
        assertTrue(snapshot.contains("p-wait"))
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun emptyQueueIsEmpty() {
        val snapshot = ApiCodec.decodeQueue("""{"queue_running":[],"queue_pending":[]}""")
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun historySuccessExtractsOutputs() {
        val entry = ApiCodec.decodeHistory(
            """
            {"p1":{"status":{"status_str":"success","completed":true,"messages":[]},
            "outputs":{"9":{"images":[{"filename":"ComfyUI_0001.png","subfolder":"","type":"output"}]}}}}
            """.trimIndent(),
            "p1",
        )
        assertTrue(entry!!.isSuccess)
        assertEquals(1, entry.extractOutputs().size)
        assertEquals("ComfyUI_0001.png", entry.extractOutputs().first().filename)
    }

    @Test
    fun historyErrorIsDetected() {
        val entry = ApiCodec.decodeHistory(
            """
            {"p1":{"status":{"status_str":"error","completed":false,
            "messages":[["execution_error",{"exception_message":"CUDA out of memory","exception_type":"RuntimeError"}]]},
            "outputs":{}}}
            """.trimIndent(),
            "p1",
        )
        assertTrue(entry!!.isError)
        assertNull(entry.errorMessages.firstOrNull { it.isBlank() })
        assertTrue(entry.errorMessages.first().contains("CUDA"))
    }

    @Test
    fun historyUnknownPromptIsNull() {
        assertNull(ApiCodec.decodeHistory("{}", "p1"))
    }

    @Test
    fun historyToleratesUnknownFields() {
        val entry = ApiCodec.decodeHistory(
            """{"p1":{"outputs":{},"status":{"status_str":"success","completed":true,"messages":[]},"future_field":123}}""",
            "p1",
        )
        assertTrue(entry!!.isSuccess)
    }

    @Test
    fun viewUrlEncodesSegments() {
        val url = ApiPaths.viewUrl(
            "http://host:8188/",
            ViewRequest(filename = "a b.png", subfolder = "sub dir", type = "output"),
        )
        assertEquals("http://host:8188/view?filename=a%20b.png&subfolder=sub%20dir&type=output", url)
    }

    @Test
    fun interruptBodyCarriesPromptId() {
        val resolved = ApiPaths.resolve("http://host:8188", ApiCall.Interrupt("p1"))
        assertEquals("/interrupt", resolved.url.removePrefix("http://host:8188"))
        assertEquals("""{"prompt_id":"p1"}""", resolved.body!!.decodeToString())
    }

    @Test
    fun historyPathIsEncoded() {
        val resolved = ApiPaths.resolve("http://host:8188", ApiCall.FetchHistory("a/b"))
        assertEquals("http://host:8188/history/a%2Fb", resolved.url)
    }
}
