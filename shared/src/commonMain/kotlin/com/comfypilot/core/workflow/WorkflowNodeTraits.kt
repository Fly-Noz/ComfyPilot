package com.comfypilot.core.workflow

/**
 * 「全部节点」筛选用的粗粒度类型。
 *
 * 只用节点定义里已有的输入类型分类，不额外请求主机；一个节点可以同时命中多个类型。
 */
enum class NodeTrait {
    /** 有 IMAGE 输入，或本身就是 LoadImage。 */
    Image,

    /** 有 STRING 输入（提示词、多行文本等）。 */
    Text,

    /** 有 INT / FLOAT 输入（步数、分辨率、强度等）。 */
    Number,

    /** 还缺必填值，提交前需要补。 */
    NeedsInput,
}

object WorkflowNodeTraits {

    fun of(node: WorkflowNodeView): Set<NodeTrait> {
        val traits = mutableSetOf<NodeTrait>()
        if (node.needsInput) traits += NodeTrait.NeedsInput
        if (node.type == "LoadImage") traits += NodeTrait.Image
        node.fields.forEach { field ->
            when (field.type) {
                "IMAGE" -> traits += NodeTrait.Image
                "STRING" -> traits += NodeTrait.Text
                "INT", "FLOAT" -> traits += NodeTrait.Number
            }
        }
        return traits
    }

    /** 搜索匹配：备注名、节点名、类型、#id；空关键词匹配全部。 */
    fun matches(node: WorkflowNodeView, query: String, note: String? = null): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val haystacks = buildList {
            add(node.displayName)
            add(node.type)
            add("#${node.id}")
            if (!note.isNullOrBlank()) add(note)
            // 也能按输入类型搜，例如 string / image。
            node.fields.forEach { field -> add(field.type) }
        }
        return haystacks.any { it.contains(trimmed, ignoreCase = true) }
    }
}