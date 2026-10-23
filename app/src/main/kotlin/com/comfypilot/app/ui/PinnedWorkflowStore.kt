package com.comfypilot.app.ui

import android.content.Context
import com.comfypilot.core.workflow.WorkflowEntry

/**
 * 工作流置顶是纯本机 UI 偏好，不写回服务器，也不进入任务/工作流协议。
 *
 * 只保存工作流路径；列表排序时置顶的排前面，其余仍按路径排序。
 */
class PinnedWorkflowStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet().orEmpty()

    fun save(paths: Set<String>) {
        val editor = prefs.edit()
        if (paths.isEmpty()) {
            editor.remove(KEY)
        } else {
            editor.putStringSet(KEY, paths)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "pinned_workflows"
        const val KEY = "paths"
    }
}

/** 置顶工作流在前；同组内保持服务器目录的路径顺序。 */
internal fun orderWorkflowEntries(
    entries: List<WorkflowEntry>,
    pinnedPaths: Set<String>,
): List<WorkflowEntry> = entries.sortedWith(
    compareBy(
        { it.path !in pinnedPaths },
        { it.path.lowercase() },
    ),
)
