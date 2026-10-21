package com.comfypilot.core.workflow

import com.comfypilot.core.api.ApiCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 一条阻断提交的问题。带上节点，界面上才能定位到具体节点。 */
data class BuildProblem(
    val nodeId: Int? = null,
    val nodeType: String? = null,
    val message: String,
) {
    fun format(): String = when {
        nodeId == null -> message
        nodeType.isNullOrBlank() -> "#$nodeId：$message"
        else -> "#$nodeId $nodeType：$message"
    }
}

sealed interface PromptBuild {
    /** 可以直接 POST 给 `/prompt` 的图。 */
    data class Ready(val prompt: JsonObject, val warnings: List<String>) : PromptBuild

    /** 转换不完整：宁可拒绝，也不把一张缺东西的图交给服务器。 */
    data class Blocked(val problems: List<BuildProblem>) : PromptBuild
}

/**
 * 把网页格式工作流转换成 `/prompt` 能吃的 API 图。
 *
 * 规则全部对齐前端 1.52.x 的 `graphToPrompt` / `ExecutableNodeDTO`：
 * - `mode=2`（静音）不执行；`mode=4`（旁路）按输入/输出类型穿透到上游；
 * - 控件值优先用 `widgets_values_named`，旧文件按 `input_order` 的位置映射；
 * - `control_after_generate`、`upload` 这类前端专用槽位跳过；
 * - 只提交 `/object_info` 声明过的输入；自动增长输入保留 `name.sub` 扁平键；
 * - 上游是服务端没有的节点时直接报错，不猜它的值。
 */
object PromptBuilder {

    /**
     * 前端专用、没有服务端实现的节点。它们不参与执行，画布上只是标签/开关/备注。
     * 其余"服务端没有定义"的活动节点一律当成缺自定义节点报错。
     */
    private val UI_ONLY_NODE_TYPES = setOf(
        "Note",
        "MarkdownNote",
        "Reroute",
        "PrimitiveNode",
        "Label (rgthree)",
        "Fast Groups Bypasser (rgthree)",
    )

    fun build(document: WorkflowDocument, definitions: Map<String, NodeDefinition>): PromptBuild {
        when (document.format) {
            WorkflowFormat.API -> {
                val root = runCatching {
                    ApiCodec.json.parseToJsonElement(document.raw) as? JsonObject
                }.getOrNull()
                return if (root.isNullOrEmpty()) {
                    PromptBuild.Blocked(listOf(BuildProblem(message = "API 工作流是空的")))
                } else {
                    PromptBuild.Ready(root, emptyList())
                }
            }

            WorkflowFormat.UNKNOWN -> return PromptBuild.Blocked(
                listOf(BuildProblem(message = "无法识别工作流格式，不能提交")),
            )

            WorkflowFormat.UI -> Unit
        }

        val graph = UiWorkflowParser.parse(document.raw)
            ?: return PromptBuild.Blocked(listOf(BuildProblem(message = "工作流不是有效的网页格式 JSON")))
        if (graph.hasSubgraphs) {
            return PromptBuild.Blocked(
                listOf(BuildProblem(message = "工作流包含子图（subgraph），当前版本不支持转换")),
            )
        }

        val resolver = UiGraphResolver(graph)
        val warnings = mutableListOf<String>()

        val ordered = graph.nodes
            .filter { it.mode == UiWorkflowParser.MODE_NORMAL }
            .sortedWith(compareBy({ it.order }, { it.id }))

        val converted = LinkedHashMap<Int, ConvertedNode>()
        for (node in ordered) {
            val definition = definitions[node.type]
            if (definition == null || definition.missing) {
                if (node.type in UI_ONLY_NODE_TYPES) continue
                converted[node.id] = ConvertedNode(
                    node = node,
                    json = null,
                    problems = listOf(
                        BuildProblem(
                            node.id,
                            node.type,
                            "服务端没有该节点的定义，请先在 ComfyUI 安装对应自定义节点",
                        ),
                    ),
                )
                continue
            }

            val nodeProblems = mutableListOf<BuildProblem>()
            val json = convertNode(node, definition, graph, definitions, resolver, nodeProblems, warnings)
            converted[node.id] = ConvertedNode(node, json, nodeProblems)
        }

        // 服务端只校验/执行"输出节点及其上游"。孤立节点（画布上忘了删的）不该阻断提交，
        // 也不该出现在提交的图里——真实工作流里就有这种节点。
        val roots = ordered
            .filter { definitions[it.type]?.isOutputNode == true }
            .map { it.id }
            .toSet()
        if (roots.isEmpty()) {
            return PromptBuild.Blocked(
                listOf(BuildProblem(message = "工作流没有输出节点（如 SaveImage / SaveVideo），不能提交")),
            )
        }

        val reachable = reachableNodeIds(roots, graph, resolver)
        val included = converted.values.filter { it.node.id in reachable }
        val ignored = converted.size - included.size
        if (ignored > 0) {
            warnings += "已忽略 $ignored 个未连接到输出节点的节点"
        }

        val problems = included.flatMap { it.problems }.distinct()
        if (problems.isNotEmpty()) return PromptBuild.Blocked(problems)

        val output = included.mapNotNull { it.json }
        if (output.isEmpty()) {
            return PromptBuild.Blocked(listOf(BuildProblem(message = "输出节点没有可提交的定义或输入")))
        }

        val prompt = LinkedHashMap<String, JsonElement>(output.size)
        for (item in included) {
            val json = item.json ?: continue
            prompt[item.node.id.toString()] = json
        }
        return PromptBuild.Ready(JsonObject(prompt), warnings.distinct())
    }

