package com.comfypilot.app.ui

import android.content.Context

/**
 * 节点隐藏是纯本机 UI 偏好，不进入工作流或任务协议。
 *
 * 隐藏按工作流路径隔离；被隐藏的节点无论是否启用、是否可达，
 * 都只显示在输入列表最下面的「已隐藏」折叠区里。
 */
class HiddenNodeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(host: String, workflowPath: String): Set<Int> {
        val scoped = prefs.getString(key(host, workflowPath), null)
            ?: prefs.getString(legacyKey(workflowPath), null)
            ?: return emptySet()
        return decodePinnedNodeIds(scoped)
    }

    fun save(host: String, workflowPath: String, nodeIds: Set<Int>) {
        val editor = prefs.edit()
        val key = key(host, workflowPath)
        editor.remove(legacyKey(workflowPath))
        if (nodeIds.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encodePinnedNodeIds(nodeIds))
        }
        editor.apply()
    }

    private fun key(host: String, workflowPath: String): String =
        if (host.isBlank()) legacyKey(workflowPath) else "$host|workflow:$workflowPath"

    private fun legacyKey(workflowPath: String): String = "workflow:$workflowPath"

    private companion object {
        const val PREFS_NAME = "hidden_nodes"
    }
}
