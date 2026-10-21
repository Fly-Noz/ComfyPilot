@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.comfypilot.ohos

import com.comfypilot.core.host.SessionHost
import com.comfypilot.core.host.sessionConfigFromJson
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.native.CName

/**
 * 会话级 C ABI：ArkTS 只做两件事——按时 [cpSessionPump]，把命令结果 [cpSessionSend] 送回来。
 *
 * 与 `cp_self_test_json` 一样，字符串用"调用方给缓冲区、Kotlin 写入并返回长度"的约定，
 * 跨语言边界没有内存所有权。
 *
 * 一个进程只有一个会话（一个页面一个连接），所以这里是单例；重复 create 会先关掉旧的。
 *
 * 所有入口都兜住 Kotlin 异常：异常穿过 NAPI 边界会直接 abort 进程，
 * 与其整个应用闪退，不如把错误写进帧里让上层看到。
 */
private var session: SessionHost? = null
private var cachedFrame: String? = null
private var cachedFrameAtMs: Long = -1

/** 返回 1 表示会话已创建；配置无法解析时返回 0。 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_session_create")
fun cpSessionCreate(configJson: CPointer<ByteVar>?): Int {
    val raw = configJson?.toKString() ?: return 0
    val config = runCatching { sessionConfigFromJson(raw) }.getOrNull() ?: return 0
    return try {
        session?.shutdown()
        cachedFrame = null
        cachedFrameAtMs = -1
        session = SessionHost(config).also { it.start() }
        1
    } catch (error: Throwable) {
        session = null
        0
    }
}

/**
 * 推进到 [nowMs] 并返回一帧 JSON。返回值是完整帧的字节数，
 * >= [capacity] 表示缓冲区不够，调用方应扩容后**用同一个 nowMs** 重试，
 * 此时会命中缓存，不会重复推进状态机。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_session_pump")
fun cpSessionPump(nowMs: Long, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val host = session ?: return 0
    val frame = try {
        if (cachedFrame != null && cachedFrameAtMs == nowMs) {
            cachedFrame!!
        } else {
            host.pump(nowMs).also {
                cachedFrame = it
                cachedFrameAtMs = nowMs
            }
        }
    } catch (error: Throwable) {
        errorFrame(error.message ?: "pump failed")
    }
    return writeUtf8(frame, buffer, capacity)
}

/** 送回一条宿主消息。返回 1 表示被接受，0 表示无法识别或被忽略。 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_session_send")
fun cpSessionSend(json: CPointer<ByteVar>?): Int {
    val host = session ?: return 0
    val raw = json?.toKString() ?: return 0
    // 新的输入可能改变状态，必须让下一次 pump 重新计算。
    cachedFrame = null
    cachedFrameAtMs = -1
    return try {
        if (host.deliver(raw)) 1 else 0
    } catch (error: Throwable) {
        0
    }
}

@CName("cp_session_destroy")
fun cpSessionDestroy() {
    runCatching { session?.shutdown() }
    session = null
    cachedFrame = null
    cachedFrameAtMs = -1
}

/** 结构化错误帧：宿主能照常解析，只是 state 为空、带一条 error。 */
private fun errorFrame(message: String): String = buildJsonObject {
    put("revision", -1)
    putJsonArray("commands") {}
    put("state", JsonNull)
    put("nextDelayMs", 250L)
    put("error", message)
}.toString()
