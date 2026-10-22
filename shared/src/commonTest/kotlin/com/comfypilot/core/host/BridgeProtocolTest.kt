package com.comfypilot.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
