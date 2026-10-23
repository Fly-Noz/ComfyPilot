package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * 服务器基础状态：官方 `/system_stats` 只给内存/显存与版本，没有 CPU/GPU 利用率。
 * 装了 ComfyUI-Crystools 时额外读 `/crystools/monitor` 补上 CPU/GPU/温度等指标；
 * 没有这个扩展的服务器保持原样，轮询仍只有一个 2 秒循环，失败保留上一次结果。
 */
@Composable
fun ServerStatsStrip(
    baseUrl: String?,
) {
    val c = Spectrum.colors
    var stats by remember(baseUrl) { mutableStateOf<SystemStats?>(null) }
    var queue by remember(baseUrl) { mutableStateOf<QueueSnapshot?>(null) }
    var crystools by remember(baseUrl) { mutableStateOf<CrystoolsMetrics?>(null) }
    var error by remember(baseUrl) { mutableStateOf<String?>(null) }
    val http = remember { OkHttpHttpTransport() }

    LaunchedEffect(baseUrl) {
        val url = baseUrl ?: return@LaunchedEffect
        val base = url.trimEnd('/')
        // Crystools 各版本对尾斜杠要求不一致：先试不带斜杠，404 再换带斜杠的，
        // 成功一次后锁定，之后只轮询命中的那个地址。
        val candidates = mutableListOf("$base/crystools/monitor", "$base/crystools/monitor/")
        var monitorUrl: String? = null
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

            // 指标扩展只是锦上添花：任何失败都不进 error，也不影响上面的官方状态。
            val target = monitorUrl
            if (target != null) {
                when (val result = fetchCrystools(http, target)) {
                    is CrystoolsFetch.Ok -> crystools = result.metrics
                    CrystoolsFetch.NotFound -> {
                        candidates.remove(target)
                        monitorUrl = null
                    }
                    CrystoolsFetch.Failed -> Unit
                }
            } else if (candidates.isNotEmpty()) {
                var index = 0
                while (index < candidates.size) {
                    when (val result = fetchCrystools(http, candidates[index])) {
                        is CrystoolsFetch.Ok -> {
                            crystools = result.metrics
                            monitorUrl = candidates[index]
                            index = candidates.size
                        }
                        CrystoolsFetch.NotFound -> candidates.removeAt(index)
                        CrystoolsFetch.Failed -> index = candidates.size
                    }
                }
            }
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

        // 只有 Crystools 真正给出某个指标时才加对应格子；缺失的整个省略，不画 0%。
        val metrics = crystools
        val metricCells = buildList {
            metrics?.cpuPercent?.let { add("CPU" to percentText(it)) }
            metrics?.ramPercent?.let { add("内存" to percentText(it)) }
            metrics?.gpuPercent?.let { add("GPU" to percentText(it)) }
            metrics?.vramPercent?.let { add("显存" to percentText(it)) }
            metrics?.temperature?.let { add("温度" to "${it.roundToInt()} °C") }
        }
        if (metricCells.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                metricCells.forEachIndexed { index, (label, value) ->
                    if (index > 0) CellDivider()
                    MetricCell(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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

/** Crystools 指标格：和基础状态同一套配色，值稍大一点便于扫读。 */
@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
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
            fontSize = 12.sp,
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

/** Crystools 监控响应：字段名/嵌套层级跨版本不一致，这里只做宽松提取。 */
private data class CrystoolsMetrics(
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val gpuPercent: Double? = null,
    val vramPercent: Double? = null,
    val temperature: Double? = null,
    val power: Double? = null,
    val fan: Double? = null,
)

private sealed interface CrystoolsFetch {
    data class Ok(val metrics: CrystoolsMetrics) : CrystoolsFetch
    data object NotFound : CrystoolsFetch
    data object Failed : CrystoolsFetch
}

private val monitorJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

private suspend fun fetchCrystools(http: HttpTransport, url: String): CrystoolsFetch {
    val response = withContext(Dispatchers.IO) {
        http.execute(
            HttpRequest(
                method = "GET",
                url = url,
                timeoutMs = 4_000,
            ),
        )
    }
    if (response.failure != null) return CrystoolsFetch.Failed
    if (response.status == 404) return CrystoolsFetch.NotFound
    if (response.status !in 200..299) return CrystoolsFetch.Failed
    val body = response.text()?.takeIf { it.isNotBlank() } ?: return CrystoolsFetch.Failed
    val metrics = runCatching { parseCrystools(body) }.getOrNull() ?: return CrystoolsFetch.Failed
    return CrystoolsFetch.Ok(metrics)
}

/**
 * 宽进：顶层是对象或数组（取第一个元素）都接受；字段名忽略大小写，
 * 并兼容 `gpus[0]` 里嵌套的 GPU 数据。任何缺失/非法值都返回 null，不抛异常。
 */
private fun parseCrystools(body: String): CrystoolsMetrics? {
    val root = runCatching { monitorJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val obj = when (root) {
        is JsonObject -> root
        is JsonArray -> root.firstOrNull() as? JsonObject
        else -> null
    } ?: return null
    val gpus = obj.scalar("gpus")
    val gpu = (gpus as? JsonArray)?.firstOrNull() as? JsonObject ?: gpus as? JsonObject
    return CrystoolsMetrics(
        cpuPercent = percent(obj, null, "cpu_utilization", "cpu_usage", "cpu"),
        ramPercent = percent(obj, null, "ram_used_percent", "ram_percent"),
        gpuPercent = percent(obj, gpu, "gpu_utilization", "gpu_usage", "gpu"),
        vramPercent = percent(obj, gpu, "vram_used_percent", "vram_percent"),
        temperature = number(obj, gpu, "temperature", "gpu_temperature", "temp")
            ?.takeIf { it in -100.0..300.0 },
        power = number(obj, gpu, "power", "gpu_power")?.takeIf { it >= 0.0 },
        fan = number(obj, gpu, "fan_speed", "fan")?.takeIf { it >= 0.0 },
    )
}

/** 按别名取字段，键名大小写不敏感；没有就返回 null。 */
private fun JsonObject.scalar(vararg aliases: String): JsonElement? {
    for ((key, value) in this) {
        if (aliases.any { it.equals(key, ignoreCase = true) }) return value
    }
    return null
}

private fun JsonElement?.asNumber(): Double? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun number(root: JsonObject, nested: JsonObject?, vararg aliases: String): Double? =
    root.scalar(*aliases).asNumber() ?: nested?.scalar(*aliases).asNumber()

private fun percent(root: JsonObject, nested: JsonObject?, vararg aliases: String): Double? =
    number(root, nested, *aliases)?.takeIf { it in 0.0..100.0 }

private fun percentText(value: Double): String = "${value.roundToInt()}%"

private fun gb(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val value = bytes / (1024.0 * 1024.0 * 1024.0)
    return "${(value * 10).roundToInt() / 10.0} GB"
}