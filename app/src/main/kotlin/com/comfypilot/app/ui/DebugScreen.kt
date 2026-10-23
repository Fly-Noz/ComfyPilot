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
                        onCommit = { historyStore.saveLast(it) },
                        placeholder = "http://192.168.1.10:8188",
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