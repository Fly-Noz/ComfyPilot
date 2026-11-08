package com.comfypilot.app.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.DiagnosticsLog
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar

/**
 * 诊断页只保留日志：连接、任务、参数都回各自的页面，避免这里变成第二个调试台。
 */
@Composable
fun DebugScreen(onBack: (() -> Unit)? = null) {
    SpectrumScaffold(
        topBar = {
            SpectrumTopBar(
                title = "诊断",
                onBack = onBack,
            )
        },
    ) { padding ->
        DiagnosticsLogPanel(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/**
 * 诊断日志面板：WebSocket 事件、HTTP 请求、重连原因按时间倒序滚动显示。
 */
@Composable
private fun DiagnosticsLogPanel(modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val lines by DiagnosticsLog.lines.collectAsState()
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) {
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
