package com.comfypilot.app.ai

import com.comfypilot.core.ai.AiApplyResult
import com.comfypilot.core.ai.AiEditState
import com.comfypilot.core.ai.AiWorkflowView
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.WorkflowCatalog

/**
 * 没打开工作流时的宿主：只提供「切工作流」这一件事。
 *
 * 其余能力（摘要、改节点、提交）都如实返回不可用，让模型先 `open_workflow`。
 * 这样没进工作流也能和 AI 对话、让它帮忙切流，而不是直接被拒绝。
 */
class AiNavigationHost(
    private val snapshot: () -> SessionState,
    private val openDetail: (String) -> Unit,
    private val open: (String) -> Unit,
) : AiWorkflowHost {

    override fun digest(): AiWorkflowView? = null

    override fun editState(): AiEditState = AiEditState()

    override fun applyEdit(state: AiEditState): AiApplyResult =
        AiApplyResult(state, emptyList(), listOf("还没有打开工作流：先调用 open_workflow 打开一个"))

    override fun submitCurrent(): Boolean = false

    override fun openWorkflow(path: String): Boolean {
        if (path.isBlank()) return false
        open(path)
        openDetail(path)
        return true
    }

    override fun availableWorkflows(): List<String> {
        val catalog = snapshot().catalog
        return if (catalog is WorkflowCatalog.Ready) catalog.entries.map { it.path } else emptyList()
    }

    override fun taskSummary(waitSeconds: Int): String = "[]"
}
