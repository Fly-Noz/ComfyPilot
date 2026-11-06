package com.comfypilot.app.ui

import android.content.Context
import com.comfypilot.core.workflow.WorkflowField
import com.comfypilot.core.workflow.WorkflowNodeView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * 种子节点的本机行为：固定 / 每次随机，并记住每个节点最近一次提交的种子。
 *
 * 不改服务器上的工作流文件；模式与“上次种子”只存在本机。
 */
object SeedModeStore {
    private const val FILE = "comfypilot_seed"
    private const val KEY_EVERY = "every"
    private const val KEY_LAST = "last"

    /** 当前工作流路径；进入详情页时设置，用于给模式和“上次种子”做键。 */
    @Volatile
    var workflowPath: String = ""

    private fun key(nodeId: Int): String = "$workflowPath|$nodeId"

    fun isEveryRandom(context: Context, nodeId: Int): Boolean {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_EVERY, null)
        return readLongMap(raw)[key(nodeId)] == 1L
    }

    fun setEveryRandom(context: Context, nodeId: Int, value: Boolean) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val map = readLongMap(prefs.getString(KEY_EVERY, null))
        val next = map + (key(nodeId) to if (value) 1L else 0L)
        prefs.edit().putString(KEY_EVERY, JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString()).apply()
    }

    fun lastSeed(context: Context, nodeId: Int): Long? {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST, null)
        return readLongMap(raw)[key(nodeId)]
    }

    private fun rememberSeed(context: Context, nodeId: Int, seed: Long) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val map = readLongMap(prefs.getString(KEY_LAST, null))
        prefs.edit()
            .putString(KEY_LAST, JsonObject((map + (key(nodeId) to seed)).mapValues { JsonPrimitive(it.value) }).toString())
            .apply()
    }

    private fun readLongMap(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            raw.let { text ->
                kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }
                    .toMap()
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * 提交前：对“每次随机”的种子字段生成新值，并记录所有可编辑种子的当前值，
     * 供下一次「使用上次」使用。
     */
    fun prepareForSubmit(
        context: Context,
        overrides: Map<Int, Map<String, JsonElement>>,
        nodes: List<WorkflowNodeView>,
    ): Map<Int, Map<String, JsonElement>> {
        val next = overrides.mapValuesTo(mutableMapOf()) { (_, v) -> v.toMutableMap() }
        for (node in nodes) {
            for (field in node.fields) {
                if (!isSeedField(field) || field.linked) continue
                val current = next[node.id]?.get(field.inputName) ?: field.value
                if (isEveryRandom(context, node.id)) {
                    val seed = Random.nextLong(1, 2_147_483_647L)
                    next.getOrPut(node.id) { mutableMapOf() }[field.inputName] = JsonPrimitive(seed)
                    rememberSeed(context, node.id, seed)
                } else {
                    val value = (current as? JsonPrimitive)?.longOrNull ?: continue
                    rememberSeed(context, node.id, value)
                }
            }
        }
        return next
    }

    fun isSeedField(field: WorkflowField): Boolean =
        field.widget && !field.linked && field.type == "INT" &&
            field.inputName.contains("seed", ignoreCase = true)
}
