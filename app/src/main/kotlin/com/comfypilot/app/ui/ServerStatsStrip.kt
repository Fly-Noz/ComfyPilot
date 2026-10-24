package com.comfypilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.comfypilot.core.net.MonitorSample
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
 *
 * 装了 ComfyUI-Crystools 的服务器会把 CPU/GPU/温度等指标通过 WebSocket 推上来，
 * 共享层解析后以 [monitor] 传进来，这里优先用它；`/system_stats` 仍负责内存/显存/版本，
 * `/crystools/monitor` 的 HTTP 轮询也保留着当兜底（很多服务器根本没有这个路由）。
 * 轮询仍是单个 2 秒循环，失败保留上一次结果。
 */
@Composable
fun ServerStatsStrip(
    baseUrl: String?,
    monitor: MonitorSample? = null,
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

    val current = stats
    val metrics = crystools
    val live = monitor
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

        // 百分比短，窄屏不会像 "8.5 GB / 16 GB" 那样被裁掉。总量为 0 视为未知，
        // 此时用 Crystools 的百分比兜底；仍然缺就整格省略，不画 "--"。
        // 内存/显存优先用 WebSocket 的实时采样，其次是 /system_stats，最后才是 HTTP 兜底。
        val ramPercent = live?.ramPercent
            ?: current?.let { usedPercent(it.system.ramTotal, it.system.ramFree) }
            ?: metrics?.ramPercent
        val vramPercent = live?.vramPercent
            ?: current?.devices?.firstOrNull()
                ?.let { usedPercent(it.vramTotal, it.vramFree) }
            ?: metrics?.vramPercent
        val cpuPercent = live?.cpuPercent ?: metrics?.cpuPercent
        val gpuPercent = live?.gpuPercent ?: metrics?.gpuPercent
        val temperature = live?.temperatureC ?: metrics?.temperature

        // 数值滚动：推送每秒一条，直接跳字会显得闪；320ms 补间更像仪表盘。
        val ramShown = rememberAnimatedPercent(ramPercent)
        val vramShown = rememberAnimatedPercent(vramPercent)
        val cpuShown = rememberAnimatedPercent(cpuPercent)
        val gpuShown = rememberAnimatedPercent(gpuPercent)

        val cells = buildList {
            if (ramPercent != null) add(StatValue("内存", percentText(ramShown)))
            if (vramPercent != null) add(StatValue("显存", percentText(vramShown)))
            cpuPercent?.let { add(StatValue("CPU", percentText(cpuShown))) }
            gpuPercent?.let { add(StatValue("GPU", percentText(gpuShown))) }
            temperature?.let { add(StatValue("温度", "${temperature.roundToInt()}°C")) }
            live?.gpuName?.takeIf { it.isNotBlank() }?.let { add(StatValue("显卡", it)) }
            // 队列/版本放最后：硬件指标更常用，先看到。
            queue?.let { add(StatValue("队列", "${it.running.size} / ${it.pending.size}")) }
            current?.system?.comfyuiVersion?.takeIf { it.isNotBlank() }
                ?.let { add(StatValue("版本", it)) }
        }

        // 没有数据的连接页不占地方。
        if (cells.isEmpty()) return@ServerStatsStrip

        // 收起时只留一行：标题 + 两条最有用的数字；展开才铺开两列明细。
        val summary = buildList {
            cells.firstOrNull { it.label == "内存" }?.let { add("内存 ${it.value}") }
            cells.firstOrNull { it.label == "显存" }?.let { add("显存 ${it.value}") }
            if (isEmpty()) {
                cells.firstOrNull { it.label == "CPU" }?.let { add("CPU ${it.value}") }
                cells.firstOrNull { it.label == "温度" }?.let { add(it.value) }
            }
        }.joinToString(" · ")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.card)
                .border(1.dp, c.border, shape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "主机状态",
                    color = c.textMuted,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.6.sp,
                )
                Spacer(Modifier.weight(1f))
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        color = c.textDim,
                        fontSize = 10.5.sp,
                        fontFamily = SpectrumMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (expanded) "收起 ▾" else "展开 ▸",
                    color = c.textDim,
                    fontSize = 10.sp,
                )
            }

            // 展开/收起要有过渡：高度用 expandVertically，内容跟着淡入，
            // 否则点一下会"啪"地跳出一整块。
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(240),
                    expandFrom = Alignment.Top,
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(
                    animationSpec = tween(200),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(tween(120)),
            ) {
                // 两列 × N 行：每格等宽（weight 平分），文字不够放时换行而不是省略。
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    cells.chunked(2).forEach { rowCells ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowCells.forEach { cell ->
                                StatCell(
                                    label = cell.label,
                                    value = cell.value,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowCells.size == 1) Spacer(Modifier.weight(1f))
                        }
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
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
}

/** 一个格子的内容；未知的指标不进列表，因此不会渲染空占位。 */
private data class StatValue(val label: String, val value: String)

/**
 * 状态格：上面 9sp 的 mono 标签，下面 12sp 的值。
 * 不设固定宽度、不设 maxLines，空间不够时由 Compose 换行，绝不省略。
 */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Spectrum.colors
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
        )
    }
}

/** 已用百分比；总量未知（<= 0）时返回 null，调用方据此省略格子。 */
private fun usedPercent(total: Long, free: Long): Double? {
    if (total <= 0L) return null
    val used = (total - free).toDouble() / total * 100.0
    return used.coerceIn(0.0, 100.0)
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
/** 百分比数值补间：目标为空时保持 0，不做跨格动画。 */
@Composable
private fun rememberAnimatedPercent(target: Double?): Double {
    val animated by animateFloatAsState(
        targetValue = (target ?: 0.0).toFloat(),
        animationSpec = tween(durationMillis = 320),
        label = "stat-percent",
    )
    return if (target == null) 0.0 else animated.toDouble()
}
