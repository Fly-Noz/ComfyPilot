package com.comfypilot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.comfypilot.app.artifact.ArtifactDownloadResult
import com.comfypilot.app.artifact.ArtifactDownloader
import com.comfypilot.app.ui.theme.GhostButton
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.app.ui.theme.TopBarAction
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ArtifactNames
import com.comfypilot.core.api.TaskOutput
import com.comfypilot.core.api.ViewRequest
import com.comfypilot.core.task.PromptTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface DownloadUiState {
    data object Downloading : DownloadUiState
    data class Saved(val name: String) : DownloadUiState
    data class Failed(val message: String) : DownloadUiState
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(c.background)) {
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
                    downloadState = stateOf(current),
                    onBack = { selected = null },
                    onDownload = { requestDownload(current) },
                )
            }
        }
    }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "产物",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                modifier = Modifier.weight(1f),
            )
            TopBarAction(text = "关闭", onClick = onDismiss)
        }
        Text(
            text = task.displayName ?: task.promptId ?: task.id,
            color = c.textDim,
            fontSize = 10.sp,
            fontFamily = SpectrumMono,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        if (task.outputs.isEmpty()) {
            Text(
                text = "该任务没有产物。",
                color = c.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(18.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
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
            text = "${output.kind} · ${output.type}",
            color = c.textDim,
            fontSize = 9.sp,
            fontFamily = SpectrumMono,
            letterSpacing = 0.5.sp,
        )
        if (url != null && isImage) {
            RemoteOutputImage(
                url = url,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onPreview),
            )
        } else if (url != null && isVideo) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.dialog)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .clickable(onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", color = c.heroText, fontSize = 22.sp)
                    Text("视频 · 点击预览", color = c.textDim, fontSize = 10.5.sp)
                }
            }
        } else if (url == null) {
            Text(
                text = "未连接 ComfyUI，无法预览或下载。",
                color = c.textDim,
                fontSize = 11.sp,
            )
        } else {
            Text(
                text = "该类型暂不支持内置预览，可下载后用系统应用打开。",
                color = c.textDim,
                fontSize = 11.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (url != null && (isImage || isVideo)) {
                GhostButton(text = "预览", onClick = onPreview, height = 36.dp)
            }
            if (url != null) {
                if (downloadState is DownloadUiState.Downloading) {
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, c.borderStrong, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = c.accent,
                            strokeWidth = 1.5.dp,
                        )
                        Text("下载中…", color = c.textMuted, fontSize = 11.sp)
                    }
                } else {
                    GhostButton(
                        text = downloadLabel(downloadState),
                        onClick = onDownload,
                        height = 36.dp,
                    )
                }
            }
        }

        downloadMessage(downloadState)?.let {
            Text(
                text = it,
                color = if (downloadState is DownloadUiState.Failed) c.err else c.textDim,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun ArtifactPreview(
    output: TaskOutput,
    baseUrl: String?,
    downloadState: DownloadUiState?,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    val c = Spectrum.colors
    val url = viewUrl(baseUrl, output)
    val viewerBackground = Color(0xFF0B0C10)
    val viewerInk = Color(0xFFF2F4F7)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(viewerBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarAction(text = "返回", onClick = onBack, emphasized = true)
            Text(
                text = output.filename,
                color = viewerInk.copy(alpha = 0.85f),
                fontSize = 10.5.sp,
                fontFamily = SpectrumMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (url != null) {
                TopBarAction(
                    text = downloadLabel(downloadState),
                    onClick = onDownload,
                    enabled = downloadState !is DownloadUiState.Downloading,
                    emphasized = true,
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                url == null -> Text(
                    text = "未连接 ComfyUI，无法预览。",
                    color = viewerInk.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )

                ArtifactNames.isImage(output.filename) -> RemoteOutputImage(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                ArtifactNames.isVideo(output.filename) -> key(url) {
                    ArtifactVideo(url = url, modifier = Modifier.fillMaxSize())
                }

                else -> Text(
                    text = "该类型暂不支持内置预览，可下载后用系统应用打开。",
                    color = viewerInk.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        downloadMessage(downloadState)?.let {
            Text(
                text = it,
                color = viewerInk.copy(alpha = 0.7f),
                fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ArtifactVideo(url: String, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = Spectrum.colors
    var failed by remember(url) { mutableStateOf(false) }
    var prepared by remember(url) { mutableStateOf(false) }
    var videoView by remember(url) { mutableStateOf<VideoView?>(null) }
    var mediaController by remember(url) { mutableStateOf<MediaController?>(null) }

    fun fail() {
        if (failed) return
        failed = true
        runCatching { videoView?.stopPlayback() }
        runCatching { mediaController?.hide() }
    }

    // VideoView 对 HTTP 404 等错误不一定回调 onError；加一个兜底，避免一直转圈。
    LaunchedEffect(url, failed, prepared) {
        if (!failed && !prepared) {
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
        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("视频预览失败", color = Color.White, fontSize = 15.sp)
                Text(
                    text = "可能是设备不支持该编码或网络中断，可下载后用系统应用打开。",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        } else {
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

private fun downloadMessage(state: DownloadUiState?): String? = when (state) {
    null, is DownloadUiState.Downloading -> null
    is DownloadUiState.Saved -> "已保存到下载目录：${state.name}"
    is DownloadUiState.Failed -> "下载失败：${state.message}"
}