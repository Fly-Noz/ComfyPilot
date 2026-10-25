package com.comfypilot.core.host

import com.comfypilot.core.api.stringOrNull
import com.comfypilot.core.net.SessionConfig
import com.comfypilot.core.task.DEFAULT_RESTORE_LIMIT
import com.comfypilot.core.task.PromptTask
import kotlinx.serialization.json.JsonArray
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
        // 宿主按服务器地址持久化的会话 id；为空则每次建连随机（旧行为）。
        clientId = obj.stringOrNull("clientId") ?: defaults.clientId,
    )
}

/**
 * 宿主从本地存储里读出来的历史任务：`configJson` 里的 `tasks` 数组，
 * 每项是 [toPersistJson] 写出去的快照。
 *
 * 解析坏了就当没有历史——一条任务都不恢复，也绝不因此让会话起不来。
 * 恢复之后怎么对待这些任务（尤其"上次还在跑"的），由 `restoreTasks` 决定。
 */
fun restoredTasksFromJson(
    raw: String,
    limit: Int = DEFAULT_RESTORE_LIMIT,
): List<PromptTask> {
    val obj = runCatching { bridgeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val tasks = obj["tasks"] as? JsonArray ?: return emptyList()
    return parsePersistedTasks(tasks, limit)
}
