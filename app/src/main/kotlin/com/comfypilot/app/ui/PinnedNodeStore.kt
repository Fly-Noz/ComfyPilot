package com.comfypilot.app.ui

import android.content.Context
import com.comfypilot.core.workflow.WorkflowNodeView

/**
 * 节点置顶是本机 UI 偏好，不进入工作流或任务协议。
 *
 * 置顶按工作流路径隔离；节点图未启用时仍保留置顶，但输入区不会提前它。
 */
class PinnedNodeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(workflowPath: String): Set<Int> =
        decodePinnedNodeIds(prefs.getString(key(workflowPath), null))

    fun save(workflowPath: String, nodeIds: Set<Int>) {
        val editor = prefs.edit()
        val key = key(workflowPath)
        if (nodeIds.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encodePinnedNodeIds(nodeIds))
        }
        editor.apply()
    }

    private fun key(workflowPath: String): String = "workflow:$workflowPath"

    private companion object {
        const val PREFS_NAME = "pinned_nodes"
    }
}

internal fun encodePinnedNodeIds(nodeIds: Set<Int>): String = nodeIds.sorted().joinToString(",")

internal fun decodePinnedNodeIds(raw: String?): Set<Int> =
    raw.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()

/**
 * 输入区排序：置顶且可达的节点最前；其余可达节点按“缺值优先”；
 * 未启用/旁路节点放最后。置顶的节点图未启用时不会被提前。
 */
internal fun orderInputNodes(
    nodes: List<WorkflowNodeView>,
    pinnedNodeIds: Set<Int>,
): List<WorkflowNodeView> =
    nodes.sortedWith(
        compareBy<WorkflowNodeView>(
            { !(it.reachable && it.id in pinnedNodeIds) },
            { !it.reachable },
            { !it.needsInput },
        ),
    )
