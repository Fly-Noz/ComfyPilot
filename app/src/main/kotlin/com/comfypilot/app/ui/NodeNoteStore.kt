package com.comfypilot.app.ui

import android.content.Context

/**
 * 节点备注是本机 UI 偏好，不进入工作流或任务协议。
 *
 * 备注按工作流路径隔离；空备注等于删除。
 */
class NodeNoteStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(host: String, workflowPath: String): Map<Int, String> {
        val scoped = prefs.getString(key(host, workflowPath), null)
        if (scoped != null) {
            return decodeNodeNotes(scoped)
        }
        val legacy = prefs.getString(legacyKey(workflowPath), null) ?: return emptyMap()
        prefs.edit().putString(key(host, workflowPath), legacy).remove(legacyKey(workflowPath)).apply()
        return decodeNodeNotes(legacy)
    }

    fun save(host: String, workflowPath: String, notes: Map<Int, String>) {
        val editor = prefs.edit()
        val key = key(host, workflowPath)
        editor.remove(legacyKey(workflowPath))
        val cleaned = notes.filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encodeNodeNotes(cleaned))
        }
        editor.apply()
    }

    private fun key(host: String, workflowPath: String): String =
        if (host.isBlank()) legacyKey(workflowPath) else "$host|workflow:$workflowPath"

    private fun legacyKey(workflowPath: String): String = "workflow:$workflowPath"

    private companion object {
        const val PREFS_NAME = "node_notes"
    }
}

internal fun encodeNodeNotes(notes: Map<Int, String>): String =
    notes.toSortedMap()
        .map { (id, label) -> "$id\t${label.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')}" }
        .joinToString("\n")

internal fun decodeNodeNotes(raw: String?): Map<Int, String> =
    raw.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val cut = line.indexOf('\t')
            if (cut <= 0) return@mapNotNull null
            val id = line.substring(0, cut).trim().toIntOrNull() ?: return@mapNotNull null
            val label = line.substring(cut + 1).trim()
            if (label.isEmpty()) null else id to label
        }
        .toMap()