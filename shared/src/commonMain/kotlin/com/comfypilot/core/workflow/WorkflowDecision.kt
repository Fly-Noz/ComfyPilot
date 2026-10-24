package com.comfypilot.core.workflow

import kotlinx.serialization.json.JsonElement

/**
 * 一次工作流编辑里与提交有关的静态判断。
 *
 * 节点图、字段、待加载/缺失节点定义都只依赖工作流原文和节点定义，不依赖用户当前
 * 输入的值。平台可以缓存这份结果，用户改字段时只重算 [WorkflowDecision]。
 */
class WorkflowAnalysis internal constructor(
    internal val document: WorkflowDocument,
    internal val definitions: Map<String, NodeDefinition>,
    val selection: WorkflowGraphSelection,
    val fields: WorkflowFieldView,
    val pendingNodeClasses: List<String>,
    val missingNodeClasses: List<String>,
) {
    val path: String get() = document.path
    val name: String get() = document.name
    val format: WorkflowFormat get() = document.format
    val graphs: List<WorkflowGraph> get() = selection.graphs
    val defaultGraphIds: List<String> get() = graphs.filter { it.enabled }.map { it.id }
    val selectedGraphIds: Set<String> get() = selection.selectedIds
    val enabledOutputIds: Set<Int>? get() = selection.enabledOutputIds
    val forcedNormalNodeIds: Set<Int> get() = selection.forcedNormalNodeIds
    /** 用户在当前工作流里显式关闭的节点（优先于图级强制启用）。 */
    val bypassedNodeIds: Set<Int> get() = selection.bypassedNodeIds
    /** 用户在当前工作流里显式重新启用的节点（把文件里的静音/旁路恢复执行）。 */
    val enabledNodeIds: Set<Int> get() = selection.enabledNodeIds
    val removedLinkIds: Set<Int> get() = selection.removedLinkIds
    val commonFields: List<WorkflowField> get() = fields.commonFields
    val pendingNodeClassCount: Int get() = pendingNodeClasses.size

    /** 节点定义还没到齐时，两端都显示同一句阻断原因。 */
    val pendingBlockReason: String?
        get() = if (pendingNodeClasses.isEmpty()) {
            null
        } else {
            "不可提交：正在加载节点定义（还有 ${pendingNodeClasses.size} 个类）"
        }

    /** 未启用图里的节点；它们仍保留置顶状态，但不参与排序和提交。 */
    val disabledNodeIds: Set<Int>
        get() = graphs.filter { it.id !in selectedGraphIds }.flatMap { it.nodeIds }.toSet()
}

/**
 * 两端共享的完整决策结果。
 *
 * 界面只负责渲染：输入区节点顺序、能否提交、阻断原因、警告和最终 prompt 都来自这里。
 * [nodes] 已经按置顶/可达/缺值排好序；[build] 里的 prompt 只有在本次提交传入
 * `runToken` 时才会追加唯一文件名后缀。
 */
