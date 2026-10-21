package com.comfypilot.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemStatsTest {

    @Test
    fun `system stats parse memory and the first gpu`() {
        val stats = ApiCodec.decodeSystemStats(
            """
            {
              "system": {
                "os": "posix",
                "ram_total": 34359738368,
                "ram_free": 12884901888,
                "comfyui_version": "0.3.40",
                "python_version": "3.12.0",
                "pytorch_version": "2.6.0"
              },
              "devices": [
                {
                  "name": "cuda:0 NVIDIA RTX",
                  "type": "cuda",
                  "index": 0,
                  "vram_total": 25769803776,
                  "vram_free": 8589934592
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(34359738368L, stats.system.ramTotal)
        assertEquals(12884901888L, stats.system.ramFree)
        assertEquals("0.3.40", stats.system.comfyuiVersion)
        assertEquals(1, stats.devices.size)
        assertEquals(25769803776L, stats.devices[0].vramTotal)
        assertEquals(8589934592L, stats.devices[0].vramFree)
    }

    @Test
    fun `missing fields default to zero instead of failing`() {
        val stats = ApiCodec.decodeSystemStats("""{"system":{},"devices":[]}""")
        assertEquals(0L, stats.system.ramTotal)
        assertTrue(stats.devices.isEmpty())
    }

    @Test
    fun `system stats route is a plain get`() {
        val resolved = ApiPaths.resolve("http://host:8188/", ApiCall.SystemStats)
        assertEquals("GET", resolved.method)
        assertEquals("http://host:8188/system_stats", resolved.url)
    }
}
