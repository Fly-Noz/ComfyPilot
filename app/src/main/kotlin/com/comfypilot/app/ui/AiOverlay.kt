package com.comfypilot.app.ui

import androidx.compose.foundation.Image
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfypilot.app.ai.AiConfig
import com.comfypilot.app.ai.AiFeature
import com.comfypilot.app.ai.AiChatMessage
import com.comfypilot.app.ai.AiImage
import com.comfypilot.app.ai.AiQuestion
import com.comfypilot.app.ai.AiRuntime
import com.comfypilot.app.ai.AiSessionInfo
import com.comfypilot.app.ai.AiSnapshot
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumDialog
import com.comfypilot.app.ui.theme.SpectrumMono
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
            // 球是半透明渐变：深色主题下用白（强调色压在紫红上几乎看不见），
            // 浅色主题下玻璃发白，改用强调色的深紫。
            AiGlyph(size = 28.dp, tint = if (c.isDark) Color.White else c.accent)
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
    // 运行时在协程里改状态（工具步骤、等任务结果）时不会自己触发重组；
    // 挂一个回调把最新快照推进来，否则界面会一直停在「等待任务结果…」。
    androidx.compose.runtime.DisposableEffect(Unit) {
        AiRuntime.onChange = { snapshot = AiRuntime.snapshot() }
        onDispose { AiRuntime.onChange = null }
    }
    var input by remember { mutableStateOf("") }
    var scopeOpen by remember { mutableStateOf(false) }
    var sessionsOpen by remember { mutableStateOf(false) }
    var stepsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 附图：AI 自己打不开相册，由这里选图、只在本机编码（一份给模型看、一份留待上传），
    // 不直接上传。要不要上传到 ComfyUI、传哪张，由 AI 在真正要把图写进节点时用
    // upload_image 工具决定——只在服务器上留用得上的图。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachments by remember { mutableStateOf(listOf<AiAttachment>()) }
    var attaching by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
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
            val added = mutableListOf<AiAttachment>()
            for ((filename, bytes) in loaded) {
                // 只在本地编码，不发请求：vision 给模型看，upload 留着等 AI 决定上传。
                val dataUrl = withContext(Dispatchers.Default) { visionDataUrl(bytes, filename) }
                val upload = withContext(Dispatchers.Default) { uploadData(bytes, filename) }
                added += AiAttachment(filename, dataUrl, upload)
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
    // 是否跟随到底：用户一上手就交出控制权，滑回底部再交回来。
    var autoFollow by remember { mutableStateOf(true) }

    // 只有用户的手指能产生 DragInteraction，程序化滚动不会，所以这个信号是干净的。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                autoFollow = false
            }
        }
    }
    // 内容只会变长，所以「滚不动了」只可能是用户自己滑到了底——这时把跟随交还回来。
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward }.collect { atBottom ->
            if (atBottom) autoFollow = true
        }
    }
    LaunchedEffect(snapshot.messages.size, snapshot.actions.size, snapshot.busy) {
        if (!autoFollow) return@LaunchedEffect
        // 可见条目不是 messages 的下标：工具结果被过滤掉了，「思考与操作」是另外一项。
        // 以前直接用 messages.size - 1 当下标，越界之后滚动就落空，看起来像不回到底部。
        val last = snapshot.messages.count { !isAiToolResult(it.content) } +
            (if (snapshot.busy) 1 else 0) +
            (if (snapshot.actions.isNotEmpty()) 1 else 0) - 1
        if (last < 0) return@LaunchedEffect
        // 等一帧：新气泡还没参与布局时滚动会停在上一次的高度。
        withFrameNanos { }
        listState.animateScrollToItem(last)
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
                    AiGlyph(size = 18.dp, tint = Color.White)
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
                Text(
                    "会话",
                    color = c.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickableNoRipple { sessionsOpen = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
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
                if (snapshot.messages.isEmpty() && !snapshot.busy) {
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
                    AiMessageBubble(
                        message = message,
                        modifier = Modifier.animateItem(),
                        onLocate = { id ->
                            AiRuntime.requestLocate(id)
                            onDismiss()
                        },
                    )
                }
                if (snapshot.busy) {
                    item {
                        AiTypingBubble(
                            activity = snapshot.activity,
                            streaming = snapshot.streaming,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                // 模型没有流式输出，「思考过程」能看见的只有工具往返；收成一张折叠卡，
                // 忙碌时自动展开，平时不占地方。
                if (snapshot.actions.isNotEmpty()) {
                    item {
                        AiStepLog(
                            actions = snapshot.actions,
                            expanded = stepsOpen || snapshot.busy,
                            onToggle = { stepsOpen = !stepsOpen },
                            modifier = Modifier.animateItem(),
                        )
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
                    // 改动多时原来会把「应用并提交」顶出屏幕；限高并允许滚动，按钮始终可见。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            pending.changes.forEach {
                                Text(
                                    text = "· ${it.label}",
                                    color = c.textMuted,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
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
            // 输入区整体包一层：提问时用浮层盖住它，而不是把面板挤小。
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                if (snapshot.canUndo) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "撤销这次修改",
                            color = c.textMuted,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickableNoRipple {
                                    AiRuntime.undoLast()
                                    snapshot = AiRuntime.snapshot()
                                }
                                .padding(6.dp),
                        )
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
                    val auto = snapshot.config.autoSubmit
                    Text(
                        text = if (auto) "自动执行" else "每次确认",
                        color = if (auto) c.accent else c.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (auto) c.accentSoft else c.card)
                            .border(1.dp, if (auto) c.accentBorder else c.border, RoundedCornerShape(10.dp))
                            .clickableNoRipple {
                                AiRuntime.toggleAutoSubmit()
                                snapshot = AiRuntime.snapshot()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }

                // 附图：上传中给转圈，传完直接摆缩略图。
                // 以前只有一行「图片1：文件名」，传没传上去、传的哪张都要靠猜。
                if (attaching || attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (attaching) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.6.dp,
                                    color = c.accent,
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                Text("正在上传…", color = c.textDim, fontSize = 10.sp)
                            }
                        }
                        attachments.forEachIndexed { index, ref ->
                            AiAttachmentChip(ref) {
                                attachments = attachments.filterIndexed { i, _ -> i != index }
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
                        AiImageGlyph(
                            size = 17.dp,
                            tint = if (attaching) c.textDim else c.textMuted,
                        )
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
                    // 同一个按钮：空闲是「发送」，请求中是「停止」——不再让用户去头部找停止。
                    if (snapshot.busy) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(c.cardStrong)
                                .border(1.dp, c.err.copy(alpha = 0.55f), CircleShape)
                                .clickableNoRipple { AiRuntime.stop() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(c.err),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(c.gradientBrush())
                                .clickableNoRipple {
                                    if (input.isNotBlank() || attachments.isNotEmpty()) {
                                        val text = input
                                        val refs = attachments
                                        input = ""
                                        attachments = emptyList()
                                        val lead = text.ifBlank { "用这些图" }
                                        AiRuntime.send(
                                            if (refs.isEmpty()) {
                                                text
                                            } else {
                                                "$lead\n（附了 ${refs.size} 张图，序号从 1 开始：${refs.joinToString("、") { it.name }}；看图回答不必上传，要把图用进工作流时再用 upload_image 上传）"
                                            },
                                            refs.mapNotNull { ref ->
                                                if (ref.dataUrl.isEmpty()) null
                                                else AiImage(ref.name, ref.dataUrl, ref.uploadBytes)
                                            },
                                            // 气泡只显示用户原话；附图说明是给模型的。
                                            displayText = if (refs.isEmpty()) null else lead,
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
                snapshot.question?.let { question ->
                    AiQuestionOverlay(question = question) { option ->
                        AiRuntime.answerQuestion(option)
                    }
                }
            }
        }
    }

    if (scopeOpen) {
        AiScopeDialog(onDismiss = { scopeOpen = false })
    }
    if (sessionsOpen) {
        AiSessionsDialog(onDismiss = { sessionsOpen = false })
    }
}

/** 历史会话：新建 / 切换 / 删除；会话之间的消息与操作记录互不影响。 */
/**
 * 一张待发送的附图：缩略图 + 右上角的移除。
 *
 * 用缩略图而不是文件名，是因为「传上没传上」只能靠眼睛确认——文字行和"已选文件"长得一样，
 * 分不清是排队中还是已经到服务器了。
 */
@Composable
private fun AiAttachmentChip(attachment: AiAttachment, onRemove: () -> Unit) {
    val c = Spectrum.colors
    val bitmap = remember(attachment.dataUrl) {
        runCatching {
            val encoded = attachment.dataUrl.substringAfter("base64,", "")
            if (encoded.isEmpty()) return@runCatching null
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier = Modifier.size(44.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, c.border, RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.card)
                    .border(1.dp, c.border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AiImageGlyph(size = 18.dp, tint = c.textDim)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .clip(CircleShape)
                .background(c.dialog)
                .border(1.dp, c.border, CircleShape)
                .clickableNoRipple(onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = c.textMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun AiSessionsDialog(onDismiss: () -> Unit) {
    val c = Spectrum.colors
    var version by remember { mutableStateOf(0) }
    val infos = remember(version) { AiRuntime.sessionInfos() }
    // 删除不可撤销，问一句再动手。
    var confirmDelete by remember { mutableStateOf<AiSessionInfo?>(null) }
    val timeFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史会话", color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    "新建",
                    color = c.accent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickableNoRipple {
                            AiRuntime.newSession()
                            version++
                            onDismiss()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "✕",
                    color = c.textDim,
                    fontSize = 13.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(6.dp),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(infos) { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (info.active) c.accentSoft else c.card)
                            .border(1.dp, if (info.active) c.accentBorder else c.border, RoundedCornerShape(10.dp))
                            .clickableNoRipple {
                                AiRuntime.switchSession(info.id)
                                version++
                                onDismiss()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = info.title,
                                color = if (info.active) c.accent else c.text,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                text = timeFormat.format(java.util.Date(info.updatedAt)),
                                color = c.textDim,
                                fontSize = 9.sp,
                                fontFamily = SpectrumMono,
                            )
                        }
                        Text(
                            "删除",
                            color = c.err,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickableNoRipple { confirmDelete = info }
                                .padding(6.dp),
                        )
                    }
                }
            }
        }
    }

    // 删除不可撤销：先问一句，确认后才真的删，删完重算列表。
    confirmDelete?.let { target ->
        SpectrumDialog(
            title = "删除这条会话？",
            onDismiss = { confirmDelete = null },
            confirmText = "删除",
            onConfirm = {
                AiRuntime.deleteSession(target.id)
                version++
                confirmDelete = null
            },
            dismissText = "取消",
        ) {
            Text(
                text = "「${target.title.ifBlank { "新会话" }}」及其中的对话记录会被删除，无法恢复。",
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
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

/** 助手头像：一条渐变圆点，比自己发的消息多一个"谁在说话"的锚点。 */
@Composable
private fun AiAvatar(size: Dp = 20.dp) {
    val c = Spectrum.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.gradientBrush()),
        contentAlignment = Alignment.Center,
    ) {
        // 20dp 里只留主星：两颗星缩到这么小，小的那颗会糊成一个点。
        AiGlyph(size = size * 0.62f, tint = Color.White, single = true)
    }
}

/**
 * 一条消息：自己靠右、不带头像；助手靠左、带头像。
 *
 * 气泡宽度用 wrapContentWidth 收在 86% 以内——短句不再被拉成一整条横条，
 * 长句仍然会换行占满可用宽度。
 */
@Composable
private fun AiMessageBubble(
    message: AiChatMessage,
    modifier: Modifier = Modifier,
    onLocate: ((Int) -> Unit)? = null,
) {
    val c = Spectrum.colors
    val mine = message.role == "user"
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) {
            AiAvatar()
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Text(
            // 气泡只看 display：附图时发给模型的「附了 N 张图…」属于内部说明，不上屏。
            text = (if (mine) message.display ?: message.content else aiPlainText(message.content))
                .ifBlank { "…" },
            color = if (mine) c.text else c.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (mine) 14.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 14.dp,
                    ),
                )
                .background(if (mine) c.accentSoft else c.card)
                .border(1.dp, if (mine) c.accentBorder else c.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        )
        if (!mine && onLocate != null) {
            val ids = aiNodeRefs(message.content)
            if (ids.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ids.forEach { id ->
                        Text(
                            text = "定位 #$id",
                            color = c.accent,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.accentSoft)
                                .clickableNoRipple { onLocate.invoke(id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

/** 从回复里挑出 `#节点号`（去重、最多 4 个），渲染成定位按钮。 */
private fun aiNodeRefs(text: String): List<Int> {
    val ids = LinkedHashSet<Int>()
    Regex("#(\\d+)").findAll(text).forEach { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (id > 0 && ids.size < 4) ids += id
    }
    return ids.toList()
}

/** 等待模型时的三个呼吸点：比转圈更轻，和"正在说话"的语义对得上。 */
@Composable
private fun AiTypingBubble(activity: String = "", streaming: String = "", modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        AiAvatar()
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(c.card)
                .border(1.dp, c.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                // 流式正文优先：正在往外吐字的时候，这比「执行 get_node」更能说明在思考。
                streaming.isNotBlank() -> Text(
                    text = streaming,
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                // 能说清在干什么时就不晃三个点了。
                activity.isNotBlank() -> Text(text = activity, color = c.textMuted, fontSize = 11.5.sp)

                else -> repeat(3) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 520, delayMillis = index * 140),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot-$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(c.textDim.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

/**
 * 思考与操作记录。
 *
 * 模型这里是整段返回、没有流式输出，能看见的"过程"只有工具往返。
 * 所以把它收成一张折叠卡：忙碌时自动展开看进度，平时折叠只留步数。
 */
@Composable
private fun AiStepLog(
    actions: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Spectrum.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card.copy(alpha = 0.55f))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onToggle)
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "▾" else "▸", color = c.textDim, fontSize = 9.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
            Text(
                text = "思考与操作 · ${actions.size} 步",
                color = c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
            )
        }
        if (expanded) {
            actions.takeLast(12).forEach { action ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(c.accent.copy(alpha = 0.55f)),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Text(action, color = c.textMuted, fontSize = 9.5.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

/**
 * 提问浮层：盖住输入区，而不是把面板挤小。
 *
 * 选项竖排整行（和常见 Agent 客户端一致），选项多时浮层内部滚动；
 * 上面压一层半透明底，提示"先把这个答了再输入"。
 */
@Composable
private fun AiQuestionOverlay(question: AiQuestion, onPick: (String) -> Unit) {
    val c = Spectrum.colors
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(c.dialog.copy(alpha = 0.92f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(c.cardStrong)
                .border(1.dp, c.accentBorder, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "需要你确认",
                    color = c.accent,
                    fontSize = 9.5.sp,
                    fontFamily = SpectrumMono,
                    letterSpacing = 0.6.sp,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text("选一个继续", color = c.textDim, fontSize = 9.5.sp)
            }
            Text(
                text = aiPlainText(question.question),
                color = c.text,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                question.options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.dialog)
                            .border(1.dp, c.border, RoundedCornerShape(10.dp))
                            .clickableNoRipple { onPick(option) }
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ('A' + index).toString(),
                            color = c.accent,
                            fontSize = 10.sp,
                            fontFamily = SpectrumMono,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(c.accentSoft)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(9.dp))
                        Text(
                            text = option,
                            color = c.text,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (index == 0) {
                            // 提示词要求选项按推荐程度排序，第一个就是最推荐的。
                            Text(
                                text = "推荐",
                                color = c.accent,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c.accentSoft)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
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

/** 设置页里编辑 AI 配置：服务商、接口、模型、Key、自动提交、轮数。 */
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
    var maxRounds by remember { mutableStateOf(initial.maxRounds.coerceIn(2, 20)) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "启用 AI 助手",
                    color = if (AiFeature.available) c.text else c.textDim,
                    fontSize = 13.sp,
                )
                Text(
                    text = if (AiFeature.available) "关闭后悬浮球隐藏，不发起请求" else AiFeature.notice,
                    color = c.textDim,
                    fontSize = 10.sp,
                )
            }
            Switch(
                checked = AiFeature.available && enabled,
                onCheckedChange = { enabled = it },
                enabled = AiFeature.available,
            )
        }
        // 功能没开放时不摆输入框：填了也不会生效，只会让人以为是自己没配对。
        if (AiFeature.available) {
            AiProviderPicker(currentBaseUrl = baseUrl) { preset ->
                if (preset.baseUrl.isNotEmpty()) {
                    baseUrl = preset.baseUrl
                    model = preset.model
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("单轮工具轮数上限", color = c.textMuted, fontSize = 11.sp)
                    Text("默认 8；切流 + 查节点 + 改 + 提交通常够用", color = c.textDim, fontSize = 9.5.sp)
                }
                RoundStep("−") { maxRounds = (maxRounds - 1).coerceAtLeast(2) }
                Text("$maxRounds", color = c.text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                RoundStep("+") { maxRounds = (maxRounds + 1).coerceAtMost(20) }
            }
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
                                maxRounds = maxRounds,
                            ),
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text("保存", color = Color.White, fontSize = 12.5.sp)
            }
        }
    }
}

/** 主流厂商预设：选完自动填地址与模型；模型名以厂商文档为准，仍可手改。 */
private data class AiProviderPreset(val name: String, val baseUrl: String, val model: String)

private val AI_PROVIDER_PRESETS = listOf(
    AiProviderPreset("DeepSeek（推荐）", "https://api.deepseek.com", "deepseek-flash"),
    AiProviderPreset("DeepSeek V4 Pro", "https://api.deepseek.com", "deepseek-v4-pro"),
    AiProviderPreset("通义千问 Qwen3.8 Max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-max"),
    AiProviderPreset("通义千问 Flash", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-flash"),
    AiProviderPreset("Kimi", "https://api.moonshot.cn/v1", "kimi-k3"),
    AiProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3"),
    AiProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-6-astra"),
    AiProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-pro"),
    AiProviderPreset("自定义", "", ""),
)

@Composable
private fun AiProviderPicker(currentBaseUrl: String, onPick: (AiProviderPreset) -> Unit) {
    val c = Spectrum.colors
    var expanded by remember { mutableStateOf(false) }
    val matched = AI_PROVIDER_PRESETS.firstOrNull {
        it.baseUrl.isNotEmpty() && currentBaseUrl.startsWith(it.baseUrl)
    } ?: AI_PROVIDER_PRESETS.last()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("服务商", color = c.textMuted, fontSize = 11.sp)
            Text("选中后自动填地址与模型，仍可手改", color = c.textDim, fontSize = 9.5.sp)
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(c.card)
                    .border(1.dp, c.border, RoundedCornerShape(9.dp))
                    .clickableNoRipple { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(matched.name, color = c.text, fontSize = 11.sp)
                Text(" ▾", color = c.textDim, fontSize = 10.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AI_PROVIDER_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, fontSize = 12.sp) },
                        onClick = {
                            expanded = false
                            onPick(preset)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundStep(label: String, onClick: () -> Unit) {
    val c = Spectrum.colors
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.card)
            .border(1.dp, c.border, RoundedCornerShape(8.dp))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = c.textMuted, fontSize = 13.sp)
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

/**
 * 聊天附件：一张待发的图。
 *
 * [name] 是原文件名（含扩展名）；[dataUrl] 是给多模态模型看的 base64（vision 压缩），
 * 为空表示编码失败；[uploadBytes] 是等 AI 决定上传时才发出去的字节（长边 2048 压缩）。
 */
private data class AiAttachment(
    val name: String,
    val dataUrl: String,
    val uploadBytes: ByteArray?,
)

/**
 * 给多模态模型用的 data URL：太大先压到长边 1024 / JPEG 80。
 *
 * 不能像以前那样「太大就只给文件名」——用户传图就是要模型看懂画面，
 * 把图丢掉等于让它盲写提示词。
 */
private fun visionDataUrl(bytes: ByteArray, filename: String): String {
    if (bytes.size <= 1_200_000) {
        val mime = if (filename.endsWith(".png", true)) "image/png" else "image/jpeg"
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    return runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching ""
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scale = if (maxSide > 1024) 1024f / maxSide else 1f
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")
}

/**
 * 给 ComfyUI 上传用的字节：长边压到 2048 / JPEG 90，小图原样。
 *
 * 相册原图常 5–20 MB，经热点/Tailscale 上传会撞超时；压到 1 MB 上下既快又够 LoadImage 用。
 * 鸿蒙用的是 `ImageUploader.compressForUpload`（同一组参数），改这里要同步那边。
 */
private fun uploadData(bytes: ByteArray, filename: String): ByteArray? {
    if (bytes.size <= 2 * 1024 * 1024) {
        return runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide <= 2048) bytes else compressUpload(bitmap)
        }.getOrNull()
    }
    return runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        compressUpload(bitmap)
    }.getOrNull()
}

private fun compressUpload(bitmap: Bitmap): ByteArray {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    val scale = if (maxSide > 2048) 2048f / maxSide else 1f
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}
