package com.comfypilot.core.selftest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfTestTest {
    @Test
    fun selfTestPassesOnJvm() {
        val report = runSelfTest()

        assertTrue(report.ok, report.message)
        assertEquals(1L, report.generationAfterStart)
        assertEquals(1, report.openSocketEffects)
        assertEquals("已连接", report.connectedLabel)
        assertEquals(2L, report.generationAfterSwitch)
        assertEquals("Connecting", report.phaseAfterSwitch)
        assertEquals(1, report.queueRunning)
    }

    @Test
    fun selfTestJsonContainsReport() {
        val json = runSelfTestJson()

        assertTrue(json.contains("\"ok\":true"), json)
        assertTrue(json.contains("\"generationAfterSwitch\":2"), json)
    }
}
