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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comfypilot.android.service.ComfyForegroundService
import com.comfypilot.app.ui.DebugScreen

class MainActivity : ComponentActivity() {
    private val viewModel: DebugViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响连接，只是看不到保活通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val notice by viewModel.notice.collectAsStateWithLifecycle()
            DebugScreen(
                state = state,
                notice = notice,
                onConnect = { url ->
                    startKeepAliveService()
                    viewModel.connect(url)
                },
                onDisconnect = {
                    viewModel.disconnect()
                    stopService(Intent(this, ComfyForegroundService::class.java))
                },
                onReconnect = viewModel::reconnect,
                onSubmit = viewModel::submit,
                onCancel = viewModel::cancel,
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
}
