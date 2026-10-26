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

    fun loadOverrides(path: String): Map<Int, Map<String, JsonElement>> {
        val raw = prefs.getString(path, null) ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<Int, Map<String, JsonElement>>()
        root.forEach { (nodeKey, nodeValue) ->
            val nodeId = nodeKey.toIntOrNull() ?: return@forEach
            val fields = (nodeValue as? JsonObject) ?: return@forEach
            result[nodeId] = fields.mapValues { it.value }
        }
        return result
    }

    fun saveOverrides(path: String, overrides: Map<Int, Map<String, JsonElement>>) {
        if (overrides.isEmpty()) {
            prefs.edit().remove(path).apply()
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
        prefs.edit().putString(path, json).apply()
    }

    /** 恢复默认：清掉所有工作流的参数草稿。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** 是否存在任何草稿（设置页用来提示）。 */
    fun hasAny(): Boolean = prefs.all.isNotEmpty()
}