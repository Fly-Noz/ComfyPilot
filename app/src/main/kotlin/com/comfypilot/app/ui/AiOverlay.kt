package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.ai.AiConfig
import com.comfypilot.app.ai.AiRuntime
import com.comfypilot.app.ai.AiSnapshot
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 连接成功后才显示的 AI 悬浮球：可拖动，松手吸附到左右两侧；
 * 点击打开悬浮对话框。
 */
@Composable
fun AiFloatingOverlay(visible: Boolean) {
    if (!visible) return
    val c = Spectrum.colors
    var open by remember { mutableStateOf(false) }
    var side by remember { mutableStateOf(1) } // 1 = 右，0 = 左
    var yFraction by remember { mutableStateOf(0.72f) }
    var dragX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val ballPx = with(density) { 46.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() } - ballPx
        val heightPx = with(density) { maxHeight.toPx() } - ballPx

        if (!dragging) {
            dragX = if (side == 1) widthPx else 0f
        }

        Box(
            modifier = Modifier
                .size(46.dp)
                .offsetPx(dragX, (yFraction * heightPx))
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        c.gradient.map { it.copy(alpha = 0.55f) },
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            side = if (dragX > widthPx / 2f) 1 else 0
                            dragX = if (side == 1) widthPx else 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragX = if (side == 1) widthPx else 0f
                        },
                    ) { _, drag ->
                        dragX = (dragX + drag.x).coerceIn(0f, widthPx)
                        yFraction = ((yFraction * heightPx + drag.y) / heightPx).coerceIn(0.05f, 0.95f)
                    }
                }
                .clickableNoRipple { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = SpectrumMono,
            )
        }
    }

    if (open) {
        AiChatDialog(onDismiss = { open = false })
    }
}

private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.then(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) })

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

@Composable
fun AiChatDialog(onDismiss: () -> Unit) {
    val c = Spectrum.colors
    var snapshot by remember { mutableStateOf(AiRuntime.snapshot()) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = AiRuntime.snapshot()
            delay(200)
        }
    }
    LaunchedEffect(snapshot.messages.size) {
        if (snapshot.messages.isNotEmpty()) {
            listState.animateScrollToItem(snapshot.messages.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(min = 480.dp, max = 640.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(22.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 助手", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    text = "≈${snapshot.tokens} tokens",
                    color = c.textDim,
                    fontSize = 9.sp,
                    fontFamily = SpectrumMono,
                )
                Text(
                    text = "✕",
                    color = c.textDim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickableNoRipple(onDismiss)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(snapshot.messages) { message ->
                    val mine = message.role == "user" && !message.content.startsWith("（工具结果）")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message.content.ifBlank { "…" },
                            color = if (mine) c.text else c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (mine) c.cardStrong else c.card)
                                .padding(9.dp),
                        )
                    }
                }
            }

            snapshot.pending?.let { pending ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accentSoft)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(pending.summary, color = c.text, fontSize = 12.sp)
                    pending.changes.take(6).forEach {
                        Text("· ${it.label}", color = c.textMuted, fontSize = 10.5.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "应用",
                            color = c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickableNoRipple { AiRuntime.confirmPending(submit = false) }
                                .padding(6.dp),
                        )
                        Text(
                            text = "应用并提交",
                            color = c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickableNoRipple { AiRuntime.confirmPending(submit = true) }
                                .padding(6.dp),
                        )
                        Text(
                            text = "取消",
                            color = c.textDim,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickableNoRipple { AiRuntime.cancelPending() }
                                .padding(6.dp),
                        )
                    }
                }
            }

            snapshot.error?.let {
                Text(it, color = c.err, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (snapshot.busy) {
                Text("正在请求模型…", color = c.textDim, fontSize = 10.5.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("让 AI 改参数、开关节点…", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    singleLine = true,
                )
                Text(
                    text = "发送",
                    color = if (snapshot.busy) c.textDim else c.accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickableNoRipple {
                            if (!snapshot.busy && input.isNotBlank()) {
                                AiRuntime.send(input)
                                input = ""
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 设置页与对话里共用的配置编辑器。 */
@Composable
fun AiConfigEditor(
    initial: AiConfig,
    onSave: (AiConfig) -> Unit,
) {
    val c = Spectrum.colors
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var autoSubmit by remember { mutableStateOf(initial.autoSubmit) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("接口地址（OpenAI 兼容）", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（只存本机）", fontSize = 10.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 修改后自动提交", color = c.textMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Switch(checked = autoSubmit, onCheckedChange = { autoSubmit = it })
        }
        Text(
            text = "关掉时，AI 会先展示 diff，由你确认后再应用。",
            color = c.textDim,
            fontSize = 9.5.sp,
        )
        Text(
            text = "保存",
            color = c.accent,
            fontSize = 12.5.sp,
            modifier = Modifier
                .clickableNoRipple {
                    onSave(
                        AiConfig(
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            apiKey = apiKey.trim(),
                            autoSubmit = autoSubmit,
                        ),
                    )
                }
                .padding(vertical = 6.dp),
        )
    }
}


/** 设置页入口：只编辑配置，不打开对话。 */
@Composable
fun AiSettingsDialog(onDismiss: () -> Unit) {
    val c = Spectrum.colors
    var snapshot by remember { mutableStateOf(AiRuntime.snapshot()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI 助手", color = c.text, fontSize = 15.sp)
            AiConfigEditor(
                initial = snapshot.config,
                onSave = {
                    AiRuntime.saveConfig(it)
                    snapshot = AiRuntime.snapshot()
                    onDismiss()
                },
            )
        }
    }
}
