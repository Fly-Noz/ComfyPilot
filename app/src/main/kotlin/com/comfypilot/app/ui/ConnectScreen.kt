package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.Dot
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.GradientButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTextField
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.net.SessionPhase
import com.comfypilot.core.net.SessionState

@Composable
fun ConnectScreen(
    state: SessionState,
    initialUrl: String,
    notice: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onBrowse: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Spectrum.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val historyStore = remember(context) { ConnectionHistoryStore(context.applicationContext) }
    var history by remember(context) { mutableStateOf(historyStore.history()) }
    var url by rememberSaveable { mutableStateOf(historyStore.last().ifBlank { initialUrl }) }
    val connected = state.phase is SessionPhase.Connected
    val hasLease = state.lease != null

    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = "",
                titleOverride = { Wordmark() },
                actions = {
                    TopBarAction(text = "设置", onClick = onSettings)
                    TopBarAction(text = "诊断", onClick = onDiagnostics)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "连接 ComfyUI",
                color = c.text,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            )
            Text(
                text = "连上后选择服务器上已有的工作流，调参数、提交任务、看采样。",
                color = c.textMuted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )

            GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
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
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    GradientButton(
                        text = if (connected) "重新连接" else "连接",
                        onClick = {
                            historyStore.record(url)
                            history = historyStore.history()
                            onConnect(url)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "断开",
                        onClick = onDisconnect,
                        enabled = hasLease,
                        modifier = Modifier.width(84.dp),
                    )
                }
                if (hasLease) {
                    Spacer(Modifier.height(9.dp))
                    GhostButton(
                        text = "选择工作流",
                        onClick = onBrowse,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SessionStatusCard(state)

            notice?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "最近事件",
                        color = c.textDim,
                        fontSize = 10.sp,
                        fontFamily = SpectrumMono,
                    )
                    Text(
                        text = it,
                        color = c.textMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (connected) {
                Text(
                    text = "已连接，正在进入工作流列表；也可以点上面的「选择工作流」。",
                    color = c.textDim,
                    fontSize = 11.sp,
                )
            }

            Text(
                text = "后台可能暂停监控，回来自动同步。",
                color = c.textDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Wordmark() {
    val c = Spectrum.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "Comfy",
            color = c.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
        Text(
            text = "Pilot",
            style = TextStyle(
                brush = c.gradientBrush(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
        )
    }
}

@Composable
internal fun SessionStatusCard(state: SessionState) {
    val c = Spectrum.colors
    GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Dot(color = if (state.lease != null) c.ok else c.textDim)
            Text(
                text = state.connectionLabel,
                color = c.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "GEN ${state.generation}",
                color = c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 0.6.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        MonoRow("网络", buildString {
            append(state.network.transport.name)
            if (state.network.validated) append(" · 已验证")
        })
        MonoRow("clientId", state.lease?.clientId?.take(8) ?: "—")
        state.queue?.let { queue ->
            MonoRow("队列", "运行 ${queue.running.size} / 等待 ${queue.pending.size}")
        }
    }
}

@Composable
private fun MonoRow(label: String, value: String) {
    val c = Spectrum.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = c.textDim,
            fontSize = 9.5.sp,
            fontFamily = SpectrumMono,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            color = c.textMuted,
            fontSize = 11.5.sp,
            fontFamily = SpectrumMono,
            modifier = Modifier.weight(1f),
        )
    }
}