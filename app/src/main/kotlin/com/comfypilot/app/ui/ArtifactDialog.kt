package com.comfypilot.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comfypilot.app.artifact.ArtifactDownloadResult
import com.comfypilot.app.artifact.ArtifactDownloader
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.GlassCard
import com.comfypilot.app.ui.theme.GradientButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumBackdrop
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.SpectrumPill
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ArtifactNames
import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.api.ViewRequest
import com.comfypilot.core.task.PromptTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

private sealed interface DownloadUiState {
    data object Downloading : DownloadUiState
    data class Saved(val name: String) : DownloadUiState
    data class Failed(val message: String) : DownloadUiState
}

/** 预览区统一的深色底板；图片、视频都落在这块哑光面上，避免发白。 */
private val ViewerMatte = Color(0xFF0E0F14)
private val PosterScrim = Color(0x26000000)

/** 首帧缓存：列表缩略图和预览页共用，按 URL 命中，字节数封顶，避免视频帧常驻。 */
private val videoPosterCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * 产物对话框：既可以从任务列表打开，也可以从详情页的预览区打开。
 *
 * 图片可以直接在应用内预览；图片、视频、音频和其他文件都可以流式下载到系统下载目录。
 */
@Composable
fun ArtifactDialog(
    task: PromptTask,
    baseUrl: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = Spectrum.colors
    val scope = rememberCoroutineScope()
    val downloader = remember { ArtifactDownloader() }
    var selected by remember(task.id) { mutableStateOf<TaskOutput?>(null) }
    var downloadStates by remember(task.id) { mutableStateOf<Map<String, DownloadUiState>>(emptyMap()) }
    // 预览区文件大小：HEAD 结果按文件名缓存，退回列表再进来不用重复请求。
    var knownSizes by remember(task.id) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var pendingPermission by remember { mutableStateOf<TaskOutput?>(null) }

    fun stateOf(output: TaskOutput): DownloadUiState? = downloadStates[artifactKey(output)]

    fun mark(output: TaskOutput, state: DownloadUiState) {
        downloadStates = downloadStates + (artifactKey(output) to state)
    }

    fun performDownload(output: TaskOutput) {
        val base = baseUrl?.takeIf { it.isNotBlank() }
        if (base == null) {
            mark(output, DownloadUiState.Failed("未连接 ComfyUI"))
            return
        }
        if (stateOf(output) is DownloadUiState.Downloading) return

        mark(output, DownloadUiState.Downloading)
        scope.launch {
            when (val result = downloader.download(context, base, output)) {
                is ArtifactDownloadResult.Saved -> mark(output, DownloadUiState.Saved(result.displayName))
                is ArtifactDownloadResult.Failed -> mark(output, DownloadUiState.Failed(result.message))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val output = pendingPermission
        pendingPermission = null
        if (output != null) {
            if (granted) {
                performDownload(output)
            } else {
                mark(output, DownloadUiState.Failed("没有存储权限，无法保存到下载目录"))
            }
        }
    }

    fun requestDownload(output: TaskOutput) {
        if (baseUrl.isNullOrBlank()) {
            mark(output, DownloadUiState.Failed("未连接 ComfyUI"))
            return
        }
        // Android 10 起走 MediaStore，不需要这个权限；旧系统写公共 Downloads 才需要。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermission = output
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            performDownload(output)
        }
    }

    Dialog(
        onDismissRequest = {
            if (selected != null) selected = null else onDismiss()
        },
        // 全屏对话框：宽度不吃平台默认值，窗口也不让系统栏把内容挤进去，
        // 这样整块窗口都由下面的光谱底色覆盖，不会露出窗口自己的底色。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 窗口底色来自平台主题，不是 Compose 画的；先把它也刷成光谱底色。
        DialogWindowBackdrop()
        val panelShape = RoundedCornerShape(22.dp)
        Box(modifier = Modifier.fillMaxSize()) {
            // 和页面共用同一层光晕背景：浅色模式下也不是一块死白。
            SpectrumBackdrop(modifier = Modifier.matchParentSize())
            // 产物面板：半透明玻璃底，让背后的光晕透上来一点，再加 22dp 圆角 + 1dp 边框。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp)
                    .clip(panelShape)
                    .background(c.dialog.copy(alpha = if (c.isDark) 0.88f else 0.84f))
                    .border(1.dp, c.borderStrong, panelShape),
            ) {
                val current = selected
                if (current == null) {
                    ArtifactList(
                        task = task,
                        baseUrl = baseUrl,
                        downloadStates = downloadStates,
                        onPreview = { selected = it },
                        onDownload = ::requestDownload,
                        onDismiss = onDismiss,
                    )
                } else {
                    ArtifactPreview(
                        output = current,
                        baseUrl = baseUrl,
                        knownSizes = knownSizes,
                        onSizeKnown = { filename, bytes ->
                            knownSizes = knownSizes + (filename to bytes)
                        },
                        downloadState = stateOf(current),
                        onBack = { selected = null },
                        onDownload = { requestDownload(current) },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * 对话框是独立窗口，窗口底色来自 Activity 的平台主题（本应用是浅色主题，底色发白），
 * Compose 只在运行时用透明色覆盖它；部分兼容层（卓易通）不执行这次覆盖，
 * 首帧就会露出一整块白底。这里把窗口自身也刷成当前光谱底色，并把系统栏设成透明，
 * 保证 Compose 内容画出来之前、以及窗口 inset 区域都不是白的。
 */
@Composable
private fun DialogWindowBackdrop() {
    val c = Spectrum.colors
    val view = LocalView.current
    val background = remember(c.background) { ColorDrawable(c.background.toArgb()) }
    DisposableEffect(view, background, c.isDark) {
        val window = view.findDialogWindow()
        if (window != null) {
            window.setBackgroundDrawable(background)
            // 兼容层不一定像 DialogLayout 那样把窗口撑满，这里显式铺满整屏。
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !c.isDark
                isAppearanceLightNavigationBars = !c.isDark
            }
        }
        onDispose { }
    }
}

/** 从对话框内容往上找承载窗口的 [DialogWindowProvider]；找不到就退化为不处理。 */
private fun View.findDialogWindow(): Window? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

@Composable
private fun ArtifactList(
    task: PromptTask,
    baseUrl: String?,
    downloadStates: Map<String, DownloadUiState>,
    onPreview: (TaskOutput) -> Unit,
    onDownload: (TaskOutput) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Spectrum.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "产物",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                )
                Text(
                    text = task.displayName ?: task.promptId ?: task.id,
                    color = c.textDim,
                    fontSize = 10.sp,
                    fontFamily = SpectrumMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TopBarAction(text = "关闭", onClick = onDismiss, emphasized = true)
        }
        if (task.outputs.isEmpty()) {
            GlassCard(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                padding = PaddingValues(16.dp),
            ) {
                Text(
                    text = "该任务没有产物。",
                    color = c.textMuted,
                    fontSize = 12.5.sp,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(task.outputs) { output ->
                ArtifactRow(
                    output = output,
                    baseUrl = baseUrl,
                    downloadState = downloadStates[artifactKey(output)],
                    onPreview = { onPreview(output) },
                    onDownload = { onDownload(output) },
                )
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    output: TaskOutput,
    baseUrl: String?,
    downloadState: DownloadUiState?,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    val c = Spectrum.colors
    val url = viewUrl(baseUrl, output)
    val isImage = ArtifactNames.isImage(output.filename)
    val isVideo = ArtifactNames.isVideo(output.filename)
    val cardShape = RoundedCornerShape(16.dp)
    val mediaShape = RoundedCornerShape(16.dp)
    val showPreview = url != null && (isImage || isVideo)
    val showPrimary = url != null &&
        downloadState !is DownloadUiState.Downloading &&
        downloadState !is DownloadUiState.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(c.card)
            .border(1.dp, c.border, cardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = output.filename,
            color = c.text,
            fontSize = 10.5.sp,
            fontFamily = SpectrumMono,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artifactMeta(output),
            color = c.textDim,
            fontSize = 10.sp,
            fontFamily = SpectrumMono,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            url == null -> Text(
                text = "未连接 ComfyUI，无法预览或下载。",
                color = c.textMuted,
                fontSize = 12.sp,
            )

            isImage -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(mediaShape)
                    .background(ViewerMatte)
                    .border(1.dp, c.border, mediaShape)
                    .clickable(onClick = onPreview),
            ) {
                RemoteOutputImage(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            isVideo -> VideoPosterFrame(
                url = url,
                label = "视频 · 点击预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(mediaShape)
                    .background(ViewerMatte)
                    .border(1.dp, c.border, mediaShape),
                onClick = onPreview,
            )

            else -> Text(
                text = "该类型暂不支持内置预览，可下载后用系统应用打开。",
                color = c.textMuted,
                fontSize = 12.sp,
            )
        }

        if (showPreview || showPrimary) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showPreview) {
                    GhostButton(text = "预览", onClick = onPreview, height = 36.dp)
                }
                if (showPrimary) {
                    GradientButton(
                        text = downloadLabel(downloadState),
                        onClick = onDownload,
                        height = 36.dp,
                    )
                }
            }
        }

        DownloadStatusLine(downloadState = downloadState, onRetry = onDownload)
    }
}

@Composable
private fun ArtifactPreview(
    output: TaskOutput,
    baseUrl: String?,
    knownSizes: Map<String, Long>,
    onSizeKnown: (String, Long) -> Unit,
    downloadState: DownloadUiState?,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Spectrum.colors
    val context = LocalContext.current
    val url = viewUrl(baseUrl, output)
    val mediaShape = RoundedCornerShape(16.dp)
    val showPrimary = url != null &&
        downloadState !is DownloadUiState.Downloading &&
        downloadState !is DownloadUiState.Failed

    // 大小是锦上添花：按文件名缓存 HEAD 结果，失败就整段省略，不阻塞预览也不提示错误。
    LaunchedEffect(output.filename) {
        if (knownSizes.containsKey(output.filename)) return@LaunchedEffect
        val target = url ?: return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { fetchContentLength(target) }
        if (bytes != null) onSizeKnown(output.filename, bytes)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GhostButton(text = "返回", onClick = onBack, height = 34.dp)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = output.filename,
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artifactMeta(output, knownSizes[output.filename]),
                    color = c.textDim,
                    fontSize = 10.sp,
                    fontFamily = SpectrumMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (url != null) {
                TopBarAction(
                    text = "分享",
                    onClick = { shareArtifact(context, output, url) },
                )
            }
            TopBarAction(text = "关闭", onClick = onDismiss, emphasized = true)
        }

        // 内容区：高度沿用原有 weight(1f)，统一哑光底 + 16dp 圆角。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp)
                .clip(mediaShape)
                .background(ViewerMatte)
                .border(1.dp, c.border, mediaShape),
        ) {
            when {
                url == null -> GlassCard(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    padding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = "未连接 ComfyUI，无法预览。",
                        color = c.textMuted,
                        fontSize = 12.5.sp,
                    )
                }

                ArtifactNames.isImage(output.filename) -> RemoteOutputImage(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                ArtifactNames.isVideo(output.filename) -> key(url) {
                    ArtifactVideo(url = url, modifier = Modifier.fillMaxSize())
                }

                else -> GlassCard(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    padding = PaddingValues(16.dp),
                ) {
                    Text(
                        text = "该类型暂不支持内置预览，可下载后用系统应用打开。",
                        color = c.textMuted,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadStatusLine(downloadState = downloadState, onRetry = onDownload)
            if (showPrimary) {
                GradientButton(
                    text = downloadLabel(downloadState),
                    onClick = onDownload,
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 视频首帧封面：抽出首帧前先显示可点击的占位，点击后才挂载 [VideoView]。
 * 抽帧失败时保留占位并允许直接播放，不阻塞预览。
 */
@Composable
private fun VideoPosterFrame(
    url: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Spectrum.colors
    val poster = rememberVideoPoster(url)
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val frame = poster
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "视频首帧",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(modifier = Modifier.fillMaxSize().background(PosterScrim))
            PlayGlyph(modifier = Modifier.size(54.dp))
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlayGlyph(modifier = Modifier.size(46.dp))
                Text(text = label, color = c.textMuted, fontSize = 11.sp)
            }
        }
        Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            SpectrumPill(text = "视频")
        }
    }
}

@Composable
private fun PlayGlyph(modifier: Modifier = Modifier) {
    // 播放键落在永远深色的哑光板上，用 heroText 而不是任何"白"字面量；
    // 深浅两套主题里它都是浅色，对比度不会翻转。
    val glyph = Spectrum.colors.heroText
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        drawCircle(color = Color(0x59000000), radius = radius)
        drawCircle(
            color = glyph.copy(alpha = 0.3f),
            radius = (radius - 1.dp.toPx()).coerceAtLeast(0f),
            style = Stroke(width = 1.dp.toPx()),
        )
        val path = Path().apply {
            moveTo(size.width * 0.40f, size.height * 0.32f)
            lineTo(size.width * 0.40f, size.height * 0.68f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            close()
        }
        drawPath(path = path, color = glyph.copy(alpha = 0.9f))
    }
}

@Composable
private fun ArtifactVideo(url: String, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = Spectrum.colors
    var failed by remember(url) { mutableStateOf(false) }
    var prepared by remember(url) { mutableStateOf(false) }
    var started by remember(url) { mutableStateOf(false) }
    var videoView by remember(url) { mutableStateOf<VideoView?>(null) }
    var mediaController by remember(url) { mutableStateOf<MediaController?>(null) }

    fun fail() {
        if (failed) return
        failed = true
        runCatching { videoView?.stopPlayback() }
        runCatching { mediaController?.hide() }
    }

    // VideoView 对 HTTP 404 等错误不一定回调 onError；加一个兜底，避免一直转圈。
    // 用户点开首帧封面后才开始计时，封面阶段不能被判定成播放失败。
    LaunchedEffect(url, failed, prepared, started) {
        if (started && !failed && !prepared) {
            delay(20_000)
            if (!failed && !prepared) fail()
        }
    }

    DisposableEffect(url, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // 只暂停，不在回到前台时自动继续；用户按播放键再继续。
                runCatching { videoView?.takeIf { it.isPlaying }?.pause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            videoView?.let { view ->
                view.setMediaController(null)
                view.stopPlayback()
            }
            runCatching { mediaController?.hide() }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            failed -> GlassCard(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                padding = PaddingValues(16.dp),
            ) {
                Text(
                    text = "视频预览失败",
                    color = c.err,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "可能是设备不支持该编码或网络中断，可下载后用系统应用打开。",
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            !started -> VideoPosterFrame(
                url = url,
                label = "点击播放",
                modifier = Modifier.fillMaxSize(),
                onClick = { started = true },
            )

            else -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            val controller = MediaController(viewContext)
                            controller.setAnchorView(this)
                            mediaController = controller
                            setMediaController(controller)
                            setOnPreparedListener { player ->
                                prepared = true
                                player.isLooping = false
                                start()
                            }
                            setOnErrorListener { _, _, _ ->
                                fail()
                                true
                            }
                            setVideoURI(Uri.parse(url))
                        }.also { videoView = it }
                    },
                )
                if (!prepared) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = c.accent,
                    )
                }
            }
        }
    }
}

/** 下载状态行：加载/已保存用 textMuted 12sp，失败用 err 12sp 并带「重试」。 */
@Composable
private fun DownloadStatusLine(
    downloadState: DownloadUiState?,
    onRetry: () -> Unit,
) {
    val c = Spectrum.colors
    when (downloadState) {
        null -> Unit

        is DownloadUiState.Downloading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = c.accent,
                strokeWidth = 1.5.dp,
            )
            Text(text = "下载中…", color = c.textMuted, fontSize = 12.sp)
        }

        is DownloadUiState.Saved -> Text(
            text = "已保存到下载目录：${downloadState.name}",
            color = c.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        is DownloadUiState.Failed -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "下载失败：${downloadState.message}",
                color = c.err,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f),
            )
            GhostButton(text = "重试", onClick = onRetry, height = 32.dp)
        }
    }
}

