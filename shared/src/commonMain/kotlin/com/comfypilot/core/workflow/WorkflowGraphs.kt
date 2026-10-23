package com.comfypilot.core.workflow

/**
 * 一个可独立启停的节点图。
 *
 * 以"包含视觉输出（IMAGE/VIDEO/LATENT/AUDIO）的连通分量"为单位：
 * - 两个互不连线的分支（如"图像编辑"/"生图"）各自成一个图；
 * - 同一个分支里的 PreviewImage/Image Comparer 不会额外拆出一个重复的图；
 * - 文本输出（提示词增强器等）不算可执行图。
 *
 * 连接到输出分支、但不在输出分组里的 LoadImage 图组（如 `image-2`）会作为
 * 无输出图组追加进来：选中时只把组内节点强制为正常执行，不额外增加输出节点。
 *
 * 启停时优先只强制该图所在分组内的节点，避免把旁边可选的 LoadImage/Lora 一起打开。
 */
data class WorkflowGraph(
    val id: String,
    val title: String,
    val nodeIds: Set<Int>,
    val outputNodeIds: Set<Int>,
    val enabled: Boolean,
    /** 辅助图组所属的输出图；输出图本身为 null。 */
    val parentId: String? = null,
)

object WorkflowGraphs {

    private val VISUAL_OUTPUT_TYPES = setOf("IMAGE", "VIDEO", "LATENT", "AUDIO")

    fun analyze(
        document: WorkflowDocument,
        definitions: Map<String, NodeDefinition>,
    ): List<WorkflowGraph> {
        val graph = UiWorkflowParser.parse(document.raw) ?: return emptyList()
        val outputs = graph.nodes
            .filter { node ->
                val definition = definitions[node.type]
                definition != null &&
                    definition.isOutputNode &&
                    definition.outputs.any { it in VISUAL_OUTPUT_TYPES }
            }
            .map { it.id }
            .toSet()
        if (outputs.isEmpty()) return emptyList()

        val components = mutableListOf<Set<Int>>()
        val remaining = outputs.toMutableSet()
        while (remaining.isNotEmpty()) {
            val component = componentOf(graph, remaining.first())
            components += component
            remaining -= component
        }

        val componentGraphIds = mutableMapOf<Set<Int>, String>()
        val outputGraphs = components.mapNotNull { component ->
            val componentOutputs = outputs.intersect(component)
            if (componentOutputs.isEmpty()) return@mapNotNull null
            val outputGroups = graph.groups.filter { group ->
                componentOutputs.any { id -> graph.byId[id]?.let(group::contains) == true }
            }
            val groupNodes = outputGroups
                .flatMap { group -> graph.nodes.filter(group::contains).map { it.id } }
                .toSet()
            val largestGroup = outputGroups.maxByOrNull { group -> graph.nodes.count(group::contains) }
            val primaryOutput = componentOutputs.minOrNull()?.let { graph.byId[it] }
            val graphId = "c${component.minOrNull() ?: 0}"
            componentGraphIds[component] = graphId
            WorkflowGraph(
                id = graphId,
                title = largestGroup?.title
                    ?: primaryOutput?.title?.takeIf { it.isNotBlank() }
                    ?: "输出 #${componentOutputs.minOrNull()}",
                nodeIds = groupNodes.ifEmpty { component },
                outputNodeIds = componentOutputs,
                enabled = componentOutputs.any { graph.byId[it]?.mode == UiWorkflowParser.MODE_NORMAL },
            )
        }

        // 一个 LoadImage 图组可能是主输出分组的辅助输入（如 image-2..image-8）。
        // 它本身没有输出节点，但选中后必须能把组内被旁路的节点强制回正常执行。
        val represented = outputGraphs.flatMapTo(mutableSetOf()) { it.nodeIds }
        val auxiliaryGraphs = graph.groups.mapNotNull { group ->
            val nodes = graph.nodes.filter(group::contains).map { it.id }.toSet()
            if (nodes.isEmpty()) return@mapNotNull null
            if (nodes.any { it in represented || it in outputs }) return@mapNotNull null
            if (nodes.none { graph.byId[it]?.type == "LoadImage" }) return@mapNotNull null
            val parentComponent = components.firstOrNull { component ->
                nodes.any { it in component }
            } ?: return@mapNotNull null
            val parentId = componentGraphIds[parentComponent] ?: return@mapNotNull null
            WorkflowGraph(
                id = "g${group.id}",
                title = group.title.ifBlank { "图组 #${group.id}" },
                nodeIds = nodes,
                outputNodeIds = emptySet(),
                enabled = nodes.all { graph.byId[it]?.mode == UiWorkflowParser.MODE_NORMAL },
                parentId = parentId,
            )
        }

        return (outputGraphs + auxiliaryGraphs).sortedWith(
            compareBy(
                { it.outputNodeIds.isEmpty() },
                { it.id.drop(1).toIntOrNull() ?: Int.MAX_VALUE },
            ),
        )
    }

    private fun componentOf(graph: UiGraph, start: Int): Set<Int> {
        val adjacency = mutableMapOf<Int, MutableSet<Int>>()
        fun connect(a: Int, b: Int) {
            if (a !in graph.byId || b !in graph.byId) return
            adjacency.getOrPut(a) { mutableSetOf() } += b
            adjacency.getOrPut(b) { mutableSetOf() } += a
        }
        graph.links.values.forEach { link -> connect(link.originId, link.targetId) }

        val seen = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            adjacency[id].orEmpty().forEach(queue::addLast)
        }
        return seen
    }
}
