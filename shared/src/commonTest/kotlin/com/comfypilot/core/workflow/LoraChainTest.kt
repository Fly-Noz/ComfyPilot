package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 多个 LoRA 串联时的转换。
 *
 * 用户反馈「单个 LoRA 能用，串两个就不可用」，先在这里确认转换层本身没问题：
 * 链路 model/clip 都要接到上一个 LoRA，而不是绕回 Checkpoint。
 */
class LoraChainTest {

    @Test
    fun `two chained loras keep the chain`() {
        val ready = ready(PromptBuilder.build(document(CHAIN), DEFINITIONS))

        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8"), ready.prompt.keys)

        // 第一个 LoRA 接 Checkpoint 的 MODEL / CLIP
        assertEquals(1 to 0, ready.link("2", "model"))
        assertEquals(1 to 1, ready.link("2", "clip"))
        // 第二个 LoRA 必须接第一个 LoRA，而不是绕过它
        assertEquals(2 to 0, ready.link("3", "model"))
        assertEquals(2 to 1, ready.link("3", "clip"))
        // 采样器和文本编码器接最后一个 LoRA
        assertEquals(3 to 0, ready.link("7", "model"))
        assertEquals(3 to 1, ready.link("4", "clip"))
        assertEquals(3 to 1, ready.link("5", "clip"))

        // 两个 LoRA 各自的权重不能被写串
        assertEquals("lora-a.safetensors", ready.text("2", "lora_name"))
        assertEquals(0.8, ready.inputs("2")["strength_model"]!!.jsonPrimitive.content.toDouble())
        assertEquals("lora-b.safetensors", ready.text("3", "lora_name"))
        assertEquals(0.6, ready.inputs("3")["strength_model"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `bypassing the middle lora passes both model and clip through`() {
        // 第二个 LoRA 被旁路：下游必须直接拿到第一个 LoRA 的输出。
        val bypassed = CHAIN.replace("\"id\": 3,", "\"id\": 3, \"mode\": 4,")
        val ready = ready(PromptBuilder.build(document(bypassed), DEFINITIONS))

        assertEquals(2 to 0, ready.link("7", "model"))
        assertEquals(2 to 1, ready.link("4", "clip"))
        assertEquals(2 to 1, ready.link("5", "clip"))
    }

    // ------------------------------------------------------------ 夹具

    private fun ready(build: PromptBuild): PromptBuild.Ready =
        build as? PromptBuild.Ready ?: fail("应当可以提交，实际被拒绝：$build")

    private fun document(raw: String): WorkflowDocument {
        val parsed = WorkflowCodec.parseDocument(raw)
        val ok = parsed as? WorkflowCodec.Parsed.Ok ?: fail("夹具不是可识别的工作流：$parsed")
        return WorkflowDocument(
            path = "lora-chain.json",
            name = "lora-chain",
            raw = raw,
            format = ok.format,
            nodeClasses = ok.nodeClasses,
        )
    }

    private fun PromptBuild.Ready.inputs(id: String): kotlinx.serialization.json.JsonObject =
        prompt[id]?.jsonObject?.get("inputs")?.jsonObject ?: fail("节点 $id 没有 inputs")

    private fun PromptBuild.Ready.text(id: String, key: String): String =
        inputs(id)[key]?.jsonPrimitive?.content ?: fail("缺少 $id.$key")

    private fun PromptBuild.Ready.link(id: String, key: String): Pair<Int, Int> {
        val array = inputs(id)[key]?.jsonArray ?: fail("缺少连线 $id.$key")
        val origin = array[0].jsonPrimitive.content.toIntOrNull() ?: fail("连线源不是节点 id")
        val slot = array[1].jsonPrimitive.content.toIntOrNull() ?: fail("连线源不是槽位")
        return origin to slot
    }

    private fun widget(name: String, type: String, default: JsonElement? = JsonPrimitive(0)) =
        NodeInputDef(name = name, type = type, defaultValue = default, isWidget = true, required = true)

    private fun link(name: String, type: String) =
        NodeInputDef(name = name, type = type, isWidget = false, required = true)

    private val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
        "CheckpointLoaderSimple" to NodeDefinition(
            className = "CheckpointLoaderSimple",
            inputs = listOf(widget("ckpt_name", "COMBO", JsonPrimitive("sdxl.safetensors"))),
            outputs = listOf("MODEL", "CLIP", "VAE"),
        ),
        "LoraLoader" to NodeDefinition(
            className = "LoraLoader",
            inputs = listOf(
                link("model", "MODEL"),
                link("clip", "CLIP"),
                widget("lora_name", "COMBO", JsonPrimitive("lora-a.safetensors")),
                widget("strength_model", "FLOAT", JsonPrimitive(1.0)),
                widget("strength_clip", "FLOAT", JsonPrimitive(1.0)),
            ),
            outputs = listOf("MODEL", "CLIP"),
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
            inputs = listOf(
                widget("filename_prefix", "STRING", JsonPrimitive("ComfyPilot")),
                link("images", "IMAGE"),
            ),
            outputs = emptyList(),
            isOutputNode = true,
        ),
    )

    private val CHAIN = """
        {
          "nodes": [
            {"id": 1, "type": "CheckpointLoaderSimple", "pos": [0, 0], "size": [300, 98],
             "widgets_values": ["sdxl.safetensors"],
             "inputs": [{"name": "ckpt_name", "type": "COMBO", "widget": {"name": "ckpt_name"}, "link": null}],
             "outputs": [{"name": "MODEL", "type": "MODEL"}, {"name": "CLIP", "type": "CLIP"}, {"name": "VAE", "type": "VAE"}]},
            {"id": 2, "type": "LoraLoader", "pos": [320, 0], "size": [315, 126],
             "widgets_values": ["lora-a.safetensors", 0.8, 0.8],
             "inputs": [
               {"name": "model", "type": "MODEL", "link": 1},
               {"name": "clip", "type": "CLIP", "link": 2},
               {"name": "lora_name", "type": "COMBO", "widget": {"name": "lora_name"}, "link": null},
               {"name": "strength_model", "type": "FLOAT", "widget": {"name": "strength_model"}, "link": null},
               {"name": "strength_clip", "type": "FLOAT", "widget": {"name": "strength_clip"}, "link": null}
             ],
             "outputs": [{"name": "MODEL", "type": "MODEL"}, {"name": "CLIP", "type": "CLIP"}]},
            {"id": 3, "type": "LoraLoader", "pos": [660, 0], "size": [315, 126],
             "widgets_values": ["lora-b.safetensors", 0.6, 0.6],
             "inputs": [
               {"name": "model", "type": "MODEL", "link": 3},
               {"name": "clip", "type": "CLIP", "link": 4},
               {"name": "lora_name", "type": "COMBO", "widget": {"name": "lora_name"}, "link": null},
               {"name": "strength_model", "type": "FLOAT", "widget": {"name": "strength_model"}, "link": null},
               {"name": "strength_clip", "type": "FLOAT", "widget": {"name": "strength_clip"}, "link": null}
             ],
             "outputs": [{"name": "MODEL", "type": "MODEL"}, {"name": "CLIP", "type": "CLIP"}]},
            {"id": 4, "type": "CLIPTextEncode", "pos": [1000, 0], "size": [300, 100],
             "widgets_values": ["a cat"],
             "inputs": [
               {"name": "text", "type": "STRING", "widget": {"name": "text"}, "link": null},
               {"name": "clip", "type": "CLIP", "link": 5}
             ],
             "outputs": [{"name": "CONDITIONING", "type": "CONDITIONING"}]},
            {"id": 5, "type": "CLIPTextEncode", "pos": [1000, 140], "size": [300, 100],
             "widgets_values": ["blurry"],
             "inputs": [
               {"name": "text", "type": "STRING", "widget": {"name": "text"}, "link": null},
               {"name": "clip", "type": "CLIP", "link": 6}
             ],
             "outputs": [{"name": "CONDITIONING", "type": "CONDITIONING"}]},
            {"id": 6, "type": "EmptyLatentImage", "pos": [1000, 280], "size": [300, 106],
             "widgets_values": [1024, 1024, 1],
             "inputs": [
               {"name": "width", "type": "INT", "widget": {"name": "width"}, "link": null},
               {"name": "height", "type": "INT", "widget": {"name": "height"}, "link": null},
               {"name": "batch_size", "type": "INT", "widget": {"name": "batch_size"}, "link": null}
             ],
             "outputs": [{"name": "LATENT", "type": "LATENT"}]},
            {"id": 7, "type": "KSampler", "pos": [1340, 0], "size": [300, 262],
             "widgets_values": [1, 8, 7.0, "euler", "normal", 1.0],
             "inputs": [
               {"name": "seed", "type": "INT", "widget": {"name": "seed"}, "link": null},
               {"name": "steps", "type": "INT", "widget": {"name": "steps"}, "link": null},
               {"name": "cfg", "type": "FLOAT", "widget": {"name": "cfg"}, "link": null},
               {"name": "sampler_name", "type": "COMBO", "widget": {"name": "sampler_name"}, "link": null},
               {"name": "scheduler", "type": "COMBO", "widget": {"name": "scheduler"}, "link": null},
               {"name": "denoise", "type": "FLOAT", "widget": {"name": "denoise"}, "link": null},
               {"name": "model", "type": "MODEL", "link": 7},
               {"name": "positive", "type": "CONDITIONING", "link": 8},
               {"name": "negative", "type": "CONDITIONING", "link": 9},
               {"name": "latent_image", "type": "LATENT", "link": 10}
             ],
             "outputs": [{"name": "LATENT", "type": "LATENT"}]},
            {"id": 8, "type": "SaveImage", "pos": [1680, 0], "size": [300, 300],
             "widgets_values": ["ComfyPilot"],
             "inputs": [
               {"name": "filename_prefix", "type": "STRING", "widget": {"name": "filename_prefix"}, "link": null},
               {"name": "images", "type": "IMAGE", "link": 11}
             ],
             "outputs": []}
          ],
          "links": [
            [1, 1, 0, 2, 0, "MODEL"],
            [2, 1, 1, 2, 1, "CLIP"],
            [3, 2, 0, 3, 0, "MODEL"],
            [4, 2, 1, 3, 1, "CLIP"],
            [5, 3, 1, 4, 1, "CLIP"],
            [6, 3, 1, 5, 1, "CLIP"],
            [7, 3, 0, 7, 6, "MODEL"],
            [8, 4, 0, 7, 7, "CONDITIONING"],
            [9, 5, 0, 7, 8, "CONDITIONING"],
            [10, 6, 0, 7, 9, "LATENT"],
            [11, 7, 0, 8, 1, "IMAGE"]
          ],
          "groups": [],
          "version": 0.4
        }
    """.trimIndent()
}
