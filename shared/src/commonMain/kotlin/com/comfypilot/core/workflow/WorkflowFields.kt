package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonElement

/**
 * 工作流里的一个可编辑/可查看字段。
 *
 * [common] 只是"首屏常用"的推荐，不代表其他字段不能改；全部字段在 [WorkflowFieldView.nodes] 里。
 */
data class WorkflowField(
    val nodeId: Int,
    val nodeType: String,
    val nodeTitle: String?,
    val inputName: String,
    val label: String?,
    val type: String,
    val options: List<String>,
    val value: JsonElement?,
    val linked: Boolean,
    val widget: Boolean,
    val required: Boolean,
    val common: Boolean,
) {
    /** 没有可用值、需要用户填/连线的字段；用于把这类节点排在输入区最前面。 */
    val needsInput: Boolean
        get() = when {
            linked -> false
            widget -> value == null
            else -> required
        }
}

data class WorkflowNodeView(
    val id: Int,
    val type: String,
    val displayName: String,
    val title: String?,
    val mode: Int,
    val missing: Boolean,
    /** 该节点在输出节点的上游，提交时会真正执行。 */
    val reachable: Boolean,
    val fields: List<WorkflowField>,
) {
    /**
     * 只有会真正参与本次提交的节点才提示“待填写”。
     *
     * 未启用/被旁路的节点仍会列在“全部节点”里供查看，但它们不进入 prompt，
     * 缺值不该被算成用户必须补的输入，更不能排到输入区最前面。
     */
    val needsInput: Boolean get() = reachable && fields.any { it.needsInput }
}

data class WorkflowFieldView(
    val path: String,
    val name: String,
    val format: WorkflowFormat,
    /** 首屏"常用输入"：只放能直接改的控件，不包含被上游连线驱动的。 */
    val commonFields: List<WorkflowField>,
    val nodes: List<WorkflowNodeView>,
    val problems: List<BuildProblem>,
) {
    val allFields: List<WorkflowField> get() = nodes.flatMap { it.fields }
}

/**
 * 从网页格式工作流里提取字段，供"输入表单 / 全部节点"两个视图使用。
 *
 * 这里不负责提交，也不修改任何值；转换与校验以 [PromptBuilder] 为准。
 */
object WorkflowFields {

    private val TEXT_INPUTS = setOf("text", "prompt", "negative_prompt", "positive_prompt", "system_prompt")
    private val SEED_INPUTS = setOf("seed", "noise_seed")
    private val SIZE_INPUTS = setOf(
        "width", "height", "length", "batch_size", "megapixels", "aspect_ratio", "resolution",
    )
    private val SAMPLER_INPUTS = setOf("steps", "cfg", "denoise", "sampler_name", "scheduler")
    private val SAMPLER_NODES = setOf(
        "KSampler", "KSamplerAdvanced", "SamplerCustomAdvanced", "BasicScheduler", "KSamplerSelect", "RandomNoise",
    )

    fun build(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
        forcedNormalNodeIds: Set<Int> = emptySet(),
        enabledOutputIds: Set<Int>? = null,
    ): WorkflowFieldView {
        if (document.format != WorkflowFormat.UI) {
            return WorkflowFieldView(
                path = document.path,
                name = document.name,
                format = document.format,
                commonFields = emptyList(),
                nodes = emptyList(),
                problems = emptyList(),
            )
        }

        val parsed = UiWorkflowParser.parse(document.raw)
            ?: return WorkflowFieldView(
                path = document.path,
                name = document.name,
                format = document.format,
                commonFields = emptyList(),
                nodes = emptyList(),
                problems = listOf(BuildProblem(message = "工作流不是有效的网页格式 JSON")),
            )
        val graph = parsed.withForcedNormal(forcedNormalNodeIds)

        val resolver = UiGraphResolver(graph)
        val problems = mutableListOf<BuildProblem>()
        if (graph.hasSubgraphs) {
            problems += BuildProblem(message = "工作流包含子图（subgraph），当前版本不支持编辑")
        }

        val roots = graph.nodes
            .filter { node ->
                node.mode == UiWorkflowParser.MODE_NORMAL &&
                    definitions[node.type]?.isOutputNode == true &&
                    (enabledOutputIds == null || node.id in enabledOutputIds)
            }
            .map { it.id }
            .toSet()
        val reachable = reachableNodeIds(roots, graph, resolver)

        val nodes = graph.nodes
            .sortedWith(compareBy({ it.order }, { it.id }))
            .map { node -> buildNode(node, definitions, resolver, node.id in reachable) }

        val common = nodes.filter { it.reachable }.flatMap { it.fields }
            .filter { it.common && it.widget && !it.linked }
            .distinctBy { it.nodeId to it.inputName }

        return WorkflowFieldView(
            path = document.path,
            name = document.name,
            format = document.format,
            commonFields = common,
            nodes = nodes,
            problems = problems,
        )
    }

