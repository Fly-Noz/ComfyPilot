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
    private val UI_ONLY_NODE_TYPES = WorkflowCodec.UI_ONLY_NODE_TYPES

    fun build(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
        overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
        /** null = 按保存时的模式；非 null = 只提交这些输出节点。 */
        enabledOutputIds: Set<Int>? = null,
        /** 选中的节点图内的节点：临时当作正常执行，用于在 App 里启用被旁路的分支。 */
        forcedNormalNodeIds: Set<Int> = emptySet(),
        /**
         * 用户逐个关闭（旁路）的节点。它优先于 [forcedNormalNodeIds]：
         * 图级选择不能把用户刚关掉的节点重新打开。
         */
        bypassedNodeIds: Set<Int> = emptySet(),
        /**
         * 用户逐个重新启用的节点。用于把文件里保存为静音/旁路的节点恢复执行；
         * 优先级低于 [bypassedNodeIds]。
         */
        enabledNodeIds: Set<Int> = emptySet(),
        /**
         * 每次提交的唯一短 token。保存类节点的 filename_prefix 会追加它，
         * 避免工作流前缀不唯一时后一次产物覆盖前一次。
         */
        runToken: String? = null,
        /** 本次会话里被用户断开的连线：构建时按输入未连接处理。 */
        removedLinkIds: Set<Int> = emptySet(),
        /** 会话级新建/改接连线：在解析后应用到图上。 */
        linkOverrides: List<InputLinkOverride> = emptyList(),
        /**
         * 用户显式打开「忽略本地校验」时为 true：问题只记成警告，仍然把能转换的节点
         * 交给服务器，由服务端做最终判定。
         */
        force: Boolean = false,
    ): PromptBuild {
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

        val parsed = UiWorkflowParser.parse(document.raw)
            ?: return PromptBuild.Blocked(listOf(BuildProblem(message = "工作流不是有效的网页格式 JSON")))
        val graph = parsed
            .withoutLinks(removedLinkIds)
            .withInputLinks(linkOverrides)
            .withEffectiveModes(
                forcedNormalNodeIds = forcedNormalNodeIds,
                bypassedNodeIds = bypassedNodeIds,
                enabledNodeIds = enabledNodeIds,
            )
        if (graph.hasSubgraphs) {
            return PromptBuild.Blocked(
                listOf(BuildProblem(message = "工作流包含子图（subgraph），当前版本不支持转换")),
            )
        }

        val warnings = mutableListOf<String>()
        val expanded = expandPowerLoraNodes(graph, overrides, warnings)
        val resolver = UiGraphResolver(expanded)

        val ordered = expanded.nodes
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
            val json = convertNode(
                node,
                definition,
                expanded,
                definitions,
                resolver,
                overrides[node.id].orEmpty(),
                nodeProblems,
                warnings,
                runToken,
            )
            converted[node.id] = ConvertedNode(node, json, nodeProblems)
        }

        // 服务端只校验/执行"输出节点及其上游"。孤立节点（画布上忘了删的）不该阻断提交，
        // 也不该出现在提交的图里——真实工作流里就有这种节点。
        val roots = ordered
            .filter { node ->
                definitions[node.type]?.isOutputNode == true &&
                    (enabledOutputIds == null || node.id in enabledOutputIds)
            }
            .map { it.id }
            .toSet()
        if (roots.isEmpty()) {
            return PromptBuild.Blocked(
                listOf(
                    BuildProblem(
                        message = if (enabledOutputIds != null) {
                            "没有启用任何节点图，不能提交"
                        } else {
                            "工作流没有输出节点（如 SaveImage / SaveVideo），不能提交"
                        },
                    ),
                ),
            )
        }

        val reachable = reachableNodeIds(roots, expanded, resolver)
        val included = converted.values.filter { it.node.id in reachable }
        val ignored = converted.size - included.size
        if (ignored > 0) {
            warnings += "已忽略 $ignored 个未连接到输出节点的节点"
        }

        val problems = included.flatMap { it.problems }.distinct()
        if (problems.isNotEmpty()) {
            if (!force) return PromptBuild.Blocked(problems)
            warnings += problems.map { "强制提交（忽略本地问题）：${it.format()}" }
        }

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
        overrides: Map<String, JsonElement>,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
        runToken: String?,
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
            // PrimitiveNode 同理：它携带的字面量直接写进下游输入，API 图里没有这个节点。
            if (origin?.type == "PrimitiveNode") {
                val literal = origin.widgetsNamed["value"] ?: origin.widgets?.getOrNull(0)
                if (literal == null) {
                    problems += BuildProblem(
                        node.id,
                        node.type,
                        "输入 ${uiInput.name} 的上游 PrimitiveNode 没有值",
                    )
                } else {
                    inputs[uiInput.name] = literal
                }
                return@forEachIndexed
            }
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
                // ComfyUI 的 API 图用字符串节点 ID 作为连线源；写成数字会在
                // validate_inputs 里触发 KeyError，表现为下游节点校验失败。
                add(JsonPrimitive(resolved.nodeId.toString()))
                add(JsonPrimitive(resolved.slot))
            }
        }

        // 用户在本次会话里改过的控件值。只允许覆盖"服务端声明为控件、且没有被连线驱动"的输入：
        // 覆盖连线会悄悄改变拓扑，覆盖不存在的字段说明界面和定义已经对不上，两者都直接拒绝。
        for ((name, value) in overrides) {
            val declared = declaredByName[name]
            if (declared == null || !declared.isWidget || declared.forceInput) {
                // 旧会话残留的覆盖值：字段现在不存在、或本来就是连线/非控件输入。
                // 忽略它并告警，别让一条历史记录把整张图卡在不可提交。
                warnings += "节点 ${node.title}（#${node.id}）的字段 $name 已不可覆盖（不存在或由连线提供），忽略本地值"
                continue
            }
            val linked = node.inputs.any { uiInput ->
                if (uiInput.linkId == null) {
                    false
                } else {
                    val base = declaredByName[uiInput.name]
                        ?: declaredByName[uiInput.name.substringBeforeLast('.', "")]
                    base?.name == declared.name
                }
            }
            if (linked) {
                // 旧会话可能存过「当时还没连线」的覆盖值；连线优先，丢掉覆盖而不是
                // 把整张图卡在「字段 X 由连线提供，不能覆盖」上。
                warnings += "节点 ${node.title}（#${node.id}）的字段 ${declared.name} 由连线提供，已忽略本地覆盖值"
                continue
            }
            // 会话里存下来的旧值可能已经不在候选里（换模型/换节点版本）：换回默认值并告警，
            // 别让一条历史覆盖把整次提交卡死。图片上传字段例外：刚上传的文件本来就不在
            // 打开工作流时抓到的候选快照里，替换成别的图会让第二个 LoadImage 用错文件。
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (!declared.imageUpload && !declared.dynamic &&
                declared.options.isNotEmpty() && text != null && text !in declared.options
            ) {
                val fallback = declared.defaultValue?.takeIf { it !is JsonNull }
                    ?: declared.options.firstOrNull()?.let { JsonPrimitive(it) }
                if (fallback == null) {
                    problems += BuildProblem(
                        node.id,
                        node.type,
                        "字段 ${declared.name} 的值「$text」不在候选列表里（候选：${declared.options.take(6).joinToString("、")}）",
                    )
                    continue
                }
                val shown = (fallback as? JsonPrimitive)?.contentOrNull ?: fallback.toString()
                warnings += "节点 ${node.title}（#${node.id}）的 ${declared.name} 旧值「$text」已不在候选里，提交时改用「$shown」"
                inputs[declared.name] = fallback
                continue
            }
            inputs[declared.name] = value
            // 控件值原本缺失时，上面的读取会记一条问题；覆盖之后这条问题不再成立。
            problems.removeAll {
                it.nodeId == node.id && it.message.contains("缺少控件 ${declared.name} 的值")
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

        // 工作流作者常用 `%date:yyyyMMddhhmmss%` 这类 ComfyUI 不认识的日期格式。
        // 服务端会把它当普通字符，Windows 又会把冒号换掉，导致计数器永远从 1 开始
        // 并覆盖上一次的产物。这里给每次提交追加一个唯一后缀，保证产物不会互相覆盖。
        val token = runToken.orEmpty()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(24)
        val prefix = inputs["filename_prefix"]
        if (token.isNotEmpty() && prefix is JsonPrimitive && prefix.isString) {
            val base = prefix.content.ifBlank { "ComfyPilot" }.trimEnd('_')
            inputs["filename_prefix"] = JsonPrimitive("${base}_$token")
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
        // Reroute 是纯视觉转接点：1 进 1 出、类型直通，API 图里不存在。
        // 桌面端 graphToPrompt 也把它折到上游，这里必须同样穿透，否则下游会
        // 把 Reroute 当成真实来源，报「服务端没有该节点的定义」。
        if (node.type == "Reroute") {
            return resolveInput(node, 0, seen)
        }
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
/**
 * rgthree 的 Power Lora Loader 是**前端虚拟节点**：`object_info` 里只声明 model/clip，
 * LoRA 名与权重全都藏在 `widgets_values` 的对象数组里，由前端在 graphToPrompt 时
 * 展开成一串 LoraLoader。我们直接把它当普通节点提交，服务端收到的就是一个
 * 「不带任何 LoRA」的直通节点——用户看到的现象正是「权重不生效」。
 *
 * 这里在转换前把它展开成真正的 LoraLoader 链；没有启用的 LoRA 时按直通处理。
 */
private val RGTHREE_POWER_LORA_TYPES = setOf(RGTHREE_POWER_LORA_TYPE)

private data class LoraEntry(val name: String, val strength: Double, val clipStrength: Double)

/** 读取条目时先看用户覆盖（`lora_1.strength` 等），再看文件里的原值。 */
private fun parsePowerLoraEntries(
    node: UiNode,
    overrides: Map<String, JsonElement>,
): List<LoraEntry> {
    val raw = node.widgets ?: return emptyList()
    val result = mutableListOf<LoraEntry>()
    var index = 0
    for (element in raw) {
        val obj = element as? JsonObject ?: continue
        // 表头/表尾是前端组件描述，不是 LoRA 条目。
        if (obj["type"] != null) continue
        if (obj["lora"] !is JsonPrimitive) continue
        index += 1
        val key = "lora_$index"
        val lora = (overrides["$key.lora"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["lora"] as? JsonPrimitive)?.contentOrNull
            ?: continue
        if (lora.isBlank() || lora == "None") continue
        val on = (overrides["$key.on"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: (obj["on"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: true
        if (!on) continue
        val strength = (overrides["$key.strength"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: (obj["strength"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: 1.0
        val second = (overrides["$key.strengthTwo"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: (obj["strengthTwo"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        result += LoraEntry(lora, strength, second ?: strength)
    }
    return result
}

private fun expandPowerLoraNodes(
    graph: UiGraph,
    overrides: Map<Int, Map<String, JsonElement>>,
    warnings: MutableList<String>,
): UiGraph {
    val targets = graph.nodes.filter {
        it.mode == UiWorkflowParser.MODE_NORMAL && it.type in RGTHREE_POWER_LORA_TYPES
    }
    if (targets.isEmpty()) return graph
    val targetIds = targets.map { it.id }.toSet()
    var nextNodeId = (graph.nodes.maxOfOrNull { it.id } ?: 0) + 1
    var nextLinkId = (graph.links.keys.maxOrNull() ?: 0) + 1
    val nodes = graph.nodes.filterNot { it.id in targetIds }.toMutableList()
    val links = LinkedHashMap(graph.links)

    for (target in targets) {
        val entries = parsePowerLoraEntries(target, overrides[target.id].orEmpty())
        val incomingModel = graph.links.values.firstOrNull { it.targetId == target.id && it.targetSlot == 0 }
        val incomingClip = graph.links.values.firstOrNull { it.targetId == target.id && it.targetSlot == 1 }
        val downstream = graph.links.values.filter { it.originId == target.id }
        links.values.removeAll { it.originId == target.id || it.targetId == target.id }

        var modelSource = incomingModel?.let { it.originId to it.originSlot }
        var clipSource = incomingClip?.let { it.originId to it.originSlot }
        // rgthree 的 Power Lora Loader 在没有接 CLIP 时，前端会用 LoraLoaderModelOnly
        // 展开（只处理 MODEL）。我们之前一律生成 LoraLoader，导致 clip 必填校验失败，
        // 用户看到的正是「LoRA 不生效 / 提交被拒」。
        val hasClip = clipSource != null

        if (entries.isEmpty()) {
            warnings += "节点「${target.title ?: target.type}」没有启用的 LoRA，已按直通处理"
        }
        for (entry in entries) {
            val id = nextNodeId++
            var modelLinkId: Int? = null
            var clipLinkId: Int? = null
            modelSource?.let { (origin, slot) ->
                val linkId = nextLinkId++
                links[linkId] = UiLink(linkId, origin, slot, id, 0, "MODEL")
                modelLinkId = linkId
            }
            if (hasClip) {
                clipSource?.let { (origin, slot) ->
                    val linkId = nextLinkId++
                    links[linkId] = UiLink(linkId, origin, slot, id, 1, "CLIP")
                    clipLinkId = linkId
                }
            }
            nodes += UiNode(
                id = id,
                type = if (hasClip) "LoraLoader" else "LoraLoaderModelOnly",
                title = "${target.title ?: "Power LoRA"} · ${entry.name}",
                mode = UiWorkflowParser.MODE_NORMAL,
                order = target.order,
                inputs = if (hasClip) {
                    listOf(
                        UiInput("model", "MODEL", modelLinkId, null, null),
                        UiInput("clip", "CLIP", clipLinkId, null, null),
                    )
                } else {
                    listOf(UiInput("model", "MODEL", modelLinkId, null, null))
                },
                outputs = if (hasClip) {
                    listOf(UiOutput("MODEL", "MODEL"), UiOutput("CLIP", "CLIP"))
                } else {
                    listOf(UiOutput("MODEL", "MODEL"))
                },
                widgets = JsonArray(
                    if (hasClip) {
                        listOf(
                            JsonPrimitive(entry.name),
                            JsonPrimitive(entry.strength),
                            JsonPrimitive(entry.clipStrength),
                        )
                    } else {
                        listOf(
                            JsonPrimitive(entry.name),
                            JsonPrimitive(entry.strength),
                        )
                    },
                ),
                widgetsNamed = emptyMap(),
                x = target.x,
                y = target.y,
            )
            modelSource = id to 0
            if (hasClip) clipSource = id to 1
        }

        // 下游原来接虚拟节点的输出：改接链尾；一个 LoRA 都没有时接上游（直通）。
        for (link in downstream) {
            val source = if (link.originSlot == 0) modelSource else clipSource
            if (source == null) {
                if (link.originSlot != 0) {
                    warnings += "节点「${target.title ?: target.type}」的 CLIP 输出没有上游，已断开下游连线"
                }
                continue
            }
            links[link.id] = link.copy(originId = source.first, originSlot = source.second)
        }
    }
    return graph.copy(
        nodes = nodes.sortedWith(compareBy({ it.order }, { it.id })),
        links = links,
    )
}

internal object WidgetValueReader {

    private val CONTROL_TOKENS = setOf("fixed", "increment", "decrement", "randomize")

    fun read(
        node: UiNode,
        defs: List<NodeInputDef>,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
    ): Map<String, JsonElement> {
        if (defs.isEmpty()) return emptyMap()
        val raw = if (node.hasWidgetValuesNamed) {
            readNamed(node, defs, problems)
        } else {
            readPositional(node, defs, problems, warnings)
        }
        return sanitizeOptions(node, defs, raw, problems, warnings)
    }

    /**
     * 下拉框的值必须落在候选里。
     *
     * 选项会随自定义节点版本、模型文件或语言变化（例如 `16:9 (Landscape Widescreen)` →
     * `16:9 (Widescreen)`）。旧工作流或旧会话存下的值提交时会被服务端整批拒绝
     * （Value not in list），这里提前换回默认值并留一条告警。
     */
    private fun sanitizeOptions(
        node: UiNode,
        defs: List<NodeInputDef>,
        values: Map<String, JsonElement>,
        problems: MutableList<BuildProblem>,
        warnings: MutableList<String>,
    ): Map<String, JsonElement> {
        val defByName = defs.associateBy { it.name }
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, value) in values) {
            val def = defByName[name]
            val text = (value as? JsonPrimitive)?.contentOrNull
            // imageUpload 的候选是服务器当前的文件列表，本来就会超出定义里的快照；
            // dynamic 附加控件的候选也随当前选项变化，两者都不能拿旧候选去卡。
            if (
                def == null || def.options.isEmpty() || def.imageUpload || def.dynamic ||
                text == null || text in def.options
            ) {
                out[name] = value
                continue
            }
            // 不自动替换：候选可能来自尚未刷新完的 object_info，也可能是另一台服务器；
            // 静默换成第一个候选会让用户拿错模型/文件名提交。保留原值并明确告知，
            // 服务端拒绝时用户能看到真实错误，再自己重新选。
            out[name] = value
            warnings += "节点 ${node.title}（#${node.id}）的 $name 值「$text」不在当前候选里，" +
                "仍按原值提交；若服务端拒绝，请重新选择（候选可能尚未加载完整）"
        }
        return out
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
