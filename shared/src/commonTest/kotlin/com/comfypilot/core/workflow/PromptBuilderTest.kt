package com.comfypilot.core.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 转换层的测试重点：一份被转错的图会在用户的机器上真的执行，
 * 所以这里既验证"能转对"，也验证"说不准时必须拒绝并指出节点"。
 */
class PromptBuilderTest {

    // ------------------------------------------------------------ 能转对

    @Test
    fun `demo ui workflow becomes a submit-ready api prompt`() {
        val ready = ready(PromptBuilder.build(document(DEMO_UI), DEFINITIONS))

        assertEquals(setOf("3", "4", "5", "6", "7", "8", "9"), ready.prompt.keys)

        // 控件值按顺序读，control_after_generate 这一格必须跳过。
        assertEquals(42, ready.int("3", "seed"))
        assertEquals(8, ready.int("3", "steps"))
        assertEquals(7, ready.int("3", "cfg"))
        assertEquals("euler", ready.text("3", "sampler_name"))
        assertEquals("normal", ready.text("3", "scheduler"))
        assertEquals(1, ready.int("3", "denoise"))
        assertTrue(!ready.prompt.toString().contains("randomize"))

        // 连线按 [originNode, originSlot] 落到 API 图；ComfyUI 要求源节点 ID 是字符串。
        val modelLink = ready.inputs("3")["model"]!!.jsonArray
        assertTrue(modelLink[0].jsonPrimitive.isString, "连线源节点 ID 必须是字符串")
        assertEquals(4 to 0, ready.link("3", "model"))
        assertEquals(6 to 0, ready.link("3", "positive"))
        assertEquals(7 to 0, ready.link("3", "negative"))
        assertEquals(5 to 0, ready.link("3", "latent_image"))

        assertEquals("sd_xl_base_1.0.safetensors", ready.text("4", "ckpt_name"))
        assertEquals("a red fox in snow", ready.text("6", "text"))
        assertEquals(4 to 1, ready.link("6", "clip"))
        assertEquals(1024, ready.int("5", "width"))
        assertEquals(3 to 0, ready.link("8", "samples"))
        assertEquals(4 to 2, ready.link("8", "vae"))
        assertEquals(8 to 0, ready.link("9", "images"))
        assertEquals("ComfyPilot", ready.text("9", "filename_prefix"))
    }

    @Test
    fun `run token makes saved filename unique`() {
        val ready = ready(
            PromptBuilder.build(
                document(DEMO_UI),
                DEFINITIONS,
                runToken = "abc123",
            ),
        )

        assertEquals("ComfyPilot_abc123", ready.text("9", "filename_prefix"))
    }

    @Test
    fun `overrides replace widget values without touching links`() {
        val overrides = mapOf(
            3 to mapOf(
                "seed" to JsonPrimitive(99),
                "steps" to JsonPrimitive(30),
            ),
        )
        val ready = ready(PromptBuilder.build(document(DEMO_UI), DEFINITIONS, overrides))

        assertEquals(99, ready.int("3", "seed"))
        assertEquals(30, ready.int("3", "steps"))
        assertEquals(4 to 0, ready.link("3", "model"))
    }

    @Test
    fun `overriding a linked input is rejected`() {
        val blocked = blocked(
            PromptBuilder.build(
                document(DEMO_UI),
                DEFINITIONS,
                mapOf(3 to mapOf("model" to JsonPrimitive(9))),
            ),
        )
        assertTrue(
            blocked.problems.any { it.nodeId == 3 && it.message.contains("不能覆盖") },
            "覆盖连线必须被拒绝：${blocked.problems}",
        )
    }

    @Test
    fun `overriding an unknown field is rejected`() {
        val blocked = blocked(
            PromptBuilder.build(
                document(DEMO_UI),
                DEFINITIONS,
                mapOf(3 to mapOf("not_a_field" to JsonPrimitive(1))),
            ),
        )
        assertTrue(
            blocked.problems.any { it.message.contains("not_a_field") },
            "未知字段必须被拒绝：${blocked.problems}",
        )
    }

    @Test
    fun `bypassed node is pierced to the upstream output`() {
        val ready = ready(PromptBuilder.build(document(modelPatchGraph(mode = 4)), DEFINITIONS))

        // #5 的 model 来自被旁路的 #2，最终应指向 #1 的 MODEL 输出。
        assertEquals(1 to 0, ready.link("6", "model"))
        assertTrue("2" !in ready.prompt, "旁路节点不应出现在提交的图里")
    }

