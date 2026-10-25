package com.comfypilot.app

import android.app.Application
import android.os.Bundle
import com.comfypilot.app.notify.TaskNotificationBridge
import com.comfypilot.app.notify.TaskNotifications

/**
 * 进程级入口：只做两件事——把任务通知桥接挂起来、判断应用是否在前台。
 *
 * 前台判断用 Activity 计数而不是 ProcessLifecycleOwner：不引入额外依赖，
 * 而且多 Activity（目前只有 MainActivity）也不会误判。
 */
class ComfyPilotApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var startedActivities: Int = 0

    override fun onCreate() {
        super.onCreate()
        com.comfypilot.app.DiagnosticsLog.attach(this)
        TaskNotifications.ensureChannels(this)
        registerActivityLifecycleCallbacks(this)
        TaskNotificationBridge.start(this)
    }

    override fun onActivityStarted(activity: android.app.Activity) {
        startedActivities++
        if (startedActivities == 1) {
            TaskNotificationBridge.setForeground(true)
        }
    }

    override fun onActivityStopped(activity: android.app.Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            TaskNotificationBridge.setForeground(false)
        }
    }

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}
