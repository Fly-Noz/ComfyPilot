package com.comfypilot.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.comfypilot.app.ui.theme.Spectrum
import com.comfypilot.core.net.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** 网络质量检测：设置页入口 + 弹窗。 */
@Composable
fun NetworkCheckSection(state: SessionState) {
    val c = Spectrum.colors
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("网络质量检测", color = c.text, fontSize = 14.sp)
        Text(
            "测延迟、稳定性、下载速度，并区分『网络慢』和『显卡忙』。",
            color = c.textDim,
            fontSize = 11.sp,
        )
        Text(
            text = "开始检测",
            color = c.accent,
            fontSize = 12.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.accentSoft)
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .clickable { open = true },
        )
    }
    if (open) {
        NetworkCheckDialog(state = state, onDismiss = { open = false })
    }
}

private data class ProbeResult(
    val verdict: String,
    val score: Int,
    val rows: List<Pair<String, String>>,
    val reason: String,
    val suggestion: String,
)

@Composable
private fun NetworkCheckDialog(state: SessionState, onDismiss: () -> Unit) {
    val c = Spectrum.colors
    val context = LocalContext.current
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    var stage by remember { mutableStateOf("准备…") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val url = state.lease?.baseUrl
        if (url.isNullOrBlank()) {
            error = "还没有连接 ComfyUI，先连上再测。"
            return@LaunchedEffect
        }
        try {
            result = withContext(Dispatchers.IO) {
                probe(context, url, state) { stage = it }
            }
        } catch (err: Throwable) {
            error = err.message ?: "检测失败"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.dialog)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网络质量检测", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("关闭", color = c.textMuted, fontSize = 12.sp, modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(6.dp))
            }
            if (error.isNotEmpty()) {
                Text(error, color = c.err, fontSize = 12.5.sp)
                return@Column
            }
            val current = result
            if (current == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = c.accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stage, color = c.textMuted, fontSize = 12.5.sp)
                }
                Text(
                    "预计 8–15 秒：延迟 8 次、下载最近产物、检查长连接。",
                    color = c.textDim,
                    fontSize = 10.5.sp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.verdict, color = c.accent, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${current.score} 分", color = c.textMuted, fontSize = 12.sp)
                }
                Text(current.reason, color = c.text, fontSize = 12.5.sp)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    current.rows.forEach { (label, value) ->
                        Row {
                            Text(label, color = c.textDim, fontSize = 11.sp, modifier = Modifier.width(92.dp))
                            Text(value, color = c.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Text("建议：" + current.suggestion, color = c.textMuted, fontSize = 11.5.sp)
            }
        }
    }
}

