package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.PillTone
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumPill
import com.comfypilot.app.ui.theme.SpectrumProgress
import com.comfypilot.core.task.PromptTask
import com.comfypilot.core.task.TaskState

/** 任务列表在"任务"页和诊断页共用，避免两套状态文案各写一遍。 */
@Composable
fun TaskList(
    tasks: List<PromptTask>,
    onCancel: (PromptTask) -> Unit,
    modifier: Modifier = Modifier,
    viewBaseUrl: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
) {
    var viewing by remember { mutableStateOf<PromptTask?>(null) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = contentPadding,
    ) {
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
    val c = Spectrum.colors
    val shape = RoundedCornerShape(16.dp)
    val tone = when (task.state) {
        TaskState.Submitting -> PillTone.Warn
        TaskState.Queued -> PillTone.Warn
        TaskState.Running -> PillTone.Ok
        TaskState.Completed -> PillTone.Neutral
        is TaskState.Failed -> PillTone.Err
        TaskState.Cancelled -> PillTone.Neutral
        is TaskState.Unknown -> PillTone.Neutral
    }
    val rail = when (tone) {
        PillTone.Ok -> c.ok
        PillTone.Warn -> c.warn
        PillTone.Err -> c.err
        PillTone.Neutral -> c.textDim
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(rail),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = task.displayName ?: task.promptId ?: task.id,
                    color = c.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SpectrumMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SpectrumPill(text = taskStateLabel(task.state), tone = tone)
            }

            val percent = task.progress?.percent
            if (percent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpectrumProgress(
                        fraction = percent / 100f,
                        height = 5.dp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$percent%",
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                    )
                }
            }

            if (task.outputs.isNotEmpty()) {
                Text(
                    text = "输出：${task.outputs.joinToString("、") { it.filename }}",
                    color = c.textDim,
                    fontSize = 10.sp,
                    fontFamily = SpectrumMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            task.error?.let {
                Text(
                    text = it,
                    color = c.err,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onViewOutputs != null || (task.isActive && task.promptId != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onViewOutputs?.let { onClick ->
                        GhostButton(text = "查看产物", onClick = onClick, height = 34.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (task.isActive && task.promptId != null) {
                        GhostButton(text = "取消", onClick = { onCancel(task) }, height = 34.dp)
                    }
                }
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