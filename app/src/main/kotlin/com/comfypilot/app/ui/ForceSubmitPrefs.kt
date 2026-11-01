package com.comfypilot.app.ui

import android.content.Context

/**
 * 「忽略本地校验，强制提交」开关。
 *
 * 打开后 PromptBuilder 只把本地问题记成警告，仍然生成 prompt，最终由 ComfyUI
 * 判定；适合自认为知道风险、只想先把任务送出去的场景。默认关闭。
 */
object ForceSubmitPrefs {
    private const val FILE = "comfypilot_workflow"
    private const val KEY = "force_submit"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, value)
            .apply()
    }
}
