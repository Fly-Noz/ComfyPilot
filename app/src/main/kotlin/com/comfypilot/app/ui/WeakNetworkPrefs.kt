package com.comfypilot.app.ui

import android.content.Context

/**
 * 弱网模式：降低轮询频率、关闭模型摘要，优先省流量和等待时间。
 * 默认关闭；MVP 只覆盖状态轮询与 AI 上下文压缩，预览/下载降级后续再加。
 */
object WeakNetworkPrefs {
    private const val FILE = "comfypilot_workflow"
    private const val KEY = "weak_network"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }
}
