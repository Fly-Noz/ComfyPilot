package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState

/** 任务列表在"任务"页和诊断页共用，避免两套状态文案各写一遍。 */
@Composable
fun TaskList(
    tasks: List<PromptTask>,
    onCancel: (PromptTask) -> Unit,
    modifier: Modifier = Modifier,
    viewBaseUrl: String? = null,
) {
    var viewing by remember { mutableStateOf<PromptTask?>(null) }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onCancel = onCancel,
                onViewOutputs = if (task.outputs.isNotEmpty()) {
                    { viewing = task }
                } else {
                    null
                },
            )
        }
    }

    viewing?.let { selected ->
        // 任务运行中产物会继续增加；对话框里要显示列表里的最新快照，而不是打开时的旧对象。
        val latest = tasks.firstOrNull { it.id == selected.id } ?: selected
        ArtifactDialog(task = latest, baseUrl = viewBaseUrl, onDismiss = { viewing = null })
    }
}

@Composable
private fun TaskCard(
    task: PromptTask,
    onCancel: (PromptTask) -> Unit,
    onViewOutputs: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                task.displayName ?: task.promptId ?: task.id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(taskStateLabel(task.state), style = MaterialTheme.typography.bodySmall)

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
                onViewOutputs?.let { onClick ->
                    OutlinedButton(onClick = onClick) { Text("查看产物") }
                }
            }

            task.error?.let {
                Text("错误：$it", style = MaterialTheme.typography.bodySmall)
            }

            if (task.isActive && task.promptId != null) {
                Spacer(Modifier.height(2.dp))
                OutlinedButton(onClick = { onCancel(task) }) { Text("取消") }
            }
        }
    }
}

fun taskStateLabel(state: TaskState): String = when (state) {
    TaskState.Submitting -> "提交中"
    TaskState.Queued -> "排队中"
    TaskState.Running -> "执行中"
    TaskState.Completed -> "已完成"
    is TaskState.Failed -> "失败"
    TaskState.Cancelled -> "已取消"
    is TaskState.Unknown -> "状态未知"
}
