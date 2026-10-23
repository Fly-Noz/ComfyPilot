package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comfypilot.android.transport.OkHttpHttpTransport
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.app.ui.theme.SpectrumMono
import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.QueueSnapshot
import com.comfypilot.core.api.SystemStats
import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 服务器基础状态：官方 `/system_stats` 只给内存/显存与版本，没有 CPU/GPU 利用率。
 * 轮询只在这个页面存活时进行，失败保留上一次结果。
 */
@Composable
fun ServerStatsStrip(
    baseUrl: String?,
) {
    val c = Spectrum.colors
    var stats by remember(baseUrl) { mutableStateOf<SystemStats?>(null) }
    var queue by remember(baseUrl) { mutableStateOf<QueueSnapshot?>(null) }
    var error by remember(baseUrl) { mutableStateOf<String?>(null) }
    val http = remember { OkHttpHttpTransport() }

    LaunchedEffect(baseUrl) {
        val url = baseUrl ?: return@LaunchedEffect
        while (isActive) {
            val statsResult = runCatching { fetchStats(http, url) }
            val queueResult = runCatching { fetchQueue(http, url) }
            statsResult.onSuccess {
                stats = it
                error = null
            }.onFailure {
                error = it.message ?: "状态获取失败"
            }
            queueResult.onSuccess { queue = it }
            delay(2_000)
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        val current = stats
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                label = "内存",
                value = current?.let { "${gb(it.system.ramFree)} / ${gb(it.system.ramTotal)}" } ?: "—",
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            StatCell(
                label = "显存",
                value = current?.devices?.firstOrNull()?.let { device ->
                    "${gb(device.vramFree)} / ${gb(device.vramTotal)}"
                } ?: "—",
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            StatCell(
                label = "队列",
                value = queue?.let { "${it.running.size} / ${it.pending.size}" } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        val problem = when {
            baseUrl == null -> "未连接 ComfyUI"
            current == null && error != null -> error!!
            current == null -> "读取中…"
            current.devices.isEmpty() -> "服务器没有返回设备，显存不可用"
            else -> error?.let { "状态更新失败：$it" }
        }
        if (problem != null) {
            Text(
                text = problem,
                color = if (error != null && current != null) c.err else c.textDim,
                fontSize = 9.5.sp,
                fontFamily = SpectrumMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    Column(
        modifier = modifier.padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            color = c.textDim,
            fontSize = 9.sp,
            fontFamily = SpectrumMono,
            letterSpacing = 0.9.sp,
        )
        Text(
            text = value,
            color = c.text,
            fontSize = 11.sp,
            fontFamily = SpectrumMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CellDivider() {
    val c = Spectrum.colors
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(c.border),
    )
}

private suspend fun fetchStats(http: HttpTransport, baseUrl: String): SystemStats {
    val resolved = ApiPaths.resolve(baseUrl, ApiCall.SystemStats)
    val response = withContext(Dispatchers.IO) {
        http.execute(
            HttpRequest(
                method = resolved.method,
                url = resolved.url,
                timeoutMs = 5_000,
            ),
        )
    }
    val failure = response.failure
    if (failure != null) error(failure)
    if (response.status == 404) error("服务器未提供 /system_stats")
    if (response.status !in 200..299) error("服务器返回 ${response.status}")
    val body = response.text()?.takeIf { it.isNotBlank() } ?: error("状态响应为空")
    return ApiCodec.decodeSystemStats(body)
}

private suspend fun fetchQueue(http: HttpTransport, baseUrl: String): com.comfypilot.core.api.QueueSnapshot {
    val resolved = ApiPaths.resolve(baseUrl, ApiCall.FetchQueue)
    val response = withContext(Dispatchers.IO) {
        http.execute(
            HttpRequest(
                method = resolved.method,
                url = resolved.url,
                timeoutMs = 5_000,
            ),
        )
    }
    val failure = response.failure
    if (failure != null) error(failure)
    if (response.status !in 200..299) error("队列返回 ${response.status}")
    val body = response.text()?.takeIf { it.isNotBlank() } ?: error("队列响应为空")
    return ApiCodec.decodeQueue(body)
}

private fun gb(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val value = bytes / (1024.0 * 1024.0 * 1024.0)
    return "${(value * 10).roundToInt() / 10.0} GB"
}