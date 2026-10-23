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
            forcedNormalNodeIds = if (graphs.isEmpty()) emptySet() else selected.flatMap { it.nodeIds }.toSet(),
        )

        val fields = WorkflowFields.build(
            document = document,
            definitions = definitions,
            forcedNormalNodeIds = selection.forcedNormalNodeIds,
            enabledOutputIds = selection.enabledOutputIds,
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
    ): WorkflowDecision = decide(
        analysis = analyze(document, definitions, requestedGraphIds, nodeInfoRequested),
        overrides = overrides,
        pinnedNodeIds = pinnedNodeIds,
        runToken = runToken,
    )

    /**
     * 置顶且可达最前；其余可达按“缺值优先”；未启用/旁路节点最后。
     * 置顶状态在水合图关闭时保留，但该节点不会因此提前。
     */
    fun orderNodes(
        nodes: List<WorkflowNodeView>,
        pinnedNodeIds: Set<Int>,
    ): List<WorkflowNodeView> = nodes.sortedWith(
        compareBy<WorkflowNodeView>(
            { !(it.reachable && it.id in pinnedNodeIds) },
            { !it.reachable },
            { !it.needsInput },
        ),
    )
}

/** 选中了哪些节点图，以及由此派生的输出节点/强制正常执行节点。 */
data class WorkflowGraphSelection(
    val graphs: List<WorkflowGraph>,
    val selectedIds: Set<String>,
    val enabledOutputIds: Set<Int>?,
    val forcedNormalNodeIds: Set<Int>,
) {
    val selectedGraphs: List<WorkflowGraph> get() = graphs.filter { it.id in selectedIds }
}
