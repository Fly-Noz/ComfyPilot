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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.core.transport.HttpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 `/view` 拉一张产物图。断线或 404 时只显示文字，不阻塞页面。 */
@Composable
fun RemoteOutputImage(url: String, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxWidth().height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "产物预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            failed -> Text("预览加载失败", style = MaterialTheme.typography.bodySmall)

            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp))
        }
    }
}

/** 解码 ComfyUI 的采样预览帧；失败返回 null，不抛异常。 */
@Composable
fun LivePreviewImage(bytes: ByteArray, modifier: Modifier = Modifier) {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bytes) {
        bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().height(240.dp),
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

fun isImageFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { lower.endsWith(it) }
}
