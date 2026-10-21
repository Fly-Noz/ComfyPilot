package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionConfig
import kotlinx.serialization.json.JsonObject

/**
 * 宿主传来的会话配置。只认几个真正需要现场调整的参数，其余沿用 [SessionConfig] 的默认值，
 * 免得两端各维护一份超时表。
 *
 * 解析失败（JSON 坏了、baseUrl 为空）一律回落到默认值：连不上比启动就崩好排查。
 */
fun sessionConfigFromJson(
    raw: String,
    fallbackBaseUrl: String = "http://127.0.0.1:8188",
): SessionConfig {
    val obj = runCatching { bridgeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
    val baseUrl = obj.stringOrNull("baseUrl")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?: fallbackBaseUrl
    val defaults = SessionConfig(baseUrl = baseUrl)
    return defaults.copy(
        connectTimeoutMs = obj.longOrNull("connectTimeoutMs") ?: defaults.connectTimeoutMs,
        httpTimeoutMs = obj.longOrNull("httpTimeoutMs") ?: defaults.httpTimeoutMs,
        initialBackoffMs = obj.longOrNull("initialBackoffMs") ?: defaults.initialBackoffMs,
        maxBackoffMs = obj.longOrNull("maxBackoffMs") ?: defaults.maxBackoffMs,
        reconcileMaxAttempts = obj.longOrNull("reconcileMaxAttempts")
            ?.toInt()
            ?: defaults.reconcileMaxAttempts,
        reconcileWindowMs = obj.longOrNull("reconcileWindowMs") ?: defaults.reconcileWindowMs,
        progressSilenceMs = obj.longOrNull("progressSilenceMs") ?: defaults.progressSilenceMs,
        progressSilenceMaxMs = obj.longOrNull("progressSilenceMaxMs") ?: defaults.progressSilenceMaxMs,
        tickIntervalMs = obj.longOrNull("tickIntervalMs") ?: defaults.tickIntervalMs,
    )
}