    @Test
    fun `named widget values win over the positional array`() {
        val graph = graph(
            nodes = listOf(
                uiNode(
                    id = 1,
                    type = "LoadImage",
                    inputs = listOf(uiInput("image", "COMBO", widgetName = "image")),
                    outputs = listOf(uiOutput("IMAGE", "IMAGE"), uiOutput("MASK", "MASK")),
                    widgets = JsonArray(listOf(JsonPrimitive("photo.png"), JsonPrimitive("image"))),
                ),
                uiNode(
                    id = 2,
                    type = "SaveImage",
                    inputs = listOf(uiInput("images", "IMAGE", link = 1)),
                    widgets = JsonArray(listOf(JsonPrimitive("positional"))),
                    named = buildJsonObject { put("filename_prefix", "named") },
                    title = "输出",
                ),
            ),
            links = listOf(uiLink(1, 1, 0, 2, 0, "IMAGE")),
        )

        val ready = ready(PromptBuilder.build(document(graph), DEFINITIONS))
        assertEquals("named", ready.text("2", "filename_prefix"))
        assertEquals("输出", ready.node("2")["_meta"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `load image upload slot is skipped instead of becoming a widget`() {
        val graph = graph(
            nodes = listOf(
                uiNode(
                    id = 1,
                    type = "LoadImage",
                    inputs = listOf(uiInput("image", "COMBO", widgetName = "image")),
                    outputs = listOf(uiOutput("IMAGE", "IMAGE"), uiOutput("MASK", "MASK")),
                    widgets = JsonArray(listOf(JsonPrimitive("photo.png"), JsonPrimitive("image"))),
                ),
                uiNode(
                    id = 2,
                    type = "SaveImage",
                    inputs = listOf(uiInput("images", "IMAGE", link = 1)),
                    widgets = JsonArray(listOf(JsonPrimitive("ComfyPilot"))),
                ),
            ),
            links = listOf(uiLink(1, 1, 0, 2, 0, "IMAGE")),
        )

        val ready = ready(PromptBuilder.build(document(graph), DEFINITIONS))
        assertEquals(setOf("image"), ready.inputs("1").keys)
        assertEquals("photo.png", ready.text("1", "image"))
        assertTrue(ready.warnings.isEmpty(), "上传槽位被正确跳过后不该有告警：${ready.warnings}")
    }

    @Test
    fun `autogrow inputs keep their flat name dot sub keys`() {
        val graph = graph(
            nodes = listOf(
                uiNode(
                    id = 1,
                    type = "Number",
                    outputs = listOf(uiOutput("INT", "INT")),
                    widgets = JsonArray(listOf(JsonPrimitive(1))),
                ),
                uiNode(
                    id = 2,
                    type = "Number",
                    outputs = listOf(uiOutput("INT", "INT")),
                    widgets = JsonArray(listOf(JsonPrimitive(2))),
                ),
                uiNode(
                    id = 3,
                    type = "MathExpr",
                    inputs = listOf(
                        uiInput("values.a", "INT", link = 1),
                        uiInput("values.b", "INT", link = 2),
                    ),
                    outputs = listOf(uiOutput("FLOAT", "FLOAT")),
                ),
                uiNode(
                    id = 4,
                    type = "ShowValue",
                    inputs = listOf(uiInput("value", "FLOAT", link = 3)),
                ),
            ),
            links = listOf(
                uiLink(1, 1, 0, 3, 0, "INT"),
                uiLink(2, 2, 0, 3, 1, "INT"),
                uiLink(3, 3, 0, 4, 0, "FLOAT"),
            ),
        )

        val ready = ready(PromptBuilder.build(document(graph), DEFINITIONS))
        assertEquals(setOf("values.a", "values.b"), ready.inputs("3").keys)
        assertEquals(1 to 0, ready.link("3", "values.a"))
        assertEquals(2 to 0, ready.link("3", "values.b"))
    }

    @Test
    fun `dynamic combo expands option inputs and only sends the selected ones`() {
        val saveVideo = assertNotNull(
            WorkflowCodec.parseNodeDefinition(SAVE_VIDEO_INFO, "SaveVideo"),
        )
        assertEquals(listOf("mp4", "webm"), saveVideo.inputs.first { it.name == "format" }.options)
        val definitions = DEFINITIONS + ("SaveVideo" to saveVideo)

        val withQuality = ready(
            PromptBuilder.build(
                document(dynamicVideoGraph(buildJsonObject { put("format", "mp4"); put("quality", 23) })),
                definitions,
            ),
        )
        assertEquals("mp4", withQuality.text("2", "format"))
        assertEquals(23, withQuality.int("2", "quality"))
        assertTrue("crf" !in withQuality.inputs("2"), "没选的动态分支不能补默认值")

        val withoutQuality = ready(
            PromptBuilder.build(
                document(dynamicVideoGraph(buildJsonObject { put("format", "mp4") })),
                definitions,
            ),
        )
        assertEquals("mp4", withoutQuality.text("2", "format"))
        assertTrue("quality" !in withoutQuality.inputs("2"), "动态附加输入没有值时应省略")
    }

    // ------------------------------------------------------------ 转不准就拒绝

    @Test
    fun `muted upstream is reported instead of silently dropping the link`() {
        val blocked = blocked(PromptBuilder.build(document(modelPatchGraph(mode = 2)), DEFINITIONS))
        assertTrue(
            blocked.problems.any { it.nodeId == 6 && it.message.contains("静音或旁路") },
            "问题应指到 KSampler 的 model 输入：${blocked.problems}",
        )
    }

    @Test
    fun `unknown upstream class is named in the problem`() {
        val blocked = blocked(
            PromptBuilder.build(
                document(modelPatchGraph(mode = 0, patchType = "Mystery")),
                DEFINITIONS,
            ),
        )
        assertTrue(
            blocked.problems.any { it.message.contains("Mystery") },
            "问题应指出缺少定义的节点类型：${blocked.problems}",
        )
    }

    @Test
    fun `unknown node feeding the output is blocked`() {
        val graph = graph(
            nodes = listOf(
                uiNode(id = 1, type = "Mystery", outputs = listOf(uiOutput("IMAGE", "IMAGE"))),
                uiNode(
                    id = 2,
                    type = "SaveImage",
                    inputs = listOf(uiInput("images", "IMAGE", link = 1)),
                    widgets = JsonArray(listOf(JsonPrimitive("ComfyPilot"))),
                ),
            ),
            links = listOf(uiLink(1, 1, 0, 2, 0, "IMAGE")),
        )

        val blocked = blocked(PromptBuilder.build(document(graph), DEFINITIONS))
        assertTrue(blocked.problems.any { it.nodeId == 1 && it.message.contains("没有该节点的定义") })
    }

    @Test
    fun `required linked input without a connection is blocked`() {
        val graph = graph(
            nodes = listOf(
                uiNode(
                    id = 2,
                    type = "SaveImage",
                    inputs = listOf(uiInput("images", "IMAGE")),
                    widgets = JsonArray(listOf(JsonPrimitive("ComfyPilot"))),
                ),
            ),
        )

        val blocked = blocked(PromptBuilder.build(document(graph), DEFINITIONS))
        assertTrue(blocked.problems.any { it.message.contains("必填输入 images 没有连接") })
    }

    @Test
    fun `workflow without an output node is rejected`() {
        val graph = graph(
            nodes = listOf(
                uiNode(
                    id = 1,
                    type = "CLIPTextEncode",
                    inputs = listOf(uiInput("clip", "CLIP")),
                    widgets = JsonArray(listOf(JsonPrimitive("hello"))),
                ),
            ),
        )

        val blocked = blocked(PromptBuilder.build(document(graph), DEFINITIONS))
        assertTrue(blocked.problems.any { it.message.contains("没有输出节点") })
    }

    @Test
    fun `subgraph workflow is rejected instead of guessed`() {
        val raw = buildJsonObject {
            put("nodes", JsonArray(listOf(uiNode(id = 2, type = "SaveImage"))))
            put("links", JsonArray(emptyList()))
            putJsonObject("definitions") {
                put("subgraphs", JsonArray(listOf(buildJsonObject { put("id", "s1") })))
            }
        }.toString()

        val blocked = blocked(PromptBuilder.build(document(raw), DEFINITIONS))
        assertTrue(blocked.problems.any { it.message.contains("子图") })
    }

    // ------------------------------------------------------------ 边界行为

    @Test
    fun `nodes unreachable from the outputs are dropped with a warning`() {
        val orphan = uiNode(
            id = 8,
            type = "CLIPTextEncode",
            inputs = listOf(uiInput("clip", "CLIP")),
            widgets = JsonArray(listOf(JsonPrimitive("orphan"))),
        )
        val ready = ready(PromptBuilder.build(document(standardGraph(extraNodes = listOf(orphan))), DEFINITIONS))

        assertTrue("8" !in ready.prompt)
        assertTrue(
            ready.warnings.any { it.contains("已忽略 1 个未连接到输出节点的节点") },
            "应提示被忽略的孤立节点：${ready.warnings}",
        )
    }

    @Test
    fun `trailing frontend-only widget values are warned about`() {
        val widgets = JsonArray(
            listOf(
                JsonPrimitive(1),
                JsonPrimitive("fixed"),
                JsonPrimitive(20),
                JsonPrimitive(7),
                JsonPrimitive("euler"),
                JsonPrimitive("normal"),
                JsonPrimitive(1),
                JsonPrimitive("extra-button"),
            ),
        )
        val ready = ready(PromptBuilder.build(document(standardGraph(ksamplerWidgets = widgets)), DEFINITIONS))

        assertEquals(20, ready.int("5", "steps"))
        assertTrue(
            ready.warnings.any { it.contains("忽略了 1 个前端专用控件值") },
            "应提示多余的前端控件值：${ready.warnings}",
        )
    }

    @Test
    fun `api format passes through untouched`() {
        val raw = """{"3":{"class_type":"KSampler","inputs":{"seed":1}}}"""
        val ready = ready(PromptBuilder.build(document(raw), emptyMap()))

        assertEquals(1, ready.prompt.size)
        assertEquals("KSampler", ready.node("3")["class_type"]!!.jsonPrimitive.content)
        assertTrue(ready.warnings.isEmpty())
    }
}

// ---------------------------------------------------------------- 断言辅助

private fun ready(build: PromptBuild): PromptBuild.Ready =
    build as? PromptBuild.Ready ?: fail("应当可以提交，实际被拒绝：$build")

private fun blocked(build: PromptBuild): PromptBuild.Blocked =
    build as? PromptBuild.Blocked ?: fail("应当被拒绝，实际可以提交：$build")

private fun PromptBuild.Ready.inputs(id: String): JsonObject =
    node(id)["inputs"]?.jsonObject ?: fail("节点 $id 没有 inputs")

private fun PromptBuild.Ready.node(id: String): JsonObject =
    prompt[id]?.jsonObject ?: fail("提交的图里没有节点 $id")

private fun PromptBuild.Ready.text(id: String, key: String): String =
    inputs(id)[key]?.jsonPrimitive?.content ?: fail("缺少 $id.$key")

private fun PromptBuild.Ready.int(id: String, key: String): Int =
    inputs(id)[key]?.jsonPrimitive?.int ?: fail("缺少 $id.$key")

private fun PromptBuild.Ready.link(id: String, key: String): Pair<Int, Int> {
    val array = inputs(id)[key]?.jsonArray ?: fail("缺少 $id.$key")
    return array[0].jsonPrimitive.content.toInt() to array[1].jsonPrimitive.int
}

// ---------------------------------------------------------------- 夹具构造

private fun document(raw: String): WorkflowDocument {
    val parsed = WorkflowCodec.parseDocument(raw)
    val ok = parsed as? WorkflowCodec.Parsed.Ok ?: fail("夹具不是可识别的工作流：$parsed")
    return WorkflowDocument(
        path = "test.json",
        name = "test",
        raw = raw,
        format = ok.format,
        nodeClasses = ok.nodeClasses,
    )
}

private fun widget(
    name: String,
    type: String = "INT",
    default: JsonElement? = JsonPrimitive(0),
    options: List<String> = emptyList(),
    controlAfterGenerate: Boolean = false,
    imageUpload: Boolean = false,
): NodeInputDef = NodeInputDef(
    name = name,
    type = type,
    options = options,
    defaultValue = default,
    isWidget = true,
    required = true,
    controlAfterGenerate = controlAfterGenerate,
    imageUpload = imageUpload,
)

private fun link(name: String, type: String, required: Boolean = true): NodeInputDef = NodeInputDef(
    name = name,
    type = type,
    isWidget = false,
    required = required,
)

private fun outputDef(name: String, inputs: List<NodeInputDef>, outputs: List<String> = emptyList()) =
    NodeDefinition(className = name, inputs = inputs, outputs = outputs, isOutputNode = true)

private val DEFINITIONS: Map<String, NodeDefinition> = mapOf(
    "CheckpointLoaderSimple" to NodeDefinition(
        className = "CheckpointLoaderSimple",
        inputs = listOf(
            widget(
                "ckpt_name",
                "COMBO",
                JsonPrimitive("sd_xl_base_1.0.safetensors"),
                options = listOf("sd_xl_base_1.0.safetensors"),
            ),
        ),
        outputs = listOf("MODEL", "CLIP", "VAE"),
    ),
    "CLIPTextEncode" to NodeDefinition(
        className = "CLIPTextEncode",
        inputs = listOf(widget("text", "STRING", JsonPrimitive("")), link("clip", "CLIP")),
        outputs = listOf("CONDITIONING"),
    ),
    "EmptyLatentImage" to NodeDefinition(
        className = "EmptyLatentImage",
        inputs = listOf(widget("width"), widget("height"), widget("batch_size", default = JsonPrimitive(1))),
        outputs = listOf("LATENT"),
    ),
    "KSampler" to NodeDefinition(
        className = "KSampler",
        inputs = listOf(
            link("model", "MODEL"),
            widget("seed", controlAfterGenerate = true),
            widget("steps", default = JsonPrimitive(20)),
            widget("cfg", "FLOAT", JsonPrimitive(8.0)),
            widget(
                "sampler_name",
                "COMBO",
                JsonPrimitive("euler"),
                options = listOf("euler", "dpmpp_2m", "dpmpp_2m_sde"),
            ),
            widget("scheduler", "COMBO", JsonPrimitive("normal"), options = listOf("normal", "karras")),
            link("positive", "CONDITIONING"),
            link("negative", "CONDITIONING"),
            link("latent_image", "LATENT"),
            widget("denoise", "FLOAT", JsonPrimitive(1.0)),
        ),
        outputs = listOf("LATENT"),
    ),
    "VAEDecode" to NodeDefinition(
        className = "VAEDecode",
        inputs = listOf(link("samples", "LATENT"), link("vae", "VAE")),
        outputs = listOf("IMAGE"),
    ),
    "SaveImage" to outputDef(
        "SaveImage",
        inputs = listOf(link("images", "IMAGE"), widget("filename_prefix", "STRING", JsonPrimitive("ComfyUI"))),
    ),
    "LoadImage" to NodeDefinition(
        className = "LoadImage",
        inputs = listOf(
            widget("image", "COMBO", JsonPrimitive("example.png"), options = listOf("example.png"), imageUpload = true),
        ),
        outputs = listOf("IMAGE", "MASK"),
    ),
    "Number" to NodeDefinition(
        className = "Number",
        inputs = listOf(widget("value")),
        outputs = listOf("INT"),
    ),
    "MathExpr" to NodeDefinition(
        className = "MathExpr",
        inputs = listOf(
            NodeInputDef(name = "values", type = "COMFY_AUTOGROW_V3", isWidget = false, autogrow = true),
        ),
        outputs = listOf("FLOAT"),
    ),
    "ShowValue" to outputDef("ShowValue", inputs = listOf(link("value", "FLOAT"))),
    "ModelPatch" to NodeDefinition(
        className = "ModelPatch",
        inputs = listOf(link("model", "MODEL"), widget("strength", "FLOAT", JsonPrimitive(0.5))),
        outputs = listOf("MODEL"),
    ),
)

private val SAVE_VIDEO_INFO = """
    {
      "SaveVideo": {
        "input": {
          "required": {
            "images": ["IMAGE", {"forceInput": true}],
            "format": {
              "type": "COMFY_DYNAMICCOMBO_V3",
              "options": [
                {"key": "mp4", "inputs": {"required": {"quality": ["INT", {"default": 8}]}}},
                {"key": "webm", "inputs": {"required": {"crf": ["INT", {"default": 30}]}}}
              ]
            }
          }
        },
        "output": [],
        "output_node": true
      }
    }
""".trimIndent()

private fun dynamicVideoGraph(named: JsonObject): String = graph(
    nodes = listOf(
        uiNode(
            id = 1,
            type = "LoadImage",
            inputs = listOf(uiInput("image", "COMBO", widgetName = "image")),
            outputs = listOf(uiOutput("IMAGE", "IMAGE"), uiOutput("MASK", "MASK")),
            widgets = JsonArray(listOf(JsonPrimitive("photo.png"), JsonPrimitive("image"))),
        ),
        uiNode(
            id = 2,
            type = "SaveVideo",
            inputs = listOf(uiInput("images", "IMAGE", link = 1)),
            named = named,
        ),
    ),
    links = listOf(uiLink(1, 1, 0, 2, 0, "IMAGE")),
)

private fun modelPatchGraph(mode: Int, patchType: String = "ModelPatch"): String = graph(
    nodes = listOf(
        uiNode(
            id = 1,
            type = "CheckpointLoaderSimple",
            outputs = listOf(uiOutput("MODEL", "MODEL"), uiOutput("CLIP", "CLIP"), uiOutput("VAE", "VAE")),
            widgets = JsonArray(listOf(JsonPrimitive("sd.safetensors"))),
        ),
        uiNode(
            id = 2,
            type = patchType,
            mode = mode,
            inputs = listOf(uiInput("model", "MODEL", link = 1)),
            outputs = listOf(uiOutput("MODEL", "MODEL")),
            widgets = JsonArray(listOf(JsonPrimitive(0.5))),
        ),
        uiNode(
            id = 3,
            type = "CLIPTextEncode",
            inputs = listOf(uiInput("clip", "CLIP", link = 2)),
            outputs = listOf(uiOutput("CONDITIONING", "CONDITIONING")),
            widgets = JsonArray(listOf(JsonPrimitive("positive"))),
        ),
        uiNode(
            id = 4,
            type = "CLIPTextEncode",
            inputs = listOf(uiInput("clip", "CLIP", link = 3)),
            outputs = listOf(uiOutput("CONDITIONING", "CONDITIONING")),
            widgets = JsonArray(listOf(JsonPrimitive("negative"))),
        ),
        uiNode(
            id = 5,
            type = "EmptyLatentImage",
            outputs = listOf(uiOutput("LATENT", "LATENT")),
            widgets = JsonArray(listOf(JsonPrimitive(512), JsonPrimitive(512), JsonPrimitive(1))),
        ),
        uiNode(
            id = 6,
            type = "KSampler",
            inputs = listOf(
                uiInput("model", "MODEL", link = 4),
                uiInput("positive", "CONDITIONING", link = 5),
                uiInput("negative", "CONDITIONING", link = 6),
                uiInput("latent_image", "LATENT", link = 7),
            ),
            outputs = listOf(uiOutput("LATENT", "LATENT")),
            widgets = defaultKsWidgets(),
        ),
        uiNode(
            id = 7,
            type = "VAEDecode",
            inputs = listOf(
                uiInput("samples", "LATENT", link = 8),
                uiInput("vae", "VAE", link = 9),
            ),
            outputs = listOf(uiOutput("IMAGE", "IMAGE")),
        ),
        uiNode(
            id = 8,
            type = "SaveImage",
            inputs = listOf(uiInput("images", "IMAGE", link = 10)),
            widgets = JsonArray(listOf(JsonPrimitive("ComfyPilot"))),
        ),
    ),
    links = listOf(
        uiLink(1, 1, 0, 2, 0, "MODEL"),
        uiLink(2, 1, 1, 3, 0, "CLIP"),
        uiLink(3, 1, 1, 4, 0, "CLIP"),
        uiLink(4, 2, 0, 6, 0, "MODEL"),
        uiLink(5, 3, 0, 6, 1, "CONDITIONING"),
        uiLink(6, 4, 0, 6, 2, "CONDITIONING"),
        uiLink(7, 5, 0, 6, 3, "LATENT"),
        uiLink(8, 6, 0, 7, 0, "LATENT"),
        uiLink(9, 1, 2, 7, 1, "VAE"),
        uiLink(10, 7, 0, 8, 0, "IMAGE"),
    ),
)

private fun standardGraph(
    ksamplerWidgets: JsonArray = defaultKsWidgets(),
    extraNodes: List<JsonObject> = emptyList(),
): String = graph(
    nodes = listOf(
        uiNode(
            id = 1,
            type = "CheckpointLoaderSimple",
            outputs = listOf(uiOutput("MODEL", "MODEL"), uiOutput("CLIP", "CLIP"), uiOutput("VAE", "VAE")),
            widgets = JsonArray(listOf(JsonPrimitive("sd.safetensors"))),
        ),
        uiNode(
            id = 2,
            type = "CLIPTextEncode",
            inputs = listOf(uiInput("clip", "CLIP", link = 1)),
            outputs = listOf(uiOutput("CONDITIONING", "CONDITIONING")),
            widgets = JsonArray(listOf(JsonPrimitive("positive"))),
        ),
        uiNode(
            id = 3,
            type = "CLIPTextEncode",
            inputs = listOf(uiInput("clip", "CLIP", link = 2)),
            outputs = listOf(uiOutput("CONDITIONING", "CONDITIONING")),
            widgets = JsonArray(listOf(JsonPrimitive("negative"))),
        ),
        uiNode(
            id = 4,
            type = "EmptyLatentImage",
            outputs = listOf(uiOutput("LATENT", "LATENT")),
            widgets = JsonArray(listOf(JsonPrimitive(512), JsonPrimitive(512), JsonPrimitive(1))),
        ),
        uiNode(
            id = 5,
            type = "KSampler",
            inputs = listOf(
                uiInput("model", "MODEL", link = 3),
                uiInput("positive", "CONDITIONING", link = 4),
                uiInput("negative", "CONDITIONING", link = 5),
                uiInput("latent_image", "LATENT", link = 6),
            ),
            outputs = listOf(uiOutput("LATENT", "LATENT")),
            widgets = ksamplerWidgets,
        ),
        uiNode(
            id = 6,
            type = "VAEDecode",
            inputs = listOf(
                uiInput("samples", "LATENT", link = 7),
                uiInput("vae", "VAE", link = 8),
            ),
            outputs = listOf(uiOutput("IMAGE", "IMAGE")),
        ),
        uiNode(
            id = 7,
            type = "SaveImage",
            inputs = listOf(uiInput("images", "IMAGE", link = 9)),
            widgets = JsonArray(listOf(JsonPrimitive("ComfyPilot"))),
        ),
    ) + extraNodes,
    links = listOf(
        uiLink(1, 1, 1, 2, 0, "CLIP"),
        uiLink(2, 1, 1, 3, 0, "CLIP"),
        uiLink(3, 1, 0, 5, 0, "MODEL"),
        uiLink(4, 2, 0, 5, 1, "CONDITIONING"),
        uiLink(5, 3, 0, 5, 2, "CONDITIONING"),
        uiLink(6, 4, 0, 5, 3, "LATENT"),
        uiLink(7, 5, 0, 6, 0, "LATENT"),
        uiLink(8, 1, 2, 6, 1, "VAE"),
        uiLink(9, 6, 0, 7, 0, "IMAGE"),
    ),
)

private fun defaultKsWidgets(): JsonArray = JsonArray(
    listOf(
        JsonPrimitive(1),
        JsonPrimitive("fixed"),
        JsonPrimitive(20),
        JsonPrimitive(7),
        JsonPrimitive("euler"),
        JsonPrimitive("normal"),
        JsonPrimitive(1),
    ),
)

private fun uiNode(
    id: Int,
    type: String,
    mode: Int = 0,
    order: Int = id,
    inputs: List<JsonObject> = emptyList(),
    outputs: List<JsonObject> = emptyList(),
    widgets: JsonArray? = null,
    named: JsonObject? = null,
    title: String? = null,
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", type)
    put("mode", mode)
    put("order", order)
    put("inputs", JsonArray(inputs))
    put("outputs", JsonArray(outputs))
    if (widgets != null) put("widgets_values", widgets)
    if (named != null) put("widgets_values_named", named)
    if (title != null) put("title", title)
}

private fun uiInput(
    name: String,
    type: String = "*",
    link: Int? = null,
    widgetName: String? = null,
): JsonObject = buildJsonObject {
    put("name", name)
    put("type", type)
    if (link != null) put("link", link)
    if (widgetName != null) {
        putJsonObject("widget") { put("name", widgetName) }
    }
}

private fun uiOutput(name: String, type: String): JsonObject = buildJsonObject {
    put("name", name)
    put("type", type)
}

private fun uiLink(
    id: Int,
    origin: Int,
    originSlot: Int,
    target: Int,
    targetSlot: Int,
    type: String,
): JsonArray = JsonArray(
    listOf(
        JsonPrimitive(id),
        JsonPrimitive(origin),
        JsonPrimitive(originSlot),
        JsonPrimitive(target),
        JsonPrimitive(targetSlot),
        JsonPrimitive(type),
    ),
)

private fun graph(nodes: List<JsonObject>, links: List<JsonArray> = emptyList()): String =
    buildJsonObject {
        put("nodes", JsonArray(nodes))
        put("links", JsonArray(links))
    }.toString()

// 与 tools/mock-comfyui/workflows/demo_ui.json 同构的夹具，保持解析路径真实。
private val DEMO_UI = """
{
  "last_node_id": 9,
  "last_link_id": 9,
  "nodes": [
    {
      "id": 4,
      "type": "CheckpointLoaderSimple",
      "pos": [40, 120],
      "size": [315, 98],
      "flags": {},
      "order": 0,
      "mode": 0,
      "inputs": [],
      "outputs": [
        { "name": "MODEL", "type": "MODEL", "links": [1] },
        { "name": "CLIP", "type": "CLIP", "links": [2, 3] },
        { "name": "VAE", "type": "VAE", "links": [4] }
      ],
      "properties": { "Node name for S&R": "CheckpointLoaderSimple" },
      "widgets_values": ["sd_xl_base_1.0.safetensors"]
    },
    {
      "id": 6,
      "type": "CLIPTextEncode",
      "pos": [400, 60],
      "size": [420, 160],
      "flags": {},
      "order": 1,
      "mode": 0,
      "inputs": [{ "name": "clip", "type": "CLIP", "link": 2 }],
      "outputs": [{ "name": "CONDITIONING", "type": "CONDITIONING", "links": [5] }],
      "properties": { "Node name for S&R": "CLIPTextEncode" },
      "widgets_values": ["a red fox in snow"]
    },
    {
      "id": 7,
      "type": "CLIPTextEncode",
      "pos": [400, 260],
      "size": [420, 160],
      "flags": {},
      "order": 2,
      "mode": 0,
      "inputs": [{ "name": "clip", "type": "CLIP", "link": 3 }],
      "outputs": [{ "name": "CONDITIONING", "type": "CONDITIONING", "links": [6] }],
      "properties": { "Node name for S&R": "CLIPTextEncode" },
      "widgets_values": ["blurry, low quality"]
    },
    {
      "id": 5,
      "type": "EmptyLatentImage",
      "pos": [40, 280],
      "size": [315, 106],
      "flags": {},
      "order": 3,
      "mode": 0,
      "inputs": [],
      "outputs": [{ "name": "LATENT", "type": "LATENT", "links": [7] }],
      "properties": { "Node name for S&R": "EmptyLatentImage" },
      "widgets_values": [1024, 1024, 1]
    },
    {
      "id": 3,
      "type": "KSampler",
      "pos": [880, 120],
      "size": [315, 262],
      "flags": {},
      "order": 4,
      "mode": 0,
      "inputs": [
        { "name": "model", "type": "MODEL", "link": 1 },
        { "name": "positive", "type": "CONDITIONING", "link": 5 },
        { "name": "negative", "type": "CONDITIONING", "link": 6 },
        { "name": "latent_image", "type": "LATENT", "link": 7 }
      ],
      "outputs": [{ "name": "LATENT", "type": "LATENT", "links": [8] }],
      "properties": { "Node name for S&R": "KSampler" },
      "widgets_values": [42, "randomize", 8, 7, "euler", "normal", 1]
    },
    {
      "id": 8,
      "type": "VAEDecode",
      "pos": [1240, 120],
      "size": [210, 46],
      "flags": {},
      "order": 5,
      "mode": 0,
      "inputs": [
        { "name": "samples", "type": "LATENT", "link": 8 },
        { "name": "vae", "type": "VAE", "link": 4 }
      ],
      "outputs": [{ "name": "IMAGE", "type": "IMAGE", "links": [9] }],
      "properties": { "Node name for S&R": "VAEDecode" },
      "widgets_values": []
    },
    {
      "id": 9,
      "type": "SaveImage",
      "pos": [1500, 120],
      "size": [315, 270],
      "flags": {},
      "order": 6,
      "mode": 0,
      "inputs": [{ "name": "images", "type": "IMAGE", "link": 9 }],
      "outputs": [],
      "properties": { "Node name for S&R": "SaveImage" },
      "widgets_values": ["ComfyPilot"]
    }
  ],
  "links": [
    [1, 4, 0, 3, 0, "MODEL"],
    [2, 4, 1, 6, 0, "CLIP"],
    [3, 4, 1, 7, 0, "CLIP"],
    [4, 4, 2, 8, 1, "VAE"],
    [5, 6, 0, 3, 1, "CONDITIONING"],
    [6, 7, 0, 3, 2, "CONDITIONING"],
    [7, 5, 0, 3, 3, "LATENT"],
    [8, 3, 0, 8, 0, "LATENT"],
    [9, 8, 0, 9, 0, "IMAGE"]
  ],
  "groups": [],
  "config": {},
  "extra": { "ds": { "scale": 0.8, "offset": [120, 80] } },
  "version": 0.4
}
""".trimIndent()