    private data class ConvertedNode(
        val node: UiNode,
        val json: JsonObject?,
        val problems: List<BuildProblem>,
    )

    private fun convertNode(
        node: UiNode,
        definition: NodeDefinition,
        graph: UiGraph,
        definitions: Map<String, NodeDefinition>,
        resolver: UiGraphResolver,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
    ): JsonObject {
        val inputs = LinkedHashMap<String, JsonElement>()

        // 控件值先铺满；后面解析到连线时再覆盖，和前端 graphToPrompt 的顺序一致。
        val widgetDefs = definition.inputs.filter { it.isWidget && !it.forceInput }
        inputs.putAll(WidgetValueReader.read(node, widgetDefs, problems, warnings))

        val declaredByName = definition.inputs.associateBy { it.name }

        node.inputs.forEachIndexed { index, uiInput ->
            if (uiInput.linkId == null) return@forEachIndexed
            val declared = declaredInput(uiInput.name, declaredByName) ?: return@forEachIndexed
            val resolved = resolver.resolve(node, index)
            if (resolved == null) {
                // 线还在，但上游整条链被旁路/静音掉了。控件输入保留已存的值（前端也这样），
                // 连线输入没有值可用，只能是错误。
                if (!declared.isWidget && declared.required && !declared.autogrow && !declared.dynamic) {
                    problems += BuildProblem(
                        node.id,
                        node.type,
                        "输入 ${uiInput.name} 的上游连接已被静音或旁路，且没有可穿透的来源",
                    )
                }
                return@forEachIndexed
            }

            val origin = graph.byId[resolved.nodeId]
            val originDefinition = origin?.let { definitions[it.type] }
            if (origin == null || originDefinition == null || originDefinition.missing) {
                problems += BuildProblem(
                    node.id,
                    node.type,
                    "输入 ${uiInput.name} 来自节点 #${resolved.nodeId} ${origin?.type.orEmpty()}" +
                        "，但服务端没有该节点的定义",
                )
                return@forEachIndexed
            }

            inputs[uiInput.name] = buildJsonArray {
                add(JsonPrimitive(resolved.nodeId))
                add(JsonPrimitive(resolved.slot))
            }
        }

        // 必填连线输入：没连、且不是控件/自动增长/动态附加项，就直接拒绝。
        for (declared in definition.inputs) {
            if (!declared.required || declared.isWidget || declared.autogrow || declared.dynamic) continue
            if (inputs.containsKey(declared.name)) continue
            if (problems.any { it.nodeId == node.id && it.message.contains(declared.name) }) continue
            problems += BuildProblem(
                node.id,
                node.type,
                "必填输入 ${declared.name} 没有连接",
            )
        }

        return buildJsonObject {
            put("class_type", definition.className)
            put("inputs", JsonObject(inputs))
            if (!node.title.isNullOrBlank()) {
                put("_meta", buildJsonObject { put("title", node.title) })
            }
        }
    }

    /** `images.image_1` 这类自动增长输入归属到声明的 `images`。 */
    private fun declaredInput(
        name: String,
        byName: Map<String, NodeInputDef>,
    ): NodeInputDef? {
        byName[name]?.let { return it }
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        return byName[name.substring(0, dot)]
    }
}

