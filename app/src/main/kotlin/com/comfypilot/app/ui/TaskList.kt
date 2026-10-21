package com.comfypilot.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ViewRequest
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState
import com.comfypilot.core.transport.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    viewing?.let { task ->
        OutputDialog(task = task, baseUrl = viewBaseUrl, onDismiss = { viewing = null })
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

@Composable
private fun OutputDialog(task: PromptTask, baseUrl: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("产物", style = MaterialTheme.typography.titleMedium)
                task.outputs.forEach { output ->
                    val url = baseUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ApiPaths.viewUrl(it, ViewRequest(output.filename, output.subfolder, output.type)) }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            output.filename,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (url == null) {
                            Text("未连接 ComfyUI，无法预览。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            if (isImageFile(output.filename)) {
                                OutputImage(url = url)
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                            ) { Text("在浏览器打开") }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun OutputImage(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val response = withContext(Dispatchers.IO) {
            OkHttpHttpTransport().execute(HttpRequest(method = "GET", url = url, timeoutMs = 15_000))
        }
        val bytes = response.body
        val decoded = if (response.failure == null && response.status in 200..299 && bytes != null) {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } else {
            null
        }
        bitmap = decoded
        failed = decoded == null
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "产物预览",
            modifier = Modifier.fillMaxWidth().height(200.dp),
            contentScale = ContentScale.Fit,
        )

        failed -> Text("预览加载失败", style = MaterialTheme.typography.bodySmall)

        else -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("加载预览…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun isImageFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { lower.endsWith(it) }
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
