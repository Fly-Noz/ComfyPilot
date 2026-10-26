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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.comfypilot.app.DiagnosticsLog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.GradientButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTextField
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.core.net.SessionState

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
    val c = Spectrum.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val historyStore = remember(context) { ConnectionHistoryStore(context.applicationContext) }
    var history by remember(context) { mutableStateOf(historyStore.history()) }
    var url by rememberSaveable { mutableStateOf(historyStore.last().ifBlank { initialUrl }) }

    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = "诊断",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassCard(padding = PaddingValues(13.dp)) {
                    Text(
                        text = "COMFYUI 地址",
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                        letterSpacing = 1.1.sp,
                    )
                    Spacer(Modifier.height(7.dp))
                    AddressField(
                        value = url,
                        onValueChange = { url = it },
                        history = history,
                        onPickHistory = { picked ->
                            url = picked
                            historyStore.record(picked)
                            history = historyStore.history()
                        },
                        onDeleteHistory = { removed ->
                            historyStore.remove(removed)
                            history = historyStore.history()
                        },
                        onCommit = { historyStore.saveLast(it) },
                        placeholder = "http://主机IP:8188",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GradientButton(
                            text = "连接",
                            onClick = {
                                historyStore.record(url)
                                history = historyStore.history()
                                onConnect(url)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton(text = "断开", onClick = onDisconnect, height = 46.dp)
                        GhostButton(text = "重连", onClick = onReconnect, height = 46.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        text = "提交示例",
                        onClick = onSubmit,
                        modifier = Modifier.width(110.dp),
                        height = 38.dp,
                    )
                }

                SessionStatusCard(state)

                notice?.let {
                    Text(
                        text = "最近事件：$it",
                        color = c.textDim,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                    )
                }

                // 预期管理：不得出现"实时监控""永久在线"的承诺
                Text(
                    text = "后台可能暂停监控，回来自动同步。",
                    color = c.textDim,
                    fontSize = 10.5.sp,
                )

            }

            Spacer(Modifier.height(8.dp))

            DiagnosticsLogPanel(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .height(190.dp),
            )

            TaskList(
                tasks = state.tasks.values.sortedByDescending { it.createdAtMs },
                onCancel = { task -> onCancel(task.promptId ?: task.id) },
                modifier = Modifier.weight(1f),
                viewBaseUrl = state.lease?.baseUrl,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            )
        }
    }
}
/**
 * 诊断日志面板：WebSocket 事件、HTTP 请求、重连原因按时间倒序滚动显示。
 *
 * 之所以单独一块而不是塞进「最近事件」：这次排查长任务无进度的问题，
 * 需要看到"服务端到底发没发 progress""有没有触发静默重连"，一行摘要不够。
 */
@Composable
private fun DiagnosticsLogPanel(modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val lines by DiagnosticsLog.lines.collectAsState()
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) {
        // 新日志到达时跟到底部，停在顶部就没意义了
        scroll.scrollTo(Int.MAX_VALUE)
    }

    GlassCard(modifier = modifier, padding = PaddingValues(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "日志",
                color = c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${lines.size} 条",
                color = c.textDim,
                fontSize = 9.5.sp,
            )
            Spacer(Modifier.weight(1f))
            GhostButton(
                text = "复制",
                onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("ComfyPilot 日志", DiagnosticsLog.snapshot()),
                    )
                },
                height = 26.dp,
            )
            Spacer(Modifier.width(6.dp))
            GhostButton(text = "清空", onClick = { DiagnosticsLog.clear() }, height = 26.dp)
        }
        Spacer(Modifier.height(7.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = "还没有日志：连接后这里会记录 WebSocket 事件与重连原因。",
                    color = c.textDim,
                    fontSize = 9.5.sp,
                )
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    color = c.textMuted,
                    fontSize = 9.sp,
                    fontFamily = SpectrumMono,
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}