package com.comfypilot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comfypilot.android.service.ComfyForegroundService
import com.comfypilot.app.notify.NotificationIssue
import com.comfypilot.app.notify.checkNotificationIssue
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.ComfyPilotApp

class MainActivity : ComponentActivity() {
    private val viewModel: DebugViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果在回到前台时统一检查，拒绝也不影响连接 */ }

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

            // 每次回到前台都重新检查：用户可能刚在系统设置里关掉了通知
            var issue by remember { mutableStateOf(checkNotificationIssue(this)) }
            var dismissed by remember { mutableStateOf(false) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        issue = checkNotificationIssue(this@MainActivity)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                issue = checkNotificationIssue(this@MainActivity)
            }

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
                onSetNodeBypassed = viewModel::setNodeBypass,
            )

            // 权限缺失时系统弹窗已经弹过；这里只处理"权限有、但总开关/通道被关"的情况，
            // 这两项无法程序化申请，只能引导去系统设置。
            if (!dismissed && (issue == NotificationIssue.AppDisabled || issue == NotificationIssue.ChannelBlocked)) {
                SpectrumDialog(
                    title = "任务通知不可用",
                    onDismiss = { dismissed = true },
                    confirmText = "去开启",
                    onConfirm = {
                        dismissed = true
                        openNotificationSettings()
                    },
                    dismissText = "以后再说",
                    body = {
                        Text(
                            text = if (issue == NotificationIssue.AppDisabled) {
                                "系统里 ComfyPilot 的通知被关闭了。开启后才能看到任务进度和完成提醒；后台保活也会更容易被系统回收。"
                            } else {
                                "任务通知的两个通道（运行进度 / 任务完成）被单独关闭了。开启后才能在后台看到进度与完成提醒。"
                            },
                            color = Spectrum.colors.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    },
                )
            }
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun startKeepAliveService() {
        // 文案交给服务里的 DEFAULT_TEXT：那里会提醒"运行期间不要删除此通知"
        val intent = Intent(this, ComfyForegroundService::class.java)
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
