package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Power Lora Loader 只接了 MODEL、没接 CLIP 的情况。
 *
 * 用户反馈「LoRA 加载器不生效」的真实工作流就是这样：clip 输入为空。前端展开时
 * 用的是 LoraLoaderModelOnly，我们之前一律生成 LoraLoader，导致 clip 必填校验失败。
 */
class LoraModelOnlyTest {

    @Test
    fun `power lora without clip expands to LoraLoaderModelOnly`() {
        val build = PromptBuilder.build(document(FIXTURE), DEFINITIONS)
        val ready = build as? PromptBuild.Ready ?: error("应当可以提交：$build")

        val loraEntry = ready.prompt.entries.firstOrNull {
            it.value.jsonObject["class_type"]?.jsonPrimitive?.content == "LoraLoaderModelOnly"
        } ?: error("没有生成 LoraLoaderModelOnly：${ready.prompt}")

        val inputs = loraEntry.value.jsonObject["inputs"]!!.jsonObject
        assertEquals("lora-a.safetensors", inputs["lora_name"]!!.jsonPrimitive.content)
        assertEquals(0.85, inputs["strength_model"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals("[\"1\",0]", inputs["model"].toString())
        // 不能出现 clip 输入：模型只走 MODEL。
        assertNull(inputs["clip"])

        // 采样器的 model 必须来自 LoRA 链尾。
        val sampler = ready.prompt.entries.first {
            it.value.jsonObject["class_type"]?.jsonPrimitive?.content == "KSampler"
        }
        val samplerModel = sampler.value.jsonObject["inputs"]!!.jsonObject["model"].toString()
        assertEquals("[\"${loraEntry.key}\",0]", samplerModel)
    }

    @Test
    fun `power lora entries can be edited through overrides`() {
        val overrides = mapOf(
            2 to mapOf(
                "lora_1.strength" to JsonPrimitive(0.42),
                "lora_2.on" to JsonPrimitive(true),
            ),
        )
        val build = PromptBuilder.build(document(FIXTURE), DEFINITIONS, overrides = overrides)
        val ready = build as? PromptBuild.Ready ?: error("应当可以提交：$build")

        val loras = ready.prompt.entries.filter {
            (it.value.jsonObject["class_type"]?.jsonPrimitive?.content ?: "").startsWith("LoraLoader")
        }
        assertEquals(2, loras.size, "启用第二个 LoRA 后应当有两条链：${ready.prompt}")
        val first = loras.first().value.jsonObject["inputs"]!!.jsonObject
        assertEquals(0.42, first["strength_model"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        val second = loras.last().value.jsonObject["inputs"]!!.jsonObject
        assertEquals("lora-b.safetensors", second["lora_name"]!!.jsonPrimitive.content)
        assertEquals(0.5, second["strength_model"]!!.jsonPrimitive.content.toDouble(), 0.0001)
    }

    @Test
    fun `power lora exposes editable entries in the field view`() {
        val view = WorkflowFields.build(document(FIXTURE), DEFINITIONS)
        val node = view.nodes.first { it.type == RGTHREE_POWER_LORA_TYPE }
        val names = node.fields.map { it.inputName }
        assertTrue("lora_1.on" in names, names.toString())
        assertTrue("lora_1.lora" in names, names.toString())
        assertTrue("lora_1.strength" in names, names.toString())
        assertTrue("lora_2.strength" in names, names.toString())
        assertTrue("lora_2.on" in names, names.toString())
        // CLIP 没接线、strengthTwo 为空：不该出现第二个权重。
        assertTrue("lora_1.strengthTwo" !in names, names.toString())
    }

    private fun document(raw: String): WorkflowDocument {
        return WorkflowDocument(
            path = "power-model-only.json",
            name = "power-model-only",
            format = WorkflowFormat.UI,
            raw = raw,
            nodeClasses = emptyList(),
        )
    }

    private fun widget(name: String, type: String, value: JsonPrimitive) =
        NodeInputDef(name = name, type = type, isWidget = true, defaultValue = value)

    private fun link(name: String, type: String) = NodeInputDef(name = name, type = type)

    private val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
        "CheckpointLoaderSimple" to NodeDefinition(
            className = "CheckpointLoaderSimple",
            inputs = listOf(widget("ckpt_name", "COMBO", JsonPrimitive("sdxl.safetensors"))),
            outputs = listOf("MODEL", "CLIP", "VAE"),
        ),
        RGTHREE_POWER_LORA_TYPE to NodeDefinition(
            className = RGTHREE_POWER_LORA_TYPE,
            inputs = listOf(link("model", "MODEL"), link("clip", "CLIP")),
            outputs = listOf("MODEL", "CLIP"),
        ),        "LoraLoaderModelOnly" to NodeDefinition(            className = "LoraLoaderModelOnly",
            inputs = listOf(
                link("model", "MODEL"),
                widget("lora_name", "COMBO", JsonPrimitive("")),
                widget("strength_model", "FLOAT", JsonPrimitive(1.0)),
            ),
            outputs = listOf("MODEL"),
        ),
        "CLIPTextEncode" to NodeDefinition(
            className = "CLIPTextEncode",
            inputs = listOf(widget("text", "STRING", JsonPrimitive("")), link("clip", "CLIP")),
            outputs = listOf("CONDITIONING"),
        ),
        "EmptyLatentImage" to NodeDefinition(
            className = "EmptyLatentImage",
            inputs = listOf(
                widget("width", "INT", JsonPrimitive(1024)),
                widget("height", "INT", JsonPrimitive(1024)),
                widget("batch_size", "INT", JsonPrimitive(1)),
            ),
            outputs = listOf("LATENT"),
        ),
        "KSampler" to NodeDefinition(
            className = "KSampler",
            inputs = listOf(
                widget("seed", "INT", JsonPrimitive(1)),
                widget("steps", "INT", JsonPrimitive(8)),
                widget("cfg", "FLOAT", JsonPrimitive(7.0)),
                widget("sampler_name", "COMBO", JsonPrimitive("euler")),
                widget("scheduler", "COMBO", JsonPrimitive("normal")),
                widget("denoise", "FLOAT", JsonPrimitive(1.0)),
                link("model", "MODEL"),
                link("positive", "CONDITIONING"),
                link("negative", "CONDITIONING"),
                link("latent_image", "LATENT"),
            ),
            outputs = listOf("LATENT"),
        ),
        "SaveImage" to NodeDefinition(
            className = "SaveImage",
            inputs = listOf(widget("filename_prefix", "STRING", JsonPrimitive("ComfyPilot")), link("images", "IMAGE")),
            isOutputNode = true,
        ),
    )

    private companion object {
        val FIXTURE = """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "pos": [0, 0], "widgets_values": ["sdxl.safetensors"],
                 "inputs": [{"name": "ckpt_name", "type": "COMBO", "widget": {"name": "ckpt_name"}, "link": null}],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [1]},
                             {"name": "CLIP", "type": "CLIP", "links": [3]},
                             {"name": "VAE", "type": "VAE", "links": null}]},
                {"id": 2, "type": "Power Lora Loader (rgthree)", "pos": [320, 0],
                 "widgets_values": [
                   {},
                   {"type": "PowerLoraLoaderHeaderWidget"},
                   {"on": true, "lora": "lora-a.safetensors", "strength": 0.85, "strengthTwo": null},
                   {"on": false, "lora": "lora-b.safetensors", "strength": 0.5, "strengthTwo": null}
                 ],
                 "inputs": [
                   {"name": "model", "type": "MODEL", "link": 1},
                   {"name": "clip", "type": "CLIP", "link": null}
                 ],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [5]},
                             {"name": "CLIP", "type": "CLIP", "links": null}]},
                {"id": 4, "type": "CLIPTextEncode", "pos": [700, 0], "widgets_values": ["a cat"],
                 "inputs": [{"name": "text", "type": "STRING", "widget": {"name": "text"}, "link": null},
                            {"name": "clip", "type": "CLIP", "link": 3}],
                 "outputs": [{"name": "CONDITIONING", "type": "CONDITIONING", "links": [6, 7]}]},
                {"id": 5, "type": "KSampler", "pos": [1040, 0],
                 "widgets_values": [1, 8, 7.0, "euler", "normal", 1.0],
                 "inputs": [
                   {"name": "seed", "type": "INT", "widget": {"name": "seed"}, "link": null},
                   {"name": "steps", "type": "INT", "widget": {"name": "steps"}, "link": null},
                   {"name": "cfg", "type": "FLOAT", "widget": {"name": "cfg"}, "link": null},
                   {"name": "sampler_name", "type": "COMBO", "widget": {"name": "sampler_name"}, "link": null},
                   {"name": "scheduler", "type": "COMBO", "widget": {"name": "scheduler"}, "link": null},
                   {"name": "denoise", "type": "FLOAT", "widget": {"name": "denoise"}, "link": null},
                   {"name": "model", "type": "MODEL", "link": 5},
                   {"name": "positive", "type": "CONDITIONING", "link": 6},
                   {"name": "negative", "type": "CONDITIONING", "link": 7},
                   {"name": "latent_image", "type": "LATENT", "link": 9}
                 ],
                 "outputs": [{"name": "LATENT", "type": "LATENT", "links": [10]}]},
                {"id": 6, "type": "EmptyLatentImage", "pos": [700, 280], "widgets_values": [1024, 1024, 1],
                 "inputs": [
                   {"name": "width", "type": "INT", "widget": {"name": "width"}, "link": null},
                   {"name": "height", "type": "INT", "widget": {"name": "height"}, "link": null},
                   {"name": "batch_size", "type": "INT", "widget": {"name": "batch_size"}, "link": null}
                 ],
                 "outputs": [{"name": "LATENT", "type": "LATENT", "links": [9]}]},
                {"id": 8, "type": "SaveImage", "pos": [1380, 0], "widgets_values": ["ComfyPilot"],
                 "inputs": [
                   {"name": "filename_prefix", "type": "STRING", "widget": {"name": "filename_prefix"}, "link": null},
                   {"name": "images", "type": "IMAGE", "link": 10}
                 ],
                 "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [3, 1, 1, 4, 1, "CLIP"],
                [5, 2, 0, 5, 6, "MODEL"],
                [6, 4, 0, 5, 7, "CONDITIONING"],
                [7, 4, 0, 5, 8, "CONDITIONING"],
                [9, 6, 0, 5, 9, "LATENT"],
                [10, 5, 0, 8, 1, "IMAGE"]
              ]
            }
        """.trimIndent()
    }
}
