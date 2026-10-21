package com.comfypilot.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionHostConfigTest {

    @Test
    fun `defaults apply when the host sends only a base url`() {
        val config = sessionConfigFromJson("""{"baseUrl":"http://192.168.1.9:8188"}""")
        assertEquals("http://192.168.1.9:8188", config.baseUrl)
        assertEquals(20_000L, config.httpTimeoutMs)
        assertEquals(1_000L, config.tickIntervalMs)
        assertEquals(120_000L, config.progressSilenceMs)
    }

    @Test
    fun `trailing slashes are trimmed so paths do not double up`() {
        val config = sessionConfigFromJson("""{"baseUrl":"http://127.0.0.1:18188/"}""")
        assertEquals("http://127.0.0.1:18188", config.baseUrl)
    }

    @Test
    fun `overrides are honoured`() {
        val config = sessionConfigFromJson(
            """{"baseUrl":"http://a:1","httpTimeoutMs":7000,"tickIntervalMs":250,
               "progressSilenceMs":30000,"progressSilenceMaxMs":60000,"reconcileMaxAttempts":2}""",
        )
        assertEquals(7_000L, config.httpTimeoutMs)
        assertEquals(250L, config.tickIntervalMs)
        assertEquals(30_000L, config.progressSilenceMs)
        assertEquals(60_000L, config.progressSilenceMaxMs)
        assertEquals(2, config.reconcileMaxAttempts)
    }

    @Test
    fun `broken input falls back instead of throwing`() {
        assertEquals("http://fallback:1", sessionConfigFromJson("not json", "http://fallback:1").baseUrl)
        assertEquals("http://fallback:1", sessionConfigFromJson("""{"baseUrl":"   "}""", "http://fallback:1").baseUrl)
    }
}
