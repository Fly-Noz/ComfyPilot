package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState

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
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ComfyPilot · 连接层试验田") }) },
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
                    Button(onClick = onSubmit) { Text("提交") }
                }

                StatusCard(state)

                notice?.let {
                    Text("最近事件：$it", style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tasks.values.sortedByDescending { it.createdAtMs }, key = { it.id }) { task ->
                        TaskRow(task = task, onCancel = { onCancel(task.promptId ?: task.id) })
                    }
                }
            }
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

@Composable
private fun TaskRow(task: PromptTask, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                task.promptId ?: task.id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(stateLabel(task.state), style = MaterialTheme.typography.bodySmall)

            val percent = task.progress?.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$percent%", style = MaterialTheme.typography.labelSmall)
            }

            if (task.outputs.isNotEmpty()) {
                Text(
                    "输出：${task.outputs.joinToString("、") { it.filename }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            task.error?.let {
                Text("错误：$it", style = MaterialTheme.typography.bodySmall)
            }

            if (task.isActive && task.promptId != null) {
                Spacer(Modifier.height(2.dp))
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

private fun stateLabel(state: TaskState): String = when (state) {
    TaskState.Submitting -> "提交中"
    TaskState.Queued -> "排队中"
    TaskState.Running -> "执行中"
    TaskState.Completed -> "已完成"
    is TaskState.Failed -> "失败"
    TaskState.Cancelled -> "已取消"
    is TaskState.Unknown -> "状态未知"
}
