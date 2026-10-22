package com.comfypilot.app.ui

import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowNodeView
import org.junit.Assert.assertEquals
import org.junit.Test

class InputOrderTest {

    @Test
    fun `pinned reachable nodes stay first and pinned disabled nodes stay last`() {
        val nodes = listOf(
            node(id = 1, reachable = false),
            node(id = 2, reachable = true, needsInput = true),
            node(id = 3, reachable = true),
            node(id = 4, reachable = true, needsInput = true),
            node(id = 5, reachable = true),
            node(id = 6, reachable = false, needsInput = true),
        )

        val ordered = orderInputNodes(nodes, pinnedNodeIds = setOf(1, 3, 4, 6))
            .map { it.id }

        // 4/3 置顶且可达；2/5 为其余可达节点；1/6 所在图未启用，即使置顶也留在最后。
        assertEquals(listOf(4, 3, 2, 5, 1, 6), ordered)
    }

    private fun node(
        id: Int,
        reachable: Boolean,
        needsInput: Boolean = false,
    ): WorkflowNodeView = WorkflowNodeView(
        id = id,
        type = "X",
        displayName = "X",
        title = null,
        mode = 0,
        missing = false,
        reachable = reachable,
        fields = if (needsInput) {
            listOf(
                WorkflowField(
                    nodeId = id,
                    nodeType = "X",
                    nodeTitle = null,
                    inputName = "value",
                    label = null,
                    type = "INT",
                    options = emptyList(),
                    value = null,
                    linked = false,
                    widget = true,
                    required = true,
                    common = false,
                ),
            )
        } else {
            emptyList()
        },
    )
}
