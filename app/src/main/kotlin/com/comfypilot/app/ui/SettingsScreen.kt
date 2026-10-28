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
import com.comfypilot.app.ai.AiRuntime
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

private enum class SettingsPage { Main, Ai, Advanced }

@Composable
fun SettingsScreen(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    when (page) {
        SettingsPage.Main -> SettingsMain(
            mode = mode,
            onModeChange = onModeChange,
            onAbout = onAbout,
            onAi = { page = SettingsPage.Ai },
            onAdvanced = { page = SettingsPage.Advanced },
            onBack = onBack,
        )

        SettingsPage.Ai -> AiSettingsPage(onBack = { page = SettingsPage.Main })
        SettingsPage.Advanced -> AdvancedSettingsPage(
            onBack = { page = SettingsPage.Main },
            onDiagnostics = onDiagnostics,
        )
    }
}

@Composable
private fun SettingsMain(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    onAbout: () -> Unit,
    onAi: () -> Unit,
    onAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Spectrum.colors
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
            SectionLabel("外观")
            GlassCard(padding = PaddingValues(6.dp)) {
                ThemeMode.entries.forEachIndexed { index, entry ->
                    ModeRow(mode = entry, selected = mode == entry, onClick = { onModeChange(entry) })
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
            SectionLabel("功能")
            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "AI 助手",
                    value = if (AiRuntime.config.enabled) "已启用" else "未启用",
                    onClick = onAi,
                )
                SpectrumLinkRow(
                    label = "高级",
                    value = "诊断日志、参数重置",
                    onClick = onAdvanced,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            SectionLabel("关于")
            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "关于 ComfyPilot",
                    value = "版本、作者与开源许可",
                    onClick = onAbout,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Spectrum.colors.textDim,
        fontSize = 10.sp,
        fontFamily = SpectrumMono,
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun AiSettingsPage(onBack: () -> Unit) {
    val c = Spectrum.colors
    val context = LocalContext.current
    SpectrumScaffold(
        topBar = { SpectrumTopBar(title = "AI 助手", onBack = onBack) },
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
            GlassCard(padding = PaddingValues(12.dp)) {
                AiConfigEditor(
                    initial = AiRuntime.config,
                    onSave = {
                        AiRuntime.saveConfig(it)
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Text(
                text = "API Key 只保存在本机安全存储；启用后，工作流结构与参数会发送给你配置的模型服务商。",
                color = c.textDim,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun AdvancedSettingsPage(
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val c = Spectrum.colors
    val context = LocalContext.current
    val draftStore = remember { WorkflowDraftStore(context.applicationContext) }
    var confirmReset by remember { mutableStateOf(false) }

    SpectrumScaffold(
        topBar = { SpectrumTopBar(title = "高级", onBack = onBack) },
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
            SectionLabel("诊断")
            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "诊断日志",
                    value = "连接、任务与错误事件",
                    onClick = onDiagnostics,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            SectionLabel("工作流参数")
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
        }
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