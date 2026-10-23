package com.comfypilot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionState

private sealed interface Screen {
    data object Connect : Screen
    data object Catalog : Screen
    data object Detail : Screen
    data object Tasks : Screen
    data object Diagnostics : Screen
}

/**
 * 产品流程：连接 → 选择服务器上的工作流 → 调参/查看节点 → 提交 → 看任务。
 * 诊断页保留原来的连接层入口，不参与产品导航。
 */
@Composable
fun ComfyPilotApp(
    state: SessionState,
    initialUrl: String,
    notice: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onSubmit: (String, String?, String?) -> Unit,
    onSubmitSample: () -> Unit,
    onCancel: (String) -> Unit,
    onRefreshWorkflows: () -> Unit,
    onOpenWorkflow: (String) -> Unit,
) {
    MaterialTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
        // 从详情打开任务时，详情必须继续留在组合里；否则字段/图开关/页签会全部丢失。
        var tasksOverlay by remember { mutableStateOf(false) }

        // 连接成功后自动进入工作流列表；只在"连接页"时自动跳，避免把正在看详情的人拽走。
        LaunchedEffect(state.phase, state.lease?.leaseId) {
            if (state.phase is SessionPhase.Connected && screen == Screen.Connect) {
                onRefreshWorkflows()
                screen = Screen.Catalog
            }
        }

        BackHandler(enabled = tasksOverlay || screen != Screen.Connect) {
            if (tasksOverlay) {
                tasksOverlay = false
            } else {
                screen = when (screen) {
                    Screen.Connect -> Screen.Connect
                    Screen.Catalog -> Screen.Connect
                    Screen.Detail -> Screen.Catalog
                    Screen.Tasks -> Screen.Catalog
                    Screen.Diagnostics -> if (state.lease != null) Screen.Catalog else Screen.Connect
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Connect -> ConnectScreen(
                    state = state,
                    initialUrl = initialUrl,
                    notice = notice,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onBrowse = {
                        onRefreshWorkflows()
                        screen = Screen.Catalog
                    },
                    onDiagnostics = { screen = Screen.Diagnostics },
                )

                Screen.Catalog -> WorkflowCatalogScreen(
                    state = state,
                    onRefresh = onRefreshWorkflows,
                    onOpen = { path ->
                        onOpenWorkflow(path)
                        screen = Screen.Detail
                    },
                    onTasks = { screen = Screen.Tasks },
                    onBack = { screen = Screen.Connect },
                    onDisconnect = onDisconnect,
                )

                Screen.Detail -> WorkflowDetailScreen(
                    state = state,
                    onBack = { screen = Screen.Catalog },
                    onTasks = { tasksOverlay = true },
                    onSubmit = { workflowJson, name, path -> onSubmit(workflowJson, name, path) },
                    onCancel = onCancel,
                )

                Screen.Tasks -> TaskScreen(
                    state = state,
                    notice = notice,
                    onBack = { screen = Screen.Catalog },
                    onCancel = onCancel,
                    onDiagnostics = { screen = Screen.Diagnostics },
                    onDisconnect = {
                        onDisconnect()
                        screen = Screen.Connect
                    },
                )

                Screen.Diagnostics -> DebugScreen(
                    state = state,
                    initialUrl = initialUrl,
                    notice = notice,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onReconnect = onReconnect,
                    onSubmit = onSubmitSample,
                    onCancel = onCancel,
                    onBack = {
                        screen = if (state.lease != null) Screen.Catalog else Screen.Connect
                    },
                )
            }

            // 覆盖层：详情仍留在组合中，任务页关闭后字段、图开关和页签都不会丢。
            if (tasksOverlay) {
                TaskScreen(
                    state = state,
                    notice = notice,
                    onBack = { tasksOverlay = false },
                    onCancel = onCancel,
                    onDiagnostics = {
                        tasksOverlay = false
                        screen = Screen.Diagnostics
                    },
                    onDisconnect = {
                        onDisconnect()
                        tasksOverlay = false
                        screen = Screen.Connect
                    },
                )
            }
        }
    }
}