/** 按 URL 取视频首帧；IO 线程抽帧，成功后进有界缓存，释放 retriever。 */
@Composable
private fun rememberVideoPoster(url: String): Bitmap? {
    // 列表缩略图抽过的帧，进详情页时直接从缓存起画，避免先闪一下占位。
    var poster by remember(url) { mutableStateOf(videoPosterCache.get(url)) }
    LaunchedEffect(url) {
        if (poster == null) {
            poster = loadVideoPoster(url)
        }
    }
    return poster
}

private suspend fun loadVideoPoster(url: String): Bitmap? = withContext(Dispatchers.IO) {
    videoPosterCache.get(url)?.let { return@withContext it }
    val retriever = MediaMetadataRetriever()
    try {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            retriever.setDataSource(url, emptyMap<String, String>())
        } else {
            retriever.setDataSource(url)
        }
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.also { frame ->
            videoPosterCache.put(url, frame)
        }
    } catch (_: Throwable) {
        // 网络、鉴权或编码不支持时，交给调用方回退到占位/播放器。
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun shareArtifact(context: Context, output: TaskOutput, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = ArtifactNames.mimeType(output.filename)
        putExtra(Intent.EXTRA_SUBJECT, output.filename)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "分享产物")) }
}

/** 头部副标题：类型 · 格式，拿到大小时再追加 · 大小。*/
private fun artifactMeta(output: TaskOutput, sizeBytes: Long? = null): String {
    val kind = when {
        ArtifactNames.isImage(output.filename) -> "图片"
        ArtifactNames.isVideo(output.filename) -> "视频"
        ArtifactNames.isAudio(output.filename) -> "音频"
        else -> "文件"
    }
    val format = ArtifactNames.extension(output.filename).uppercase()
    val base = if (format.isEmpty()) "$kind · ${output.kind}" else "$kind · $format"
    val size = sizeBytes?.let(::formatBytes) ?: return base
    return "$base · $size"
}