data class WorkflowDecision(
    val analysis: WorkflowAnalysis,
    val nodes: List<WorkflowNodeView>,
    val build: PromptBuild,
) {
    val path: String get() = analysis.path
    val name: String get() = analysis.name
    val format: WorkflowFormat get() = analysis.format
    val graphs: List<WorkflowGraph> get() = analysis.graphs
    val defaultGraphIds: List<String> get() = analysis.defaultGraphIds
    val selectedGraphIds: Set<String> get() = analysis.selectedGraphIds
    val enabledOutputIds: Set<Int>? get() = analysis.enabledOutputIds
    val forcedNormalNodeIds: Set<Int> get() = analysis.forcedNormalNodeIds
    val bypassedNodeIds: Set<Int> get() = analysis.bypassedNodeIds
    val enabledNodeIds: Set<Int> get() = analysis.enabledNodeIds
    val removedLinkIds: Set<Int> get() = analysis.removedLinkIds
    val commonFields: List<WorkflowField> get() = analysis.commonFields
    val pendingNodeClasses: List<String> get() = analysis.pendingNodeClasses
    val missingNodeClasses: List<String> get() = analysis.missingNodeClasses
    val pendingNodeClassCount: Int get() = analysis.pendingNodeClassCount
    val disabledNodeIds: Set<Int> get() = analysis.disabledNodeIds
    val fields: WorkflowFieldView get() = analysis.fields
    val reachableNodeCount: Int get() = fields.nodes.count { it.reachable }

    /** 字段问题 + 转换问题；两条路径产出的问题在这里只保留一份。 */
    val problems: List<BuildProblem> = (
        fields.problems + when (build) {
            is PromptBuild.Blocked -> build.problems
            is PromptBuild.Ready -> emptyList()
        }
    ).distinctBy { Triple(it.nodeId, it.nodeType, it.message) }

    val warnings: List<String> = when (build) {
        is PromptBuild.Ready -> build.warnings
        is PromptBuild.Blocked -> emptyList()
    }

    val canSubmit: Boolean get() = build is PromptBuild.Ready && pendingNodeClasses.isEmpty()

    /** 按钮禁用时直接显示这句话；两端不再各自拼接原因。 */
    val blockReason: String? = when {
        canSubmit -> null
        analysis.pendingBlockReason != null -> analysis.pendingBlockReason
        problems.isNotEmpty() -> "不可提交：${problems.first().format()}"
        missingNodeClasses.isNotEmpty() ->
            "不可提交：服务端缺少节点 ${missingNodeClasses.joinToString("、")}"
        else -> "不可提交：工作流还没有就绪"
    }
}

/** 共享的工作流编辑/提交判断入口。 */
object WorkflowDecisions {

    /**
     * 只计算与字段值无关的部分。平台可以按工作流/节点图/节点定义缓存它，
     * 用户改字段时不必重新提取整份字段模型。
     */
    fun analyze(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
        requestedGraphIds: Set<String>? = null,
        nodeInfoRequested: Set<String> = emptySet(),
        bypassedNodeIds: Set<Int> = emptySet(),
        enabledNodeIds: Set<Int> = emptySet(),
        removedLinkIds: Set<Int> = emptySet(),
    ): WorkflowAnalysis {
        val graphs = if (document.format == WorkflowFormat.UI) {
            WorkflowGraphs.analyze(document, definitions)
        } else {
            emptyList()
        }
        val defaultIds = graphs.filter { it.enabled }.map { it.id }.toSet()
        val knownIds = graphs.map { it.id }.toSet()
        val selectedIds = if (requestedGraphIds == null) defaultIds else requestedGraphIds.intersect(knownIds)
        val selected = graphs.filter { it.id in selectedIds }
        val selection = WorkflowGraphSelection(
            graphs = graphs,
            selectedIds = selectedIds,
            enabledOutputIds = if (graphs.isEmpty()) null else selected.flatMap { it.outputNodeIds }.toSet(),
            // 只强制启用“文件里本来关着、这次被用户主动打开”的节点图；
            // 默认就启用的图保留文件里保存的单节点静音/旁路状态。
            forcedNormalNodeIds = if (graphs.isEmpty()) {
                emptySet()
            } else {
                selected.filter { !it.enabled }.flatMap { it.nodeIds }.toSet()
            },
            bypassedNodeIds = bypassedNodeIds,
            enabledNodeIds = enabledNodeIds,
            removedLinkIds = removedLinkIds,
        )

        val fields = WorkflowFields.build(
            document = document,
            definitions = definitions,
            forcedNormalNodeIds = selection.forcedNormalNodeIds,
            enabledOutputIds = selection.enabledOutputIds,
            bypassedNodeIds = selection.bypassedNodeIds,
            enabledNodeIds = selection.enabledNodeIds,
            removedLinkIds = selection.removedLinkIds,
        )

        val allClasses = document.nodeClasses.distinct()
        val missingClasses = allClasses.filter { definitions[it]?.missing != false }
        val pendingClasses = missingClasses.filter { it !in nodeInfoRequested }
        return WorkflowAnalysis(
            document = document,
            definitions = definitions,
            selection = selection,
            fields = fields,
            pendingNodeClasses = pendingClasses,
            missingNodeClasses = missingClasses.filter { it !in pendingClasses },
        )
    }

