package com.comfypilot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.comfypilot.core.net.SessionState
import com.comfypilot.core.workflow.WorkflowCatalog
import com.comfypilot.core.workflow.WorkflowEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowCatalogScreen(
    state: SessionState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onTasks: () -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state.lease != null

    // 进入页面时如果还没拉过列表，自动拉一次；失败后不自动重试，交给用户点"刷新"。
    LaunchedEffect(connected, state.catalog) {
        if (connected && state.catalog is WorkflowCatalog.Idle) onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择工作流") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = onRefresh, enabled = connected) { Text("刷新") }
                    TextButton(onClick = onTasks) { Text("任务") }
                    TextButton(
                        onClick = {
                            onDisconnect()
                            onBack()
                        },
                        enabled = connected,
                    ) { Text("断开") }
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
            Text(
                "${state.connectionLabel} · ${state.network.transport.name} · 第 ${state.generation} 代",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            when (val catalog = state.catalog) {
                is WorkflowCatalog.Idle -> {
                    Text(
                        if (connected) "准备读取服务器工作流…" else "尚未连接 ComfyUI。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is WorkflowCatalog.Loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在读取服务器 workflows 目录…", style = MaterialTheme.typography.bodySmall)
                }

                is WorkflowCatalog.Failed -> {
                    Text("读取失败：${catalog.message}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRefresh, enabled = connected) { Text("重试") }
                }

                is WorkflowCatalog.Ready -> {
                    if (catalog.entries.isEmpty()) {
                        Text("服务器 workflows 目录里没有 .json 工作流。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onRefresh) { Text("刷新") }
                    } else {
                        Text("共 ${catalog.entries.size} 个，点一个打开。", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(catalog.entries, key = { it.path }) { entry ->
                                EntryCard(entry = entry, onClick = { onOpen(entry.path) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: WorkflowEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                entry.sizeBytes?.let {
                    Text(formatSize(it), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                entry.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
