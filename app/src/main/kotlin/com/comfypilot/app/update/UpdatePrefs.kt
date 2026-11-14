package com.comfypilot.app.update

import android.content.Context

/**
 * 更新提示偏好：只影响启动时的静默检查，手动检查始终会给出结果。
 */
object UpdatePrefs {
    enum class Mode(val label: String) {
        ALWAYS("每次启动都提示"),
        ONCE_PER_VERSION("每个版本只提示一次"),
    }

    private const val FILE = "comfypilot_update"
    private const val KEY_MODE = "prompt_mode"
    private const val KEY_DISMISSED = "dismissed_version"

    fun mode(context: Context): Mode {
        val value = prefs(context).getString(KEY_MODE, null)
        return if (value == Mode.ONCE_PER_VERSION.name) Mode.ONCE_PER_VERSION else Mode.ALWAYS
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** 用户在「稍后 / 关闭」时记下的版本号；0 表示没有。 */
    fun dismissedVersion(context: Context): Int =
        prefs(context).getInt(KEY_DISMISSED, 0)

    fun markDismissed(context: Context, versionCode: Int) {
        if (versionCode > 0) {
            prefs(context).edit().putInt(KEY_DISMISSED, versionCode).apply()
        }
    }

    /** 备份恢复：清掉旧版本的“稍后”记录。 */
    fun clearDismissed(context: Context) {
        prefs(context).edit().remove(KEY_DISMISSED).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
