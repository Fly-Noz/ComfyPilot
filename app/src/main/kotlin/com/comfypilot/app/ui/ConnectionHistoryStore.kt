package com.comfypilot.app.ui

import android.content.Context

/**
 * 地址输入记忆：只保存本机填过的 ComfyUI 地址。
 *
 * 最近一次成功/尝试连接的地址作为默认值；历史列表按最近使用排序，去重后最多 6 条。
 * 不写回服务器，也不进入会话协议。
 */
class ConnectionHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次填写的地址；没有历史时返回空串。 */
    fun last(): String = prefs.getString(KEY_LAST, "").orEmpty()

    /** 历史地址，最近使用的在前。 */
    fun history(): List<String> = prefs.getString(KEY_HISTORY, "").orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** 只更新默认值，不进入历史（用于边输入边记住）。 */
    fun saveLast(url: String) {
        val value = url.trim()
        if (value.isEmpty()) return
        prefs.edit().putString(KEY_LAST, value).apply()
    }

    /** 真正连接/选择过才进历史。 */
    fun record(url: String) {
        val value = url.trim()
        if (value.isEmpty()) return
        val next = (listOf(value) + history().filter { it != value }).take(MAX_HISTORY)
        prefs.edit()
            .putString(KEY_LAST, value)
            .putString(KEY_HISTORY, next.joinToString("\n"))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "connection_history"
        const val KEY_LAST = "last"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 6
    }
}