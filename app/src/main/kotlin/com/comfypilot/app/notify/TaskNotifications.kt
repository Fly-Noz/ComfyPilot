package com.comfypilot.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.comfypilot.app.MainActivity
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState

/**
 * 任务通知：运行中的进度（可更新、无声）+ 完成时的横幅。
 *
 * 为什么和保活通知分开：保活那条是常驻的"连接中"，任务进度是临时信息。
 * 回到应用时只清任务通知，保活通知不受影响——符合"回前台就把任务通知删掉"。
 */
object TaskNotifications {
    const val CHANNEL_PROGRESS = "comfypilot.task.progress"
    const val CHANNEL_DONE = "comfypilot.task.done"
    const val ID_PROGRESS = 42
    const val ID_DONE = 43

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "任务运行进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "后台运行时显示当前任务的采样进度"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                "任务完成",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "任务完成或失败时的横幅提醒"
                setShowBadge(true)
            },
        )
    }

    /** 运行中的那条通知：有步数就画进度条，没有就显示已完成节点数与耗时。 */
    fun showProgress(context: Context, task: PromptTask) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val percent = task.progress?.let { if (it.max > 0) it.percent else null }
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(taskTitle(task))
            .setContentText(progressText(task))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .setStyle(Notification.BigTextStyle().bigText(progressText(task)))
        if (percent != null) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        manager.notify(ID_PROGRESS, builder.build())
    }

    fun cancelProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_PROGRESS)
    }

    /** 完成/失败：横幅 + 声音，点开回到应用。 */
    fun showFinished(context: Context, task: PromptTask) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val failed = task.error != null || task.state is TaskState.Failed
        val title = if (failed) "任务失败" else "任务完成"
        val body = if (failed) {
            task.error ?: (task.state as? TaskState.Failed)?.message ?: "执行失败"
        } else {
            val outputs = task.outputs.map { it.filename }.take(2).joinToString("、")
            if (outputs.isNotEmpty()) outputs else progressText(task)
        }
        val notification = Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .build()
        manager.notify(ID_DONE, notification)
    }

    fun cancelFinished(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_DONE)
    }

    fun cancelTaskNotifications(context: Context) {
        cancelProgress(context)
        cancelFinished(context)
    }

    private fun taskTitle(task: PromptTask): String {
        val name = task.displayName?.takeIf { it.isNotBlank() }
        return name ?: "任务 ${task.id.take(8)}"
    }

    private fun progressText(task: PromptTask): String {
        val percent = task.progress?.let { if (it.max > 0) it.percent else null }
        val elapsed = elapsedText(task)
        return when {
            percent != null -> {
                val step = task.progress?.node?.let { "#$it " } ?: ""
                "${step}${percent}% · $elapsed"
            }

            task.executedNodes.isNotEmpty() -> "已完成 ${task.executedNodes.size} 个节点 · $elapsed"
            else -> "等待首步 · $elapsed"
        }
    }

    private fun elapsedText(task: PromptTask): String {
        val end = if (task.isActive) System.currentTimeMillis() else task.updatedAtMs
        val seconds = ((end - task.createdAtMs) / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes >= 60) {
            "%d:%02d:%02d".format(minutes / 60, minutes % 60, rest)
        } else {
            "%d:%02d".format(minutes, rest)
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 通知可用性：应用打开时据此决定"申请权限"还是"引导去设置"。 */
enum class NotificationIssue {
    /** 一切正常。 */
    None,

    /** Android 13+ 还没授予 POST_NOTIFICATIONS，可以直接弹系统授权框。 */
    PermissionMissing,

    /** 应用级通知被关闭（设置里关掉了通知总开关）。 */
    AppDisabled,

    /** 某个通道被用户单独关掉。 */
    ChannelBlocked,
}

/**
 * 检查通知是否"完整可用"：权限 + 应用总开关 + 两条任务通道。
 *
 * 权限缺失可以程序化申请；总开关/通道属于用户设置，只能引导去系统设置页，
 * 所以这里把两种状态分开返回，界面才能给出正确动作。
 */
fun checkNotificationIssue(context: Context): NotificationIssue {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return NotificationIssue.PermissionMissing
    }
    val manager = context.getSystemService(NotificationManager::class.java)
        ?: return NotificationIssue.AppDisabled
    if (!manager.areNotificationsEnabled()) {
        return NotificationIssue.AppDisabled
    }
    val blocked = listOf(TaskNotifications.CHANNEL_PROGRESS, TaskNotifications.CHANNEL_DONE).any { id ->
        manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
    }
    return if (blocked) NotificationIssue.ChannelBlocked else NotificationIssue.None
}