/** B / KB 取整，MB / GB 保留一位小数；非正数或缺失一律不显示。 */
private fun formatBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * 对 `/view` 发一个 HEAD 只读 Content-Length。ComfyUI 或反代不返回该头时保持 null，
 * 界面就不会出现大小段；任何异常都静默吞掉。shared 的 HttpTransport 不暴露响应头，
 * 所以这里和 ArtifactDownloader 一样直接用 OkHttp。
 */
private fun fetchContentLength(url: String): Long? = try {
    artifactSizeClient.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
        if (response.isSuccessful) {
            response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        } else {
            null
        }
    }
} catch (_: Throwable) {
    null
}

private val artifactSizeClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}

private fun viewUrl(baseUrl: String?, output: TaskOutput): String? = baseUrl
    ?.takeIf { it.isNotBlank() }
    ?.let { ApiPaths.viewUrl(it, ViewRequest(output.filename, output.subfolder, output.type)) }

private fun artifactKey(output: TaskOutput): String =
    "${output.type}\u0000${output.subfolder}\u0000${output.filename}"

private fun downloadLabel(state: DownloadUiState?): String = when (state) {
    null -> "下载"
    is DownloadUiState.Downloading -> "下载中…"
    is DownloadUiState.Saved -> "再次下载"
    is DownloadUiState.Failed -> "重试"
}