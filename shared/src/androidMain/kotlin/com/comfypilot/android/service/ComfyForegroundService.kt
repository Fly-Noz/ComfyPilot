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
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "保持与 ComfyUI 的连接"
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
            .setContentTitle("ComfyPilot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
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
    }
}

object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
