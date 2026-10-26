package com.comfypilot.app.ui

import android.content.Context

/**
 * 节点置顶是本机 UI 偏好，不进入工作流或任务协议。
 *
 * 置顶按工作流路径隔离；节点图未启用时仍保留置顶，但输入区不会提前它。
 */
class PinnedNodeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(host: String, workflowPath: String): Set<Int> {
        val scoped = prefs.getString(key(host, workflowPath), null)
        if (scoped != null) {
            return decodePinnedNodeIds(scoped)
        }
        // 旧版本只按路径存：第一次在某个主机上打开时，把它迁到这个主机。
        val legacy = prefs.getString(legacyKey(workflowPath), null) ?: return emptySet()
        prefs.edit().putString(key(host, workflowPath), legacy).remove(legacyKey(workflowPath)).apply()
        return decodePinnedNodeIds(legacy)
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
        const val PREFS_NAME = "pinned_nodes"
    }
}

internal fun encodePinnedNodeIds(nodeIds: Set<Int>): String = nodeIds.sorted().joinToString(",")

internal fun decodePinnedNodeIds(raw: String?): Set<Int> =
    raw.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()
