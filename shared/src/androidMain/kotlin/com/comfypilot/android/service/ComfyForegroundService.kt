package com.comfypilot.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.comfypilot.core.transport.TimeSource

/**
 * 保活只是"让进程更不容易被杀"，不是"永久在线"。
 * UI 文案必须保持一致：后台可能暂停监控，回来自动同步。
 */
class ComfyForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ComfyPilot · 后台保活")
            .setContentText(text)
            // 展开后把"为什么不能删"讲清楚：这条是前台服务的常驻通知，
            // 删掉不一定会立刻断线，但系统回收进程的概率会明显变高。
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "连接保活",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持与 ComfyUI 的连接；后台可能被系统暂停"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "comfypilot.connection"
        const val NOTIFICATION_ID = 41
        const val EXTRA_TEXT = "text"

        /** 默认文案：明确告诉用户"运行期间别删"，否则系统更容易中断采样与进度。 */
        const val DEFAULT_TEXT: String =
            "任务运行期间请勿删除此通知：它让系统知道 ComfyPilot 正在后台工作，" +
                "删除后更容易被回收，采样画面与进度可能随之中断。"
    }
}

object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
