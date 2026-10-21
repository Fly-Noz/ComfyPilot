package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解析层的测试重点不是"能读好数据"，而是"读不懂时不要装懂"：
 * 一份被猜错的工作流会真的在用户机器上跑起来。
 */
class WorkflowCodecTest {

    // ------------------------------------------------------------ 格式识别

    @Test
    fun `api format is recognised by class_type on every entry`() {
        val parsed = WorkflowCodec.parseDocument(
            """
            {
              "1": {"class_type": "CheckpointLoaderSimple", "inputs": {}},
              "2": {"class_type": "KSampler", "inputs": {}},
              "3": {"class_type": "KSampler", "inputs": {}}
            }
            """.trimIndent(),
        )
        val ok = parsed as? WorkflowCodec.Parsed.Ok
        assertNotNull(ok)
        assertEquals(WorkflowFormat.API, ok.format)
        assertEquals(listOf("CheckpointLoaderSimple", "KSampler"), ok.nodeClasses)
    }

    @Test
    fun `ui format is recognised by nodes and skips frontend-only types`() {
        val parsed = WorkflowCodec.parseDocument(
            """
            {
              "nodes": [
                {"id": 1, "type": "KSampler", "widgets_values": []},
                {"id": 2, "type": "Reroute"},
                {"id": 3, "type": "Note"},
                {"id": 4, "type": "KSampler"},
                {"id": 5, "type": "CLIPTextEncode"}
              ],
              "links": []
            }
            """.trimIndent(),
        )
        val ok = parsed as? WorkflowCodec.Parsed.Ok
        assertNotNull(ok)
        assertEquals(WorkflowFormat.UI, ok.format)
        assertEquals(listOf("CLIPTextEncode", "KSampler"), ok.nodeClasses)
    }

    @Test
    fun `an export that is neither format is rejected with a reason`() {
        val parsed = WorkflowCodec.parseDocument("""{"foo": 1, "bar": {"inputs": {}}}""")
        val bad = parsed as? WorkflowCodec.Parsed.Bad
        assertNotNull(bad)
        assertTrue(bad.reason.contains("class_type"))
    }

    @Test
    fun `non-json and empty objects are rejected`() {
        assertTrue(WorkflowCodec.parseDocument("not json") is WorkflowCodec.Parsed.Bad)
        assertTrue(WorkflowCodec.parseDocument("{}") is WorkflowCodec.Parsed.Bad)
    }

    // ------------------------------------------------------------ 列表

    @Test
    fun `catalog keeps json only and sorts by path`() {
        val entries = WorkflowCodec.parseCatalog(
            """
            [
              {"path": "z/slow.json", "size": 10, "modified": 1700000000000},
              {"path": "a.json"},
              {"path": "notes.txt"},
              {"path": "a.json"}
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("a.json", "z/slow.json"), entries.map { it.path })
        assertEquals("slow", entries[1].name)
        assertEquals(10L, entries[1].sizeBytes)
        assertEquals(1700000000000L, entries[1].modifiedAtMs)
    }

    @Test
    fun `catalog also understands plain path arrays from older servers`() {
        val entries = WorkflowCodec.parseCatalog("""["a.json","sub/b.json"]""")
        assertEquals(listOf("a", "b"), entries.map { it.name })
        assertNull(entries[0].modifiedAtMs)
    }

    @Test
    fun `catalog tolerates junk entries instead of failing the whole list`() {
        val entries = WorkflowCodec.parseCatalog("""[42, {"nope": 1}, {"path": "ok.json"}]""")
        assertEquals(listOf("ok.json"), entries.map { it.path })
    }

    // ------------------------------------------------------------ 节点定义

    @Test
    fun `legacy combo and forceInput are read from object info`() {
        val def = assertNotNull(
            WorkflowCodec.parseNodeDefinition(
                """
                {
                  "KSampler": {
                    "input": {
                      "required": {
                        "seed": ["INT", {"default": 7, "min": 0, "control_after_generate": true}],
                        "sampler_name": [["euler", "dpmpp_2m"], {"default": "euler"}],
                        "model": ["MODEL", {"forceInput": true}],
                        "text": ["STRING", {"default": "hi", "tooltip": "提示词"}]
                      },
                      "optional": {
                        "denoise": ["FLOAT", {"default": 1.0}]
                      }
                    },
                    "output": ["LATENT"],
                    "output_name": ["LATENT"],
                    "display_name": "K 采样器"
                  }
                }
                """.trimIndent(),
                "KSampler",
            ),
        )

        assertEquals("K 采样器", def.displayName)
        assertEquals(listOf("LATENT"), def.outputs)
        val seed = assertNotNull(def.inputs.firstOrNull { it.name == "seed" })
        assertEquals("INT", seed.type)
        assertTrue(seed.isWidget)
        assertTrue(seed.controlAfterGenerate)

        val sampler = assertNotNull(def.inputs.firstOrNull { it.name == "sampler_name" })
        assertEquals("COMBO", sampler.type)
        assertEquals(listOf("euler", "dpmpp_2m"), sampler.options)

        val model = assertNotNull(def.inputs.firstOrNull { it.name == "model" })
        assertEquals("MODEL", model.type)
        assertEquals(false, model.isWidget)

        val text = assertNotNull(def.inputs.firstOrNull { it.name == "text" })
        assertEquals("提示词", text.tooltip)

        val denoise = assertNotNull(def.inputs.firstOrNull { it.name == "denoise" })
        assertEquals(false, denoise.required)
    }

    @Test
    fun `new combo style with explicit options is supported`() {
        val def = assertNotNull(
            WorkflowCodec.parseNodeDefinition(
                """
                {"X": {"input": {"required": {
                  "scheduler": {"type": "COMBO", "options": ["normal", "karras"]}
                }}}}
                """.trimIndent(),
                "X",
            ),
        )
        val scheduler = assertNotNull(def.inputs.firstOrNull())
        assertEquals("COMBO", scheduler.type)
        assertEquals(listOf("normal", "karras"), scheduler.options)
        assertTrue(scheduler.isWidget)
    }

    @Test
    fun `unknown class response marks nothing as a definition`() {
        assertNull(WorkflowCodec.parseNodeDefinition("{}", "NopeNode"))
        assertNull(WorkflowCodec.parseNodeDefinition("not json", "NopeNode"))
    }
}
