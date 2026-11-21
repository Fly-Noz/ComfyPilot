package com.comfypilot.core.workflow

/** 一次进程内的工作流原文缓存；重启应用后不会保留。 */
data class CachedWorkflow(
    val name: String,
    val raw: String,
)

/** 后台拉到的、和当前缓存有明显差异的新版本。 */
data class WorkflowUpdate(
    val path: String,
    val name: String,
    val raw: String,
    val reasons: List<String>,
)

data class WorkflowDiff(
    val significant: Boolean,
    val reasons: List<String> = emptyList(),
)

/**
 * 比较两份工作流原文。
 *
 * 只看会影响执行/图结构的内容：
 * - 节点 id、类型、模式；
 * - 连线拓扑（忽略 link id）；
 * - 画布分组 id / 标题 / 成员；
 * 忽略节点坐标、尺寸、颜色、标题、备注、控件默认值，避免拖动节点就弹窗。
 */
object WorkflowDiffer {

    fun compare(oldRaw: String, newRaw: String): WorkflowDiff {
        if (oldRaw == newRaw) return WorkflowDiff(significant = false)
        val oldGraph = UiWorkflowParser.parse(oldRaw)
        val newGraph = UiWorkflowParser.parse(newRaw)
        if (oldGraph == null || newGraph == null || oldGraph.hasSubgraphs || newGraph.hasSubgraphs) {
            return WorkflowDiff(true, listOf("工作流结构无法比较，按整体变化处理"))
        }

        val reasons = mutableListOf<String>()
        val oldNodes = oldGraph.nodes.associateBy { it.id }
        val newNodes = newGraph.nodes.associateBy { it.id }
        if (oldNodes.keys != newNodes.keys) reasons += "节点增删"
        if (oldNodes.any { (id, node) -> newNodes[id]?.type != node.type }) reasons += "节点类型变化"
        if (oldNodes.any { (id, node) -> newNodes[id]?.mode != node.mode }) reasons += "节点启用/旁路状态变化"

        fun linkSignature(graph: UiGraph): Set<String> = graph.links.values.map { link ->
            "${link.originId}:${link.originSlot}->${link.targetId}:${link.targetSlot}:${link.type}"
        }.toSet()
        if (linkSignature(oldGraph) != linkSignature(newGraph)) reasons += "连线变化"

        fun groupSignature(graph: UiGraph): Set<String> = graph.groups.map { group ->
            "${group.id}|${group.title}|${group.members.sorted().joinToString(",")}"
        }.toSet()
        if (groupSignature(oldGraph) != groupSignature(newGraph)) reasons += "画布分组变化"

        return WorkflowDiff(significant = reasons.isNotEmpty(), reasons = reasons.distinct())
    }
}