    private fun buildNode(
        node: UiNode,
        definitions: Map<String, NodeDefinition>,
        resolver: UiGraphResolver,
        reachable: Boolean,
    ): WorkflowNodeView {
        val definition = definitions[node.type]
        if (definition == null || definition.missing) {
            return WorkflowNodeView(
                id = node.id,
                type = node.type,
                displayName = definition?.displayName ?: node.type,
                title = node.title,
                mode = node.mode,
                missing = true,
                reachable = reachable,
                fields = emptyList(),
            )
        }

        val widgetDefs = definition.inputs.filter { it.isWidget && !it.forceInput }
        val widgetValues = WidgetValueReader.read(node, widgetDefs, mutableListOf(), mutableListOf())
        val byName = definition.inputs.associateBy { it.name }

        val linkedNames = mutableSetOf<String>()
        node.inputs.forEachIndexed { index, input ->
            if (input.linkId == null) return@forEachIndexed
            val base = byName[input.name] ?: byName[input.name.substringBeforeLast('.', "")]
            if (base == null) return@forEachIndexed
            if (resolver.resolve(node, index) != null) linkedNames += base.name
        }

        val labels = node.inputs.associate { it.name to it.localizedName }
        val fields = mutableListOf<WorkflowField>()
        val seen = mutableSetOf<String>()

        for (declared in definition.inputs) {
            if (!seen.add(declared.name)) continue
            val linked = declared.name in linkedNames
            fields += WorkflowField(
                nodeId = node.id,
                nodeType = node.type,
                nodeTitle = node.title,
                inputName = declared.name,
                label = labels[declared.name] ?: declared.name,
                type = declared.type,
                options = declared.options,
                value = widgetValues[declared.name],
                linked = linked,
                widget = declared.isWidget,
                required = declared.required,
                common = isCommon(node.type, declared),
            )

            if (declared.autogrow) {
                // 自动增长输入在 API 里是 `name.sub` 的多个键，逐个列出来。
                node.inputs.filter { it.name.startsWith(declared.name + ".") }.forEach { sub ->
                    if (!seen.add(sub.name)) return@forEach
                    val index = node.inputs.indexOf(sub)
                    fields += WorkflowField(
                        nodeId = node.id,
                        nodeType = node.type,
                        nodeTitle = node.title,
                        inputName = sub.name,
                        label = sub.localizedName ?: sub.name.substringAfter('.'),
                        type = sub.type,
                        options = emptyList(),
                        value = null,
                        linked = index >= 0 && resolver.resolve(node, index) != null,
                        widget = false,
                        required = false,
                        common = false,
                    )
                }
            }
        }

        return WorkflowNodeView(
            id = node.id,
            type = node.type,
            displayName = definition.displayName,
            title = node.title,
            mode = node.mode,
            missing = false,
            reachable = reachable,
            fields = fields,
        )
    }

    private fun isCommon(nodeType: String, input: NodeInputDef): Boolean = when {
        input.type == "STRING" && input.name in TEXT_INPUTS -> true
        nodeType == "LoadImage" && input.name == "image" -> true
        input.name in SEED_INPUTS -> true
        input.controlAfterGenerate -> true
        input.name in SIZE_INPUTS -> true
        nodeType in SAMPLER_NODES && input.name in SAMPLER_INPUTS -> true
        else -> false
    }
}
