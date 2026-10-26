package com.comfypilot.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
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
import com.comfypilot.app.ui.theme.SpectrumDialog
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
    // 运行中的任务每秒刷新一次"已用"；只有列表可见时才计时，离开页面自动停。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = contentPadding,
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                nowMs = nowMs,
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
    nowMs: Long,
    onCancel: (PromptTask) -> Unit,
    onViewOutputs: (() -> Unit)? = null,
) {
    val c = Spectrum.colors
    val running = task.state is TaskState.Running ||
        task.state is TaskState.Queued ||
        task.state is TaskState.Submitting
    val elapsedEnd = if (running) nowMs else task.updatedAtMs
    val elapsedSeconds = ((elapsedEnd - task.createdAtMs) / 1000).coerceAtLeast(0)
    val elapsedText = if (task.createdAtMs <= 0L) {
        ""
    } else {
        val minutes = elapsedSeconds / 60
        val rest = elapsedSeconds % 60
        val clock = if (minutes >= 60) {
            "%d:%02d:%02d".format(minutes / 60, minutes % 60, rest)
        } else {
            "%d:%02d".format(minutes, rest)
        }
        (if (running) "已用 " else "耗时 ") + clock
    }
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
    val targetRail = when (tone) {
        PillTone.Ok -> c.ok
        PillTone.Warn -> c.warn
        PillTone.Err -> c.err
        PillTone.Neutral -> c.textDim
    }
    // 状态色平滑过渡：排队(黄)→执行(绿)→完成(灰)不再是硬切。
    val rail by animateColorAsState(targetValue = targetRail, animationSpec = tween(280), label = "task-rail")
    // 刚完成时给一圈短暂的绿色脉冲，作为"跑完了"的正反馈。
    var confirmCancel by remember(task.id) { mutableStateOf(false) }
    val donePulse = remember { Animatable(0f) }
    LaunchedEffect(task.state) {
        if (task.state is TaskState.Completed) {
            donePulse.snapTo(1f)
            donePulse.animateTo(0f, tween(durationMillis = 900))
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
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
                if (elapsedText.isNotEmpty()) {
                    Text(
                        text = elapsedText,
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                    )
                }
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

            val silentSeconds = if (running) (nowMs - (task.lastProgressAtMs ?: task.updatedAtMs)) / 1000 else 0
            if (running && silentSeconds >= 60) {
                Text(
                    text = "已 ${silentSeconds} 秒没有进度更新 · 主机可能正在加载模型或换出显存",
                    color = c.warn,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
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
                        GhostButton(text = "取消", onClick = { confirmCancel = true }, height = 34.dp)
                    }
                }
            }
        }
    }
        // 完成脉冲只叠一层描边，避免改动内部布局。
        if (donePulse.value > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .border(1.5.dp, c.ok.copy(alpha = donePulse.value * 0.75f), shape),
            )
        }
    }

    if (confirmCancel) {
        SpectrumDialog(
            title = "取消这个任务？",
            onDismiss = { confirmCancel = false },
            confirmText = "确认取消",
            onConfirm = {
                confirmCancel = false
                onCancel(task)
            },
            dismissText = "继续等待",
            body = {
                Text(
                    text = "会向 ComfyUI 发送中断请求；已在运行的任务可能已经产出部分结果。",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
        )
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