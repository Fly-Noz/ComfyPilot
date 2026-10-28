package com.comfypilot.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.app.SessionHost
import com.comfypilot.app.ai.AiConfig
import com.comfypilot.app.ai.AiRuntime
import com.comfypilot.app.ai.AiSnapshot
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.core.api.ImageUpload
import com.comfypilot.core.api.ImageUploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            if (!open && (AiRuntime.pending != null || AiRuntime.question != null)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(c.err),
                )
            }
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
    var scopeOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 附图：AI 自己打不开相册，由这里选图上传，再把服务器文件名交给模型。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { OkHttpHttpTransport() }
    var attachments by remember { mutableStateOf(listOf<String>()) }
    var attaching by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val lease = SessionHost.state.value.lease
        if (lease == null) {
            attachError = "未连接 ComfyUI"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            attaching = true
            attachError = null
            val loaded = withContext(Dispatchers.IO) {
                uris.take(4 - attachments.size).mapNotNull { uri ->
                    runCatching {
                        val name = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: uri.lastPathSegment ?: "image.png"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("读不到图片内容")
                        name to bytes
                    }.getOrNull()
                }
            }
            if (loaded.isEmpty()) {
                attachError = "读取图片失败"
                attaching = false
                return@launch
            }
            val added = mutableListOf<String>()
            for ((filename, bytes) in loaded) {
                when (
                    val result = ImageUpload.upload(
                        http = uploader,
                        baseUrl = lease.baseUrl,
                        filename = filename,
                        bytes = bytes,
                    )
                ) {
                    is ImageUploadResult.Success -> added += result.reference
                    is ImageUploadResult.Failure -> {
                        attachError = result.message
                        break
                    }
                }
            }
            if (added.isNotEmpty()) {
                attachments = (attachments + added).take(4)
            }
            attaching = false
        }
    }

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
                .heightIn(min = 480.dp, max = 660.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(24.dp)),
        ) {
            // 头部：渐变标识 + 标题/模型 + token + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c.gradientBrush()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AI", color = Color.White, fontSize = 11.sp, fontFamily = SpectrumMono)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI 助手", color = c.text, fontSize = 14.sp)
                    Text(
                        text = snapshot.config.model.ifBlank { "未配置模型" },
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "≈${snapshot.tokens}",
                    color = c.textDim,
                    fontSize = 9.sp,
                    fontFamily = SpectrumMono,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.card)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
                if (snapshot.busy) {
                    Text(
                        "停止",
                        color = c.err,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickableNoRipple { AiRuntime.stop() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickableNoRipple(onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = c.textDim, fontSize = 13.sp)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.border),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(snapshot.actions.takeLast(20)) { action ->
                    Text(
                        "· $action",
                        color = c.textDim,
                        fontSize = 9.5.sp,
                        fontFamily = SpectrumMono,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (snapshot.messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("想改哪里？", color = c.textMuted, fontSize = 13.sp)
                            Text(
                                "试试「把步数改成 8」或「关闭 Save Video」",
                                color = c.textDim,
                                fontSize = 10.5.sp,
                            )
                        }
                    }
                }
                items(snapshot.messages.filterNot { isAiToolResult(it.content) }) { message ->
                    val mine = message.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Text(
                            text = (if (mine) message.content else aiPlainText(message.content)).ifBlank { "…" },
                            color = if (mine) c.text else c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 14.dp,
                                        topEnd = 14.dp,
                                        bottomStart = if (mine) 14.dp else 4.dp,
                                        bottomEnd = if (mine) 4.dp else 14.dp,
                                    ),
                                )
                                .background(if (mine) c.accentSoft else c.card)
                                .border(
                                    1.dp,
                                    if (mine) c.accentBorder else c.border,
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            snapshot.question?.let { question ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.card)
                        .border(1.dp, c.accentBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(aiPlainText(question.question), color = c.text, fontSize = 12.sp, lineHeight = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.options.forEach { option ->
                            PendingAction(option, c.accent, c.accentSoft) {
                                AiRuntime.answerQuestion(option)
                            }
                        }
                    }
                }
            }

            snapshot.pending?.let { pending ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.accentSoft)
                        .border(1.dp, c.accentBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(aiPlainText(pending.summary), color = c.text, fontSize = 12.sp)
                    pending.changes.take(6).forEach {
                        Text("· ${it.label}", color = c.textMuted, fontSize = 10.5.sp)
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PendingAction("应用", c.accent, c.card) {
                            AiRuntime.confirmPending(submit = false)
                        }
                        PendingAction("应用并提交", Color.White, c.accent) {
                            AiRuntime.confirmPending(submit = true)
                        }
                        PendingAction("取消", c.textDim, Color.Transparent) {
                            AiRuntime.cancelPending()
                        }
                    }
                }
            }

            snapshot.error?.let {
                Text(
                    text = it,
                    color = c.err,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (snapshot.busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = c.accent,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Text("正在请求模型…", color = c.textDim, fontSize = 10.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "工作区 · " + if (AiRuntime.scopeAll) "全部工作流" else "${AiRuntime.scopePaths.size.coerceAtLeast(1)} 个",
                    color = c.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.card)
                        .border(1.dp, c.border, RoundedCornerShape(10.dp))
                        .clickableNoRipple { scopeOpen = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    attachments.forEachIndexed { index, ref ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "图片${index + 1}：$ref",
                                color = c.textMuted,
                                fontSize = 10.sp,
                                fontFamily = SpectrumMono,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "移除",
                                color = c.textDim,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .clickableNoRipple {
                                        attachments = attachments.filterIndexed { i, _ -> i != index }
                                    }
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
            if (attachError != null) {
                Text(
                    text = attachError.orEmpty(),
                    color = c.err,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }

            // 输入区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c.card)
                        .border(1.dp, c.border, CircleShape)
                        .clickableNoRipple {
                            if (!attaching && !snapshot.busy) picker.launch("image/*")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("图", color = if (attaching) c.textDim else c.textMuted, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.card)
                        .border(1.dp, c.border, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = c.text,
                            fontSize = 12.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    "让 AI 改参数、开关节点…",
                                    color = c.textDim,
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (snapshot.busy) SolidColor(c.cardStrong) else c.gradientBrush())
                        .clickableNoRipple {
                            if (!snapshot.busy && (input.isNotBlank() || attachments.isNotEmpty())) {
                                val text = input
                                val refs = attachments
                                input = ""
                                attachments = emptyList()
                                AiRuntime.send(
                                    if (refs.isEmpty()) {
                                        text
                                    } else {
                                        val lead = text.ifBlank { "用这些图" }
                                        "$lead\n（图片已上传到服务器，文件名：${refs.joinToString("、")}；要放进节点就用 set_input 写这些文件名）"
                                    },
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", color = Color.White, fontSize = 17.sp)
                }
            }
        }
    }

    if (scopeOpen) {
        AiScopeDialog(onDismiss = { scopeOpen = false })
    }
}

@Composable
private fun AiScopeDialog(onDismiss: () -> Unit) {
    val c = Spectrum.colors
    val host = AiRuntime.host
    val paths = host?.availableWorkflows().orEmpty()
    val current = host?.digest()?.path.orEmpty()
    var all by remember { mutableStateOf(AiRuntime.scopeAll) }
    var picked by remember { mutableStateOf(AiRuntime.scopePaths.toSet()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.dialog)
                .border(1.dp, c.borderStrong, RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI 工作区", color = c.text, fontSize = 15.sp)
            Text(
                "选择 AI 可以查看和切换的工作流；用户说「当前」时指正在看的这个。",
                color = c.textDim,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "全部",
                    color = if (all) Color.White else c.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (all) c.accent else c.card)
                        .clickableNoRipple { all = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    "仅当前",
                    color = if (!all) Color.White else c.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (!all) c.accent else c.card)
                        .clickableNoRipple {
                            all = false
                            picked = setOf(current)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(paths) { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableNoRipple {
                                all = false
                                picked = if (path in picked) picked - path else picked + path
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = all || path in picked,
                            onCheckedChange = null,
                        )
                        Text(path, color = c.text, fontSize = 11.sp)
                    }
                }
            }
            Text(
                "完成",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.gradientBrush())
                    .clickableNoRipple {
                        AiRuntime.setScope(all, picked)
                        onDismiss()
                    }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PendingAction(
    label: String,
    textColor: Color,
    background: Color,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    Text(
        text = label,
        color = textColor,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                1.dp,
                if (background == Color.Transparent) c.border else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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

/** 设置页里编辑 AI 配置：开关、接口、模型、Key、自动提交。 */
@Composable
fun AiConfigEditor(
    initial: AiConfig,
    onSave: (AiConfig) -> Unit,
) {
    val c = Spectrum.colors
    var enabled by remember { mutableStateOf(initial.enabled) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var autoSubmit by remember { mutableStateOf(initial.autoSubmit) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启用 AI 助手", color = c.text, fontSize = 13.sp)
                Text("关闭后悬浮球隐藏，不发起请求", color = c.textDim, fontSize = 10.sp)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        AiField("接口地址（OpenAI 兼容）", baseUrl, false) { baseUrl = it }
        AiField("模型名", model, false) { model = it }
        AiField("API Key（只存本机）", apiKey, true) { apiKey = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 修改后自动提交", color = c.textMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Switch(checked = autoSubmit, onCheckedChange = { autoSubmit = it })
        }
        Text(
            text = "关闭时先展示 diff，确认后再应用。",
            color = c.textDim,
            fontSize = 9.5.sp,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(c.gradientBrush())
                .clickableNoRipple {
                    onSave(
                        AiConfig(
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            apiKey = apiKey.trim(),
                            enabled = enabled,
                            autoSubmit = autoSubmit,
                        ),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text("保存", color = Color.White, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun AiField(
    label: String,
    value: String,
    secret: Boolean,
    onChange: (String) -> Unit,
) {
    val c = Spectrum.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = c.textDim, fontSize = 9.5.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.card)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (secret) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 12.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
            )
        }
    }
}
