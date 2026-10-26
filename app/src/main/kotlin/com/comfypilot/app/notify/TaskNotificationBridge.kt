package com.comfypilot.app.notify

import android.content.Context
import com.comfypilot.app.SessionHost
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 把会话状态接到通知上：
 * - 后台且有活动任务：更新"运行中"通知（进度/节点数/耗时）；
 * - 后台时任务进入终态：先撤掉运行通知，再发完成/失败横幅；
 * - 回到前台：撤掉所有任务通知（保活通知不动）。
 *
 * 放在 Application 作用域，Activity 销毁不影响；点击通知能重新拉起界面。
 */
object TaskNotificationBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var started = false

    @Volatile
    private var foreground: Boolean = true

    /**
     * 本次进程里是否真的进过前台。
     *
     * 保活服务是 START_STICKY：进程被回收后系统会把它拉起来，Application 重新连接、
     * 恢复上次的运行中任务；任务结束时如果只看 foreground=false 就发通知，用户会
     * 觉得"明明没开 App 却收到完成通知"。这里要求至少进过一次前台才允许发。
     */
    @Volatile
    private var hasBeenForeground: Boolean = false

    private val finishedSeen = mutableSetOf<String>()
    private var lastProgressText: String = ""
    private var lastUpdateAtMs: Long = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        TaskNotifications.ensureChannels(context.applicationContext)
        scope.launch {
            SessionHost.state.collect { snapshot -> onState(snapshot) }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        val context = appContext ?: return
        if (value) {
            hasBeenForeground = true
            // 回到应用：任务通知（运行中与已完成）都清掉，避免占着通知栏
            TaskNotifications.cancelTaskNotifications(context)
            lastProgressText = ""
        }
    }

    private fun onState(state: SessionState) {
        val context = appContext ?: return
        val tasks = state.tasks.values

        // 首次观察到的历史终态不补发横幅，否则每次冷启动都会被旧任务刷屏
        if (finishedSeen.isEmpty()) {
            tasks.filter { it.isTerminal }.forEach { finishedSeen.add(it.id) }
        }

        val active = tasks.filter { it.isActive }.maxByOrNull { it.updatedAtMs }
        if (!foreground && hasBeenForeground) {
            if (active != null) {
                val text = progressSignature(active)
                val now = System.currentTimeMillis()
                if (text != lastProgressText && (now - lastUpdateAtMs >= 900 || lastProgressText.isEmpty())) {
                    lastProgressText = text
                    lastUpdateAtMs = now
                    TaskNotifications.showProgress(context, active)
                }
            } else {
                TaskNotifications.cancelProgress(context)
            }
        }

        // 新的终态：后台时发横幅；前台交给界面，不打扰
        tasks.filter { it.isTerminal }.forEach { task ->
            if (finishedSeen.add(task.id)) {
                TaskNotifications.cancelProgress(context)
                if (!foreground && hasBeenForeground) {
                    TaskNotifications.showFinished(context, task)
                }
            }
        }
    }

    private fun progressSignature(task: PromptTask): String {
        val percent = task.progress?.let { if (it.max > 0) it.percent else null }
        return "${task.id}:${percent ?: -1}:${task.executedNodes.size}:${task.state::class.simpleName}"
    }

    /** 供调试/退出时清理；正常路径不会用到。 */
    fun stop(context: Context) {
        TaskNotifications.cancelTaskNotifications(context)
        finishedSeen.clear()
        lastProgressText = ""
    }

    @Suppress("unused")
    private fun isTerminal(state: TaskState): Boolean =
        state is TaskState.Completed || state is TaskState.Failed ||
            state is TaskState.Cancelled || state is TaskState.Unknown
}