private fun probe(
    context: Context,
    baseUrl: String,
    state: SessionState,
    stage: (String) -> Unit,
): ProbeResult {
    val rows = mutableListOf<Pair<String, String>>()
    val base = baseUrl.trimEnd('/')
    val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    stage("检查网络环境…")
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    rows += "网络" to when {
        vpn -> "VPN / 中继"
        wifi -> "Wi-Fi"
        cellular -> "蜂窝网络"
        else -> "未知"
    }

    fun get(path: String): Triple<Boolean, Long, ByteArray?> {
        val started = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
                val bytes = response.body?.bytes()
                Triple(response.isSuccessful, (System.nanoTime() - started) / 1_000_000, bytes)
            }
        } catch (err: Throwable) {
            Triple(false, (System.nanoTime() - started) / 1_000_000, null)
        }
    }

    stage("建立连接…")
    val first = get("/system_stats")
    if (!first.first) {
        return ProbeResult(
            verdict = "差", score = 0,
            rows = rows + ("连接" to "失败（${first.second}ms）"),
            reason = "连不上 ComfyUI。",
            suggestion = "检查地址、端口和网络是否互通；如果走了中继，先直连试试。",
        )
    }
    rows += "建连" to "${first.second}ms"

    stage("测延迟（8 次）…")
    val samples = mutableListOf<Long>()
    var failures = 0
    repeat(8) { index ->
        val (ok, ms, _) = get("/system_stats")
        if (ok) samples += ms else failures += 1
        if (index < 7) Thread.sleep(200)
    }
    val sorted = samples.sorted()
    val p50 = sorted.getOrNull(sorted.size / 2) ?: 0
    val p95 = sorted.getOrNull((sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)) ?: 0
    val jitter = if (sorted.size > 1) abs(sorted.last() - sorted.first()) else 0
    rows += "延迟" to "p50 ${p50}ms · p95 ${p95}ms · 抖动 ${jitter}ms"
    rows += "稳定性" to if (failures == 0) "8/8 成功" else "$failures/8 失败"

    stage("测下载速度…")
    var mbps = 0.0
    var ttfb = 0L
    val history = get("/history?max_items=1")
    val filename = history.third?.decodeToString()?.let { body ->
        Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    }
    if (filename != null) {
        val viewUrl = base + "/view?filename=" + java.net.URLEncoder.encode(filename, "UTF-8") +
            "&type=output&subfolder="
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url(viewUrl).build()).execute().use { response ->
                val stream = response.body?.byteStream()
                if (stream != null && response.isSuccessful) {
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var firstByteAt = 0L
                    while (total < 4L * 1024 * 1024) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        if (firstByteAt == 0L) firstByteAt = System.nanoTime()
                        total += read
                    }
                    ttfb = (firstByteAt - started) / 1_000_000
                    val seconds = max(0.001, (System.nanoTime() - firstByteAt) / 1e9)
                    mbps = total * 8.0 / seconds / 1_000_000
                }
            }
        } catch (_: Throwable) {
        }
        rows += "下载" to if (mbps > 0) {
            String.format("%.1f Mbps · TTFB %dms", mbps, ttfb)
        } else {
            "拿不到产物，跳过"
        }
    } else {
        rows += "下载" to "没有可下载的产物，跳过"
    }

    stage("检查服务端…")
    val queueBody = get("/queue").third?.decodeToString()
    val running = Regex("\"running\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        .find(queueBody ?: "")?.groupValues?.get(1)?.count { it == '{' } ?: 0
    val pending = Regex("\"pending\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        .find(queueBody ?: "")?.groupValues?.get(1)?.count { it == '{' } ?: 0
    rows += "服务端" to "运行 $running · 排队 $pending"

    val lastProgress = if (state.wsOpens > 0) state.wsLastFrameAtMs else 0L
    val silent = lastProgress > 0 && System.currentTimeMillis() - lastProgress > 5 * 60_000
    rows += "长连接" to when {
        lastProgress == 0L -> "本次无采样任务，跳过"
        silent -> "5 分钟没收到帧，疑似静默断链"
        else -> "最后帧 ${(System.currentTimeMillis() - lastProgress) / 1000}s 前"
    }

    var score = 0
    score += when {
        p50 <= 40 -> 35
        p50 <= 120 -> 22
        else -> 5
    }
    score += when (failures) {
        0 -> 35
        1 -> 15
        else -> 0
    }
    score += if (mbps > 0) {
        when {
            mbps >= 8 -> 30
            mbps >= 2 -> 16
            else -> 4
        }
    } else 20

    val verdict = when {
        score >= 85 -> "优"
        score >= 60 -> "良"
        else -> "差"
    }
    val reason = when {
        failures >= 2 -> "连接不稳定：8 次探测失败 $failures 次。"
        p50 > 120 -> "延迟偏高：p50 ${p50}ms。"
        mbps in 0.1..2.0 -> "带宽偏小：约 ${String.format("%.1f", mbps)} Mbps，下载大图会慢。"
        silent -> "HTTP 正常，但长连接 5 分钟没有帧，可能静默断链。"
        else -> "延迟、稳定性、吞吐都在可接受范围。"
    }
    val suggestion = when {
        vpn -> "当前走 VPN/中继，先测一次直连对比；中继 RTT 高时延迟会明显变差。"
        failures >= 2 -> "检查 Wi-Fi 信号或换 5G；路由器/中继不稳时优先有线或直连。"
        p50 > 120 -> "延迟高多与中继或跨网有关；同一局域网内应低于 40ms。"
        mbps in 0.1..2.0 -> "带宽小：图片场景 2 Mbps 可用，视频/大图建议换 5G 或局域网。"
        running > 0 -> "服务端有任务在跑，慢可能是显卡忙而不是网络。"
        else -> "网络正常；若仍觉得慢，多半是服务端在算图。"
    }
    return ProbeResult(verdict, score, rows, reason, suggestion)
}
