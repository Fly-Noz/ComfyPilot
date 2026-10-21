package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.comfypilot.core.net.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    state: SessionState,
    notice: String?,
    onBack: () -> Unit,
    onCancel: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = onDiagnostics) { Text("诊断") }
                    TextButton(onClick = onDisconnect) { Text("断开") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${state.connectionLabel} · ${state.network.transport.name} · 第 ${state.generation} 代",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            state.queue?.let { queue ->
                Text(
                    "队列：运行 ${queue.running.size} / 等待 ${queue.pending.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            notice?.let {
                Text("最近事件：$it", style = MaterialTheme.typography.bodySmall)
            }

            if (state.tasks.isEmpty()) {
                Text("还没有提交过任务。", style = MaterialTheme.typography.bodyMedium)
            } else {
                TaskList(
                    tasks = state.tasks.values.sortedByDescending { it.createdAtMs },
                    onCancel = { task -> onCancel(task.promptId ?: task.id) },
                    modifier = Modifier.weight(1f),
                    viewBaseUrl = state.lease?.baseUrl,
                )
            }
        }
    }
}
