package com.comfypilot.app.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.ThemeMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog

/**
 * 设置页。目前只有外观一项：跟随系统 / 深色 / 浅色。
 * 其他设置后续再加，这里只保证入口和分区是稳定的。
 */
@Composable
fun SettingsScreen(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Spectrum.colors
    val context = LocalContext.current
    val draftStore = remember { WorkflowDraftStore(context.applicationContext) }
    var confirmReset by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }

    SpectrumScaffold(
        topBar = { SpectrumTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "外观",
                color = c.textDim,
                fontSize = 10.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 1.2.sp,
            )

            GlassCard(padding = PaddingValues(6.dp)) {
                ThemeMode.entries.forEachIndexed { index, entry ->
                    ModeRow(
                        mode = entry,
                        selected = mode == entry,
                        onClick = { onModeChange(entry) },
                    )
                    if (index != ThemeMode.entries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .height(1.dp)
                                .background(c.border),
                        )
                    }
                }
            }

            Text(
                text = "外观只影响本机显示，不会改变工作流、任务或连接行为。",
                color = c.textDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "工作流参数",
                color = c.textDim,
                fontSize = 10.sp,
                fontFamily = SpectrumMono,
                letterSpacing = 1.2.sp,
            )

            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "恢复所有参数设置为默认",
                    value = "清除本机保存的参数草稿",
                    onClick = { confirmReset = true },
                )
            }

            Text(
                text = "参数草稿只存在本机，不会写回主机或工作流文件。",
                color = c.textDim,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "诊断日志",
                    value = "连接、任务与错误事件",
                    onClick = onDiagnostics,
                )
                SpectrumLinkRow(
                    label = "AI 助手",
                    value = "接口地址、模型与 API Key",
                    onClick = { aiOpen = true },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "更多设置后续补充。",
                color = c.textDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            Spacer(modifier = Modifier.height(4.dp))

            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "关于",
                    value = "版本、作者与开源许可",
                    onClick = onAbout,
                )
            }

            if (aiOpen) {
                AiSettingsDialog(onDismiss = { aiOpen = false })
            }

            if (confirmReset) {
                SpectrumDialog(
                    title = "恢复所有参数？",
                    onDismiss = { confirmReset = false },
                    confirmText = "恢复默认",
                    onConfirm = {
                        confirmReset = false
                        draftStore.clearAll()
                        Toast.makeText(context, "已恢复，重新打开工作流生效", Toast.LENGTH_SHORT).show()
                    },
                    dismissText = "取消",
                    body = {
                        Text(
                            text = "会清除本机保存的所有工作流参数，下次打开工作流时按文件默认值显示。不会影响主机上的工作流文件。",
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 1.5.dp.toPx()
            if (selected) {
                drawCircle(color = c.accent, radius = size.minDimension / 2f)
                drawCircle(color = c.background, radius = size.minDimension / 2f - 5.dp.toPx())
            } else {
                drawCircle(
                    brush = Brush.linearGradient(
                        listOf(c.gradient[1], c.gradient[2]),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = stroke),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.label,
                color = c.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when (mode) {
                    ThemeMode.System -> "跟随系统深色模式"
                    ThemeMode.Dark -> "近黑底 + 色晕，夜里看着最舒服"
                    ThemeMode.Light -> "雾白底 + 色晕，白天户外更清楚"
                },
                color = c.textDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            Text(
                text = "当前",
                color = c.accent,
                fontSize = 10.sp,
                fontFamily = SpectrumMono,
            )
        }
    }
}