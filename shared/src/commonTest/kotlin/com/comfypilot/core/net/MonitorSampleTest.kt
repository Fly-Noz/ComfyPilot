package com.comfypilot.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Crystools 监控帧的宽松解析：字段缺失、嵌套/平铺、数字字符串、`-1` 哨兵都要能兜住。
 */
class MonitorSampleTest {

    @Test
    fun `parses the frame crystools pushes over the websocket`() {
        val sample = assertNotNull(MonitorSample.parse(REAL_FRAME, receivedAtMs = 1_234L))

        assertEquals(5.3, sample.cpuPercent!!)
        assertEquals(50.0, sample.ramPercent!!)
        assertEquals(2.0, sample.gpuPercent!!)
        assertEquals(13.359298668783588, sample.vramPercent!!)
        assertEquals(49.0, sample.temperatureC!!)
        assertNull(sample.gpuName)
        assertEquals(1_234L, sample.receivedAtMs)
    }

    @Test
    fun `malformed and unrelated frames are ignored`() {
        // 截断的 JSON：不能抛异常
        assertNull(MonitorSample.parse("""{"type":"crystools.monitor","data":{""", 1))
        assertNull(MonitorSample.parse("not json at all", 1))
        // 别的 socket 消息类型
        assertNull(MonitorSample.parse("""{"type":"progress","data":{"value":1,"max":2}}""", 1))
        // 是监控类型但没有 data
        assertNull(MonitorSample.parse("""{"type":"crystools.monitor"}""", 1))
    }

    @Test
    fun `empty gpu list keeps cpu and ram but leaves gpu unknown`() {
        val sample = assertNotNull(
            MonitorSample.parse(
                """{"type":"crystools.monitor","data":{"cpu_utilization":7,"ram_used_percent":42,"gpus":[]}}""",
                receivedAtMs = 9L,
            ),
        )

        assertEquals(7.0, sample.cpuPercent!!)
        assertEquals(42.0, sample.ramPercent!!)
        assertNull(sample.gpuPercent)
        assertNull(sample.vramPercent)
        assertNull(sample.temperatureC)
    }

    @Test
    fun `flat gpu fields and string numbers are accepted`() {
        val sample = assertNotNull(
            MonitorSample.parse(
                """
                {"type":"crystools.monitor","data":{
                  "cpu_utilization":"12.5","ram_used_percent":"33",
                  "gpu_utilization":"77","gpu_temperature":"61",
                  "vram_used_percent":"44","gpu_name":"RTX 4060"
                }}
                """.trimIndent(),
                receivedAtMs = 10L,
            ),
        )

        assertEquals(12.5, sample.cpuPercent!!)
        assertEquals(33.0, sample.ramPercent!!)
        assertEquals(77.0, sample.gpuPercent!!)
        assertEquals(44.0, sample.vramPercent!!)
        assertEquals(61.0, sample.temperatureC!!)
        assertEquals("RTX 4060", sample.gpuName)
    }

    @Test
    fun `minus one sentinel means unknown`() {
        val sample = assertNotNull(
            MonitorSample.parse(
                """
                {"type":"crystools.monitor","data":{
                  "cpu_utilization":-1,"ram_used_percent":-1,
                  "gpus":[{"gpu_utilization":-1,"gpu_temperature":-1,"vram_used_percent":-1}]
                }}
                """.trimIndent(),
                receivedAtMs = 11L,
            ),
        )

        assertNull(sample.cpuPercent)
        assertNull(sample.ramPercent)
        assertNull(sample.gpuPercent)
        assertNull(sample.vramPercent)
        assertNull(sample.temperatureC)
    }

    @Test
    fun `gpus may be a single object instead of an array`() {
        val sample = assertNotNull(
            MonitorSample.parse(
                """{"type":"crystools.monitor","data":{"gpus":{"gpu_utilization":8,"gpu_temperature":55}}}""",
                receivedAtMs = 12L,
            ),
        )

        assertEquals(8.0, sample.gpuPercent!!)
        assertEquals(55.0, sample.temperatureC!!)
    }

    private companion object {
        /** 真机抓到的帧（RTX 4060 + Crystools），不是编的。 */
        const val REAL_FRAME = """{"type":"crystools.monitor","data":{
            "cpu_utilization":5.3,
            "ram_total":16984068096,"ram_used":8486203392,"ram_used_percent":50,
            "hdd_total":-1,"hdd_used":-1,"hdd_used_percent":-1,
            "device_type":"cuda",
            "gpus":[{"gpu_utilization":2,"gpu_temperature":49,"vram_total":8585740288,
                     "vram_used":1146994688,"vram_used_percent":13.359298668783588}]}}"""
    }
}