package com.comfypilot.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import com.comfypilot.app.ui.theme.ShimmerBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.core.transport.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 `/view` 拉一张产物图。断线或 404 时只显示文字，不阻塞页面。 */
@Composable
fun RemoteOutputImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val response = withContext(Dispatchers.IO) {
            OkHttpHttpTransport().execute(HttpRequest(method = "GET", url = url, timeoutMs = 15_000))
        }
        val bytes = response.body
        val decoded = if (response.failure == null && response.status in 200..299 && bytes != null) {
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } else {
            null
        }
        bitmap = decoded
        failed = decoded == null
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "产物预览",
                modifier = Modifier
                    .fillMaxSize()
                    .fadeInWhenLoaded(),
                contentScale = contentScale,
            )

            failed -> Text("预览加载失败", style = MaterialTheme.typography.bodySmall)

            else -> ShimmerBox(modifier = Modifier.fillMaxSize())
        }
    }
}

/** 解码 ComfyUI 的采样预览帧；失败返回 null，不抛异常。 */
@Composable
fun LivePreviewImage(bytes: ByteArray, modifier: Modifier = Modifier) {
    // 不按 bytes 做 key：视频预览帧来得快、解码慢，按 key 重置会把上一张丢掉并闪回骨架；
    // 这里保留上一帧直到新帧解码成功，LaunchedEffect 换 key 会自动取消过期解码。
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bytes) {
        val decoded = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // 解码失败（半帧/异常格式）保留上一张，避免画面闪空
        if (decoded != null) {
            bitmap = decoded
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "采样预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

fun isImageFile(filename: String): Boolean = com.comfypilot.core.api.ArtifactNames.isImage(filename)
/** 图片解码完成的瞬间淡入，避免从骨架块"啪"地跳到实图。 */
@Composable
private fun Modifier.fadeInWhenLoaded(): Modifier {
    val fade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(220),
        label = "image-fade",
    )
    return this.graphicsLayer { alpha = fade }
}

/**
 * 循环播放产物里的动图（VHS 等节点输出的 gif）。
 *
 * 采样预览是 JPEG 单帧，视频节点真正的"循环预览"来自 executed 事件的 gifs 产物；
 * 用 ImageDecoder 拿到 AnimatedImageDrawable 才能动起来（BitmapFactory 只会解第一帧）。
 */
@Composable
fun LoopingAnimatedImage(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var drawable by remember(url) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val response = withContext(Dispatchers.IO) {
            OkHttpHttpTransport().execute(HttpRequest(method = "GET", url = url, timeoutMs = 20_000))
        }
        val bytes = response.body
        val decoded = if (response.failure == null && response.status in 200..299 && bytes != null) {
            withContext(Dispatchers.Default) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            }
        } else {
            null
        }
        drawable = decoded
        failed = decoded == null
    }

    androidx.compose.runtime.DisposableEffect(drawable) {
        val animated = drawable as? android.graphics.drawable.AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            drawable != null -> androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.ImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(drawable)
                    }
                },
                update = { view -> view.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text("动图加载失败", style = MaterialTheme.typography.bodySmall)

            else -> ShimmerBox(modifier = Modifier.fillMaxSize())
        }
    }
}
