package com.comfypilot.core.net

import kotlin.math.pow
import kotlin.math.roundToLong

enum class NetworkTransport { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class NetworkInfo(
    val transport: NetworkTransport,
    val validated: Boolean,
    /** 平台侧的 network handle / netId，仅用于诊断日志。 */
    val handle: String? = null,
) {
    companion object {
        val UNKNOWN = NetworkInfo(NetworkTransport.NONE, validated = false)
    }
}

/**
 * 一次连接尝试的全部身份信息。
 *
 * generation 只在网络类别切换（WiFi <-> 蜂窝）时递增；
 * leaseId 每次建连递增，用来丢弃"上一根 socket"的迟到回调。
 */
data class ConnectionLease(
    val generation: Long,
    val leaseId: Long,
    val clientId: String,
    val baseUrl: String,
    val wsUrl: String,
)

fun wsUrlFor(baseUrl: String, clientId: String): String {
    val normalized = baseUrl.trimEnd('/')
    val ws = when {
        normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
        normalized.startsWith("http://") -> "ws://" + normalized.removePrefix("http://")
        normalized.startsWith("ws://") || normalized.startsWith("wss://") -> normalized
        else -> "ws://$normalized"
    }
    return "$ws/ws?clientId=$clientId"
}

data class SessionConfig(
    val baseUrl: String,
    val connectTimeoutMs: Long = 15_000,
    val httpTimeoutMs: Long = 20_000,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
    val backoffMultiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val reconcileMaxAttempts: Int = 4,
    val reconcileWindowMs: Long = 20_000,
    val reconcileRetryBaseMs: Long = 1_000,
    val progressSilenceMs: Long = DEFAULT_PROGRESS_SILENCE_MS,
    val progressSilenceMaxMs: Long = 600_000,
    val tickIntervalMs: Long = 1_000,
    /** 运行中任务的 /history 兜底间隔；socket 被系统掐断时靠它收尾。 */
    val historyPollMs: Long = 20_000,
) {
    companion object {
        const val DEFAULT_PROGRESS_SILENCE_MS = 120_000L
    }
    /** 指数退避 + 随机抖动；attempt 从 1 开始。 */
    fun backoffMs(attempt: Int, jitter: com.comfypilot.core.transport.JitterSource): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val raw = initialBackoffMs * backoffMultiplier.pow(exponent.toDouble())
        val capped = raw.coerceAtMost(maxBackoffMs.toDouble())
        val factor = 1.0 + jitter.nextSigned() * jitterRatio
        return (capped * factor).roundToLong().coerceAtLeast(0)
    }
}
