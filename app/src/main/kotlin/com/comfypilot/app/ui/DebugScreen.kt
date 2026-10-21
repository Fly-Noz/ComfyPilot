package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.comfypilot.core.net.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    state: SessionState,
    initialUrl: String,
    notice: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ComfyPilot · 诊断") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 预期管理：不得出现“实时监控”“永久在线”的承诺
            Text(
                "后台可能暂停监控，回来自动同步。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("ComfyUI 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConnect(url) }) { Text("连接") }
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
                OutlinedButton(onClick = onReconnect) { Text("重连") }
                Button(onClick = onSubmit) { Text("提交示例") }
            }

            StatusCard(state)

            notice?.let {
                Text("最近事件：$it", style = MaterialTheme.typography.bodySmall)
            }

            TaskList(
                tasks = state.tasks.values.sortedByDescending { it.createdAtMs },
                onCancel = { task -> onCancel(task.promptId ?: task.id) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusCard(state: SessionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("状态：${state.connectionLabel}", style = MaterialTheme.typography.titleMedium)
            MonoRow("generation", state.generation.toString())
            MonoRow(
                "网络",
                buildString {
                    append(state.network.transport.name)
                    if (state.network.validated) append(" · 已验证")
                },
            )
            MonoRow("clientId", state.lease?.clientId?.take(8) ?: "—")
            state.queue?.let { queue ->
                MonoRow("队列", "运行 ${queue.running.size} / 等待 ${queue.pending.size}")
            }
            state.catalog.let { catalog ->
                if (catalog is com.comfypilot.core.workflow.WorkflowCatalog.Ready) {
                    MonoRow("工作流", "${catalog.entries.size} 个")
                }
            }
        }
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
