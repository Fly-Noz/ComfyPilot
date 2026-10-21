package com.comfypilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.comfypilot.android.transport.OkHttpHttpTransport
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

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("服务器状态", style = MaterialTheme.typography.labelSmall)
            val current = stats
            if (current == null) {
                Text(
                    when {
                        baseUrl == null -> "未连接 ComfyUI"
                        error != null -> error!!
                        else -> "读取中…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "RAM 可用 ${gb(current.system.ramFree)} / ${gb(current.system.ramTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                val device = current.devices.firstOrNull()
                Text(
                    if (device == null) {
                        "VRAM 不可用（服务器没有返回设备）"
                    } else {
                        "VRAM 可用 ${gb(device.vramFree)} / ${gb(device.vramTotal)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "队列 运行 ${queue?.running?.size ?: "—"} / 等待 ${queue?.pending?.size ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                error?.let {
                    Text(
                        "状态更新失败：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
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