    /**
     * 在静态分析上叠加用户 overrides 和本次提交的 token。
     *
     * [runToken] 默认为空，所以渲染视图时不会把唯一后缀带进字段；只有真正提交前
     * 再调用一次并传入 token。
     */
    fun decide(
        analysis: WorkflowAnalysis,
        overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
        pinnedNodeIds: Set<Int> = emptySet(),
        runToken: String? = null,
    ): WorkflowDecision {
        val build = PromptBuilder.build(
            document = analysis.document,
            definitions = analysis.definitions,
            overrides = overrides,
            enabledOutputIds = analysis.enabledOutputIds,
            forcedNormalNodeIds = analysis.forcedNormalNodeIds,
            bypassedNodeIds = analysis.bypassedNodeIds,
            enabledNodeIds = analysis.enabledNodeIds,
            removedLinkIds = analysis.removedLinkIds,
            runToken = runToken,
        )
        return WorkflowDecision(
            analysis = analysis,
            nodes = orderNodes(analysis.fields.nodes, pinnedNodeIds),
            build = build,
        )
    }

    /** 一次算完静态分析和完整决策；适合鸿蒙桥这种无本地缓存的调用。 */
    fun evaluate(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
        requestedGraphIds: Set<String>? = null,
        pinnedNodeIds: Set<Int> = emptySet(),
        overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
        nodeInfoRequested: Set<String> = emptySet(),
        runToken: String? = null,
        bypassedNodeIds: Set<Int> = emptySet(),
        enabledNodeIds: Set<Int> = emptySet(),
    ): WorkflowDecision = decide(
        analysis = analyze(
            document = document,
            definitions = definitions,
            requestedGraphIds = requestedGraphIds,
            nodeInfoRequested = nodeInfoRequested,
            bypassedNodeIds = bypassedNodeIds,
            enabledNodeIds = enabledNodeIds,
        ),
        overrides = overrides,
        pinnedNodeIds = pinnedNodeIds,
        runToken = runToken,
    )

    /**
     * 置顶且会提交的节点最前；其余节点严格保持工作流原文里的顺序。
     *
     * 排序只看“原文顺序 + 置顶”，绝不看本次会话的单节点开关：关闭一个节点不能让它
     * （或它的下游）跳到列表底部。[WorkflowNodeView.orderReachable] 是忽略单节点开关
     * 的可达性，未启用节点图里的节点仍然会排到最后（这是图级启停，不是单节点开关）。
     */
    fun orderNodes(
        nodes: List<WorkflowNodeView>,
        pinnedNodeIds: Set<Int>,
    ): List<WorkflowNodeView> = nodes.sortedWith(
        compareBy<WorkflowNodeView>(
            { !(it.orderReachable && it.id in pinnedNodeIds) },
            { !it.orderReachable },
            { !it.orderNeedsInput },
        ),
    )

    /**
     * 关闭某个节点前的影响探测：只回答"关掉它还能不能构建出可执行的输出"。
     *
     * 用当前选择构建两次 prompt（一次原样、一次额外旁路 [nodeId]），并检查可执行的
     * 输出节点是否还在。开销只在用户点开关时发生，不进入每帧状态。
     */
    fun probeNodeDisable(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
        nodeId: Int,
        requestedGraphIds: Set<String>? = null,
        overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
        bypassedNodeIds: Set<Int> = emptySet(),
        enabledNodeIds: Set<Int> = emptySet(),
    ): NodeDisableImpact {
        if (document.format != WorkflowFormat.UI) return NodeDisableImpact(breaking = false, reason = null)
        return probeNodeDisable(
            analysis = analyze(
                document = document,
                definitions = definitions,
                requestedGraphIds = requestedGraphIds,
                bypassedNodeIds = bypassedNodeIds,
                enabledNodeIds = enabledNodeIds,
            ),
            nodeId = nodeId,
            overrides = overrides,
        )
    }

