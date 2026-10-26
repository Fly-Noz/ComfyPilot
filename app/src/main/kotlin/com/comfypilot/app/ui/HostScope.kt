package com.comfypilot.app.ui

/**
 * 把服务器地址归一成"主机标识"：去掉协议、末尾斜杠和路径，只留 host:port。
 *
 * 同一个 ComfyUI 用局域网 IP 和 Tailscale IP 访问会被当成两个主机——这是刻意的：
 * 客户端无法判断两个地址是否同一台机器，宁可分开存，也不要把参数串过去。
 */
fun hostScopeOf(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withoutScheme = if (trimmed.contains("://")) {
        trimmed.substringAfter("://")
    } else {
        trimmed
    }
    return withoutScheme
        .substringBefore('/')
        .lowercase()
}