/** 旁路/静音穿透的结果：最终可用的上游节点与输出槽。 */
internal data class ResolvedLink(val nodeId: Int, val slot: Int)

/**
 * 从输出节点沿输入连线向上游回溯；旁路节点会被穿透，静音节点视为断开。
 *
 * 服务端只执行输出节点及其上游，孤立节点不会参与，所以这里得到的集合就是"真正会跑"的节点。
 */
internal fun reachableNodeIds(
    roots: Set<Int>,
    graph: UiGraph,
    resolver: UiGraphResolver,
): Set<Int> {
    val reachable = mutableSetOf<Int>()
    val stack = ArrayDeque<Int>()
    roots.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!reachable.add(id)) continue
        val node = graph.byId[id] ?: continue
        node.inputs.forEachIndexed { index, input ->
            if (input.linkId == null) return@forEachIndexed
            resolver.resolve(node, index)?.let { stack.addLast(it.nodeId) }
        }
    }
    return reachable
}

/**
 * 前端 `ExecutableNodeDTO.resolveInput/resolveOutput` 的 Kotlin 版本。
 *
 * 旁路节点不是删除，而是"同类型输入直接穿过去"；找不到可穿透的输入就断开。
 */
internal class UiGraphResolver(private val graph: UiGraph) {

    fun resolve(node: UiNode, inputIndex: Int): ResolvedLink? =
        resolveInput(node, inputIndex, mutableSetOf())

    private fun resolveInput(node: UiNode, inputIndex: Int, seen: MutableSet<String>): ResolvedLink? {
        val input = node.inputs.getOrNull(inputIndex) ?: return null
        val linkId = input.linkId ?: return null
        val link = graph.links[linkId] ?: return null
        return resolveOutput(link.originId, link.originSlot, input.type, seen)
    }

    private fun resolveOutput(
        nodeId: Int,
        slot: Int,
        expectedType: String,
        seen: MutableSet<String>,
    ): ResolvedLink? {
        val key = "$nodeId#$slot"
        if (!seen.add(key)) return null
        val node = graph.byId[nodeId] ?: return null
        return when (node.mode) {
            UiWorkflowParser.MODE_MUTED -> null
            UiWorkflowParser.MODE_BYPASS -> {
                val bypassSlot = bypassSlot(node, slot, expectedType) ?: return null
                resolveInput(node, bypassSlot, seen)
            }

            else -> ResolvedLink(node.id, slot)
        }
    }

    private fun bypassSlot(node: UiNode, outputSlot: Int, expectedType: String): Int? {
        val outputType = node.outputs.getOrNull(outputSlot)?.type.orEmpty()
        if (expectedType == "*" || expectedType.isEmpty()) {
            return if (node.inputs.size > outputSlot) outputSlot else 0
        }
        val sameIndex = node.inputs.getOrNull(outputSlot)
        if (sameIndex != null &&
            matches(sameIndex.type, outputType) &&
            matches(sameIndex.type, expectedType)
        ) {
            return outputSlot
        }
        val exact = node.inputs.indexOfFirst { matches(it.type, expectedType) }
        if (exact >= 0) return exact
        val compatible = node.inputs.indexOfFirst {
            matches(it.type, outputType) && matches(it.type, expectedType)
        }
        return compatible.takeIf { it >= 0 }
    }

    private fun matches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == "*" || b == "*") return true
        if (a == b) return true
        // 前端允许 `FLOAT,INT,BOOLEAN` 这种联合类型。
        val left = a.split(',').map { it.trim() }
        val right = b.split(',').map { it.trim() }
        return left.any { it in right }
    }
}

/**
 * 控件值读取。
 *
 * 新版工作流有 `widgets_values_named`，按名字取，最可靠。
 * 旧版只有数组，只能按 `input_order` 的位置来；同时跳过前端专用的两个槽位：
 * - `control_after_generate` 紧跟在种子控件后面；
 * - `image_upload` 紧跟在 LoadImage 的文件名后面。
 */
internal object WidgetValueReader {

    private val CONTROL_TOKENS = setOf("fixed", "increment", "decrement", "randomize")