    /**
     * 已有静态分析时的影响探测（界面点开关时用这个）：不必重新分析整张图，
     * 只需按当前选择构建两次 prompt。
     */
    fun probeNodeDisable(
        analysis: WorkflowAnalysis,
        nodeId: Int,
        overrides: Map<Int, Map<String, JsonElement>> = emptyMap(),
    ): NodeDisableImpact {
        val target = analysis.fields.nodes.firstOrNull { it.id == nodeId }
            ?: return NodeDisableImpact(breaking = false, reason = null)
        val nextBypass = analysis.bypassedNodeIds + nodeId
        val nextEnabled = analysis.enabledNodeIds - nodeId

        // 第一次构建确认当前选择下的状态；第二次只多关这一个节点。
        PromptBuilder.build(
            document = analysis.document,
            definitions = analysis.definitions,
            overrides = overrides,
            enabledOutputIds = analysis.enabledOutputIds,
            forcedNormalNodeIds = analysis.forcedNormalNodeIds,
            bypassedNodeIds = analysis.bypassedNodeIds,
            enabledNodeIds = analysis.enabledNodeIds,
        )
        val disabledBuild = PromptBuilder.build(
            document = analysis.document,
            definitions = analysis.definitions,
            overrides = overrides,
            enabledOutputIds = analysis.enabledOutputIds,
            forcedNormalNodeIds = analysis.forcedNormalNodeIds,
            bypassedNodeIds = nextBypass,
            enabledNodeIds = nextEnabled,
        )

        // 当前视图里的 mode 已经是生效后的模式；关掉目标节点只影响它自己这一位。
        val enabledOutputIds = analysis.enabledOutputIds
        val executableOutputs = analysis.fields.nodes
            .filter { node ->
                val mode = if (node.id == nodeId) UiWorkflowParser.MODE_BYPASS else node.mode
                mode == UiWorkflowParser.MODE_NORMAL &&
                    analysis.definitions[node.type]?.isOutputNode == true &&
                    (enabledOutputIds == null || node.id in enabledOutputIds)
            }
            .map { it.id }
            .toSet()

        if (disabledBuild is PromptBuild.Ready && executableOutputs.isNotEmpty()) {
            return NodeDisableImpact(breaking = false, reason = null)
        }
        val targetIsOutput = analysis.definitions[target.type]?.isOutputNode == true
        val reason = when {
            targetIsOutput && executableOutputs.isEmpty() -> "该节点是输出节点，关闭后没有可执行的输出"
            disabledBuild is PromptBuild.Blocked -> describeDisableProblem(disabledBuild.problems.firstOrNull())
            executableOutputs.isEmpty() -> "关闭后没有可执行的输出"
            else -> null
        }
        return NodeDisableImpact(breaking = true, reason = reason)
    }

    /** 把转换问题翻译成一句短的关闭影响说明；没有节点信息的通用问题直接带上原因。 */
    private fun describeDisableProblem(problem: BuildProblem?): String {
        problem ?: return "关闭后工作流不能构建"
        val nodeId = problem.nodeId ?: return "关闭后${problem.message}"
        if (problem.message.contains("必填输入") || problem.message.contains("上游连接")) {
            val type = problem.nodeType?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            return "关闭后 #$nodeId$type 的必填输入没有来源"
        }
        return "关闭后 ${problem.format()}"
    }
}

/** 关闭单个节点的影响：会不会让工作流无法构建/没有可执行输出，以及一句原因。 */
data class NodeDisableImpact(
    val breaking: Boolean,
    /** 只在 [breaking] 为 true 时有值，用于确认弹窗的第二行。 */
    val reason: String?,
)

/** 选中了哪些节点图，以及由此派生的输出节点/强制正常执行节点。 */
data class WorkflowGraphSelection(
    val graphs: List<WorkflowGraph>,
    val selectedIds: Set<String>,
    val enabledOutputIds: Set<Int>?,
    val forcedNormalNodeIds: Set<Int>,
    /** 用户逐个关闭的节点；覆盖 [forcedNormalNodeIds]。 */
    val bypassedNodeIds: Set<Int> = emptySet(),
    /** 用户逐个重新启用的节点；把文件里的静音/旁路恢复为正常执行。 */
    val enabledNodeIds: Set<Int> = emptySet(),
    /** 用户断开的连线：提交时按未连接处理，不写回工作流文件。 */
    val removedLinkIds: Set<Int> = emptySet(),
) {
    val selectedGraphs: List<WorkflowGraph> get() = graphs.filter { it.id in selectedIds }
}
