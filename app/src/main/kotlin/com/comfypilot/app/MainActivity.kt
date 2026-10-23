package com.comfypilot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comfypilot.android.service.ComfyForegroundService
import com.comfypilot.app.ui.ComfyPilotApp

class MainActivity : ComponentActivity() {
    private val viewModel: DebugViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响连接，只是看不到保活通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏铺满，状态栏/导航栏由 ComfyPilotTheme 按深浅色切换图标明暗。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermissionIfNeeded()

        // 仅供调试/联调：允许通过 adb 指定地址并自动连接，避免手点 UI 引入不确定性
        val initialUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: SessionHost.DEFAULT_BASE_URL
        val silenceMs = intent.getLongExtra(EXTRA_SILENCE_MS, 0L)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            startKeepAliveService()
            viewModel.connect(initialUrl, silenceMs)
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val notice by viewModel.notice.collectAsStateWithLifecycle()
            ComfyPilotApp(
                state = state,
                initialUrl = initialUrl,
                notice = notice,
                onConnect = { url ->
                    startKeepAliveService()
                    viewModel.connect(url, silenceMs)
                },
                onDisconnect = {
                    viewModel.disconnect()
                    stopService(Intent(this, ComfyForegroundService::class.java))
                },
                onReconnect = viewModel::reconnect,
                onSubmit = { workflowJson, name, path ->
                    viewModel.submit(workflowJson, name, path)
                },
                onSubmitSample = { viewModel.submit() },
                onCancel = viewModel::cancel,
                onRefreshWorkflows = viewModel::refreshWorkflows,
                onOpenWorkflow = viewModel::openWorkflow,
            )
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, ComfyForegroundService::class.java)
            .putExtra(ComfyForegroundService.EXTRA_TEXT, "保持与 ComfyUI 的连接")
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_AUTO_CONNECT = "autoConnect"
        const val EXTRA_SILENCE_MS = "silenceMs"
    }
}