    fun read(
        node: UiNode,
        defs: List<NodeInputDef>,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
    ): Map<String, JsonElement> {
        if (defs.isEmpty()) return emptyMap()
        return if (node.hasWidgetValuesNamed) {
            readNamed(node, defs, problems)
        } else {
            readPositional(node, defs, problems, warnings)
        }
    }

    private fun readNamed(
        node: UiNode,
        defs: List<NodeInputDef>,
        problems: MutableList<BuildProblem>,
    ): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (def in defs) {
            val value = node.widgetsNamed[def.name]
            if (value != null && value !is JsonNull) {
                out[def.name] = value
                continue
            }
            // 动态下拉框展开出来的附加控件只在用户选到对应分支时存在；
            // 工作流里没有值就表示没选到，不能用默认值硬塞进去。
            if (def.dynamic) continue
            val fallback = def.defaultValue
            if (fallback != null) {
                out[def.name] = fallback
            } else if (def.required) {
                problems += BuildProblem(
                    node.id,
                    node.type,
                    "缺少控件 ${def.name} 的值",
                )
            }
        }
        return out
    }

    private fun readPositional(
        node: UiNode,
        defs: List<NodeInputDef>,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
    ): Map<String, JsonElement> {
        val values = node.widgets ?: JsonArray(emptyList())
        val defByName = defs.associateBy { it.name }
        val out = LinkedHashMap<String, JsonElement>()
        val handled = mutableSetOf<String>()
        var index = 0
        var filledWithDefault = 0
        var missingRequired = 0

        fun fillDefault(def: NodeInputDef) {
            // 动态附加控件没有值 = 当前选项不需要它；补默认值反而会多传参数。
            if (def.dynamic) return
            val fallback = def.defaultValue
            if (fallback != null) {
                out[def.name] = fallback
                filledWithDefault++
            } else if (def.required) {
                missingRequired++
                problems += BuildProblem(
                    node.id,
                    node.type,
                    "缺少控件 ${def.name} 的值（控件顺序可能和当前服务端版本不一致）",
                )
            }
        }

        fun skipFrontendSlots(def: NodeInputDef?) {
            if (def?.controlAfterGenerate == true) {
                val token = values.getOrNull(index)?.let { (it as? JsonPrimitive)?.contentOrNull }
                // 只有确实是生成后控制值才跳；老文件没有这一格时不能误吃下一个控件。
                if (token != null && token in CONTROL_TOKENS) index++
            }
        }

        val uiWidgets = node.inputs.mapNotNull { it.widgetName?.takeIf { name -> name.isNotBlank() } }
        if (uiWidgets.isNotEmpty()) {
            // 工作流自己的控件顺序才是 widgets_values 的真实顺序；
            // object_info 的顺序只用来补齐保存时还不存在的新控件。
            val uploadIsWidget = uiWidgets.contains("upload")
            for (name in uiWidgets) {
                val def = defByName[name]
                val value = values.getOrNull(index)
                index++
                if (def != null) handled += def.name
                skipFrontendSlots(def)
                if (def?.imageUpload == true && !uploadIsWidget) {
                    val upload = values.getOrNull(index)?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (upload == "image") index++
                }
                if (value != null && value !is JsonNull) {
                    // def == null：前端专用控件（如 upload），服务端没有这个输入，丢弃。
                    if (def != null) out[def.name] = value
                } else if (def != null) {
                    fillDefault(def)
                }
            }
            for (def in defs) {
                if (def.name in handled) continue
                fillDefault(def)
            }
        } else {
            // 极少数节点（如 rgthree Seed）不把控件写进 inputs，只能按定义顺序读；
            // 末尾多出来的是前端按钮状态，忽略。
            for (def in defs) {
                val value = values.getOrNull(index)
                index++
                skipFrontendSlots(def)
                if (def.imageUpload) {
                    val upload = values.getOrNull(index)?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (upload == "image") index++
                }
                if (value != null && value !is JsonNull) {
                    out[def.name] = value
                } else {
                    fillDefault(def)
                }
            }
        }

        if (index < values.size) {
            warnings += "节点 #${node.id} ${node.type}：忽略了 ${values.size - index} 个前端专用控件值"
        }
        if (filledWithDefault > 0) {
            warnings += "节点 #${node.id} ${node.type}：$filledWithDefault 个控件使用服务端默认值补齐"
        }
        if (missingRequired > 0) {
            warnings += "节点 #${node.id} ${node.type}：$missingRequired 个必填控件没有值"
        }
        return out
    }
}
