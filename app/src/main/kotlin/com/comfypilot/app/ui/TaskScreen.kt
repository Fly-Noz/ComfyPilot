package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.Dot
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.net.SessionState

@Composable
fun TaskScreen(
    state: SessionState,
    notice: String?,
    onBack: () -> Unit,
    onCancel: (String) -> Unit,
    onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = Spectrum.colors

    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = "任务",
                onBack = onBack,
                actions = {
                    TopBarAction(text = "诊断", onClick = onDiagnostics)
                    TopBarAction(text = "断开", onClick = onDisconnect)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Dot(color = if (state.lease != null) c.ok else c.textDim)
                    Text(
                        text = state.connectionLabel,
                        color = c.textMuted,
                        fontSize = 11.5.sp,
                    )
                    Text(
                        text = "· ${state.network.transport.name} · 第 ${state.generation} 代",
                        color = c.textDim,
                        fontSize = 10.sp,
                        fontFamily = SpectrumMono,
                    )
                }
                state.queue?.let { queue ->
                    Text(
                        text = "队列：运行 ${queue.running.size} / 等待 ${queue.pending.size}",
                        color = c.textDim,
                        fontSize = 10.5.sp,
                        fontFamily = SpectrumMono,
                    )
                }
                notice?.let {
                    Text(
                        text = "最近事件：$it",
                        color = c.textDim,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            if (state.tasks.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(top = 24.dp),
                ) {
                    GlassCard(padding = PaddingValues(16.dp)) {
                        Text(
                            text = "还没有提交过任务。",
                            color = c.textMuted,
                            fontSize = 12.5.sp,
                        )
                        Text(
                            text = "在详情页调好参数后点「提交任务」，进度和产物会出现在这里。",
                            color = c.textDim,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
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