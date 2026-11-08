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
import androidx.compose.material3.Switch
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
import com.comfypilot.app.ai.AiFeature
import com.comfypilot.app.ai.AiRuntime
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumScaffold
import com.comfypilot.app.ui.theme.SpectrumTopBar
import com.comfypilot.app.ui.theme.ThemeMode
import com.comfypilot.app.update.UpdatePrefs
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
    state: com.comfypilot.core.net.SessionState,
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
            state = state,
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
    state: com.comfypilot.core.net.SessionState,
) {
    val c = Spectrum.colors
    val context = LocalContext.current
    var updateDialog by remember { mutableStateOf(false) }
    var updateMode by remember { mutableStateOf(UpdatePrefs.mode(context)) }
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
            SectionLabel("网络")
            NetworkCheckSection(state = state)

            Spacer(modifier = Modifier.height(4.dp))
            SectionLabel("功能")
            GlassCard(padding = PaddingValues(6.dp)) {
                SpectrumLinkRow(
                    label = "AI 助手",
                    value = when {
                        !AiFeature.available -> "开发中，暂不可用"
                        AiRuntime.config.enabled -> "已启用"
                        else -> "未启用"
                    },
                    onClick = onAi,
                )
                SpectrumLinkRow(
                    label = "高级",
                    value = "诊断日志、参数重置",
                    onClick = onAdvanced,
                )
                SpectrumLinkRow(
                    label = "更新提醒",
                    value = updateMode.label,
                    onClick = {
                        updateMode = UpdatePrefs.mode(context)
                        updateDialog = true
                    },
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

    if (updateDialog) {
        SpectrumDialog(
            title = "更新提醒",
            onDismiss = { updateDialog = false },
            confirmText = "保存",
            dismissText = "取消",
            onConfirm = {
                UpdatePrefs.setMode(context, updateMode)
                updateDialog = false
            },
        ) {
            UpdatePrefs.Mode.entries.forEach { option ->
                val description = when (option) {
                    UpdatePrefs.Mode.ALWAYS -> "每次打开应用都检查；有新版就提示"
                    UpdatePrefs.Mode.ONCE_PER_VERSION -> "点过稍后后，同一版本不再提示；出现更新版本或手动检查仍会提示"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { updateMode = option }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            color = c.text,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = description,
                            color = c.textDim,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (updateMode == option) "●" else "○",
                        color = if (updateMode == option) c.accent else c.textDim,
                        fontSize = 14.sp,
                    )
                }
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
            SectionLabel("提交")
            GlassCard(padding = PaddingValues(12.dp)) {
                var forceSubmit by remember { mutableStateOf(ForceSubmitPrefs.isEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("忽略本地校验，强制提交", color = c.text, fontSize = 13.sp)
                        Text(
                            "本地有报错也照样把任务发给服务器，由 ComfyUI 判定",
                            color = c.textDim,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    Switch(
                        checked = forceSubmit,
                        onCheckedChange = {
                            forceSubmit = it
                            ForceSubmitPrefs.setEnabled(context, it)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            SectionLabel("网络")
            GlassCard(padding = PaddingValues(12.dp)) {
                var weakNetwork by remember { mutableStateOf(WeakNetworkPrefs.isEnabled(context)) }
                var showWeakConfirm by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("弱网模式", color = c.text, fontSize = 13.sp)
                        Text(
                            "状态轮询降到 15 秒；AI 上下文强制走本地摘要",
                            color = c.textDim,
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    Switch(
                        checked = weakNetwork,
                        onCheckedChange = {
                            if (it) {
                                showWeakConfirm = true
                            } else {
                                weakNetwork = false
                                WeakNetworkPrefs.setEnabled(context, false)
                            }
                        },
                    )
                }
                if (showWeakConfirm) {
                    SpectrumDialog(
                        title = "开启弱网模式？",
                        onDismiss = {
                            weakNetwork = false
                            showWeakConfirm = false
                        },
                        confirmText = "开启",
                        dismissText = "取消",
                        onConfirm = {
                            weakNetwork = true
                            WeakNetworkPrefs.setEnabled(context, true)
                            showWeakConfirm = false
                        },
                    ) {
                        Text("开启后以下行为会改变：", color = c.text, fontSize = 12.5.sp)
                        Text("· 状态轮询从 2 秒降到 15 秒", color = c.textMuted, fontSize = 11.5.sp)
                        Text("· AI 上下文强制用本地机械摘要（不额外调模型）", color = c.textMuted, fontSize = 11.5.sp)
                        Text("· 不再播放多帧采样动画，只保留单帧预览", color = c.textMuted, fontSize = 11.5.sp)
                        Text("· 上传图片压缩更狠（长边 1536）", color = c.textMuted, fontSize = 11.5.sp)
                        Text("· 下载超时放宽，失败自动重试", color = c.textMuted, fontSize = 11.5.sp)
                        Text("随时可以在同一位置关闭。", color = c.textDim, fontSize = 10.5.sp)
                    }
                }
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