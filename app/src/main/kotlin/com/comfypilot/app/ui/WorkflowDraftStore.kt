package com.comfypilot.app.ui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作流参数草稿：用户在本机改过的参数值，按工作流路径存在本机。
 *
 * 只存"参数覆盖值"（节点 id -> 输入名 -> 值），不写回主机、不改工作流文件；
 * 提交/刷新时仍按原来的 overrides 机制送给共享层。
 */
class WorkflowDraftStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("workflow_drafts", Context.MODE_PRIVATE)

    fun loadOverrides(host: String, path: String): Map<Int, Map<String, JsonElement>> {
        val scopedKey = key(host, path)
        val raw = prefs.getString(scopedKey, null) ?: run {
            // 旧版本只按路径存：第一次在某个主机打开时迁移过来。
            val legacy = prefs.getString(path, null) ?: return emptyMap()
            prefs.edit().putString(scopedKey, legacy).remove(path).apply()
            legacy
        }
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<Int, Map<String, JsonElement>>()
        root.forEach { (nodeKey, nodeValue) ->
            val nodeId = nodeKey.toIntOrNull() ?: return@forEach
            val fields = (nodeValue as? JsonObject) ?: return@forEach
            result[nodeId] = fields.mapValues { it.value }
        }
        return result
    }

    fun saveOverrides(host: String, path: String, overrides: Map<Int, Map<String, JsonElement>>) {
        val scopedKey = key(host, path)
        val editor = prefs.edit().remove(path)
        if (overrides.isEmpty()) {
            editor.remove(scopedKey).apply()
            return
        }
        val json = buildJsonObject {
            overrides.forEach { (nodeId, fields) ->
                put(
                    nodeId.toString(),
                    buildJsonObject {
                        fields.forEach { (name, value) -> put(name, value) }
                    },
                )
            }
        }.toString()
        editor.putString(scopedKey, json).apply()
    }

    private fun key(host: String, path: String): String =
        if (host.isBlank()) path else "$host|$path"

    /** 恢复默认：清掉所有工作流的参数草稿。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** 是否存在任何草稿（设置页用来提示）。 */
    fun hasAny(): Boolean = prefs.all.isNotEmpty()
}