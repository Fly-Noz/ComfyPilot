@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.comfypilot.ohos

import com.comfypilot.core.host.SessionHost
import com.comfypilot.core.host.restoredTasksFromJson
import com.comfypilot.core.host.sessionConfigFromJson
import com.comfypilot.core.host.sessionWorkflowJson
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
        session = SessionHost(
            config = config,
            initialTasks = restoredTasksFromJson(raw),
        ).also { it.start() }
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

/**
 * 按需获取工作流视图或构建后的 prompt。
 *
 * 参数与返回值都是 JSON；返回值约定与 [cpSessionPump] 相同：先写调用方缓冲区，
 * 返回完整字节数，>= capacity 时调用方扩容并重试（本函数无副作用，可安全重试）。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_session_workflow")
fun cpSessionWorkflow(requestJson: CPointer<ByteVar>?, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val host = session
        ?: return writeUtf8(
            """{"ok":false,"state":"error","message":"会话尚未创建","graphs":[],"nodes":[],"commonFields":[],"problems":[],"warnings":[],"pendingNodeClasses":[],"missingNodeClasses":[],"prompt":null}""",
            buffer,
            capacity,
        )
    val raw = requestJson?.toKString() ?: "{}"
    val result = try {
        sessionWorkflowJson(host.state, raw)
    } catch (error: Throwable) {
        """{"ok":false,"state":"error","message":"${escapeJson(error.message ?: "工作流请求失败")}","graphs":[],"nodes":[],"commonFields":[],"problems":[],"warnings":[],"pendingNodeClasses":[],"missingNodeClasses":[],"prompt":null}"""
    }
    return writeUtf8(result, buffer, capacity)
}

/**
 * 按需取最新一帧采样预览：图片字节以 base64 放在专用响应里，
 * 不进入 500ms 状态帧。返回值约定与 [cpSessionPump] 相同。
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
@CName("cp_session_preview")
fun cpSessionPreview(buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val host = session
        ?: return writeUtf8("""{"ok":false,"message":"会话尚未创建"}""", buffer, capacity)
    val preview = host.state.preview
    val result = if (preview == null || preview.image.isEmpty()) {
        """{"ok":false,"message":"没有预览帧"}"""
    } else {
        buildJsonObject {
            put("ok", true)
            put("promptId", preview.promptId)
            put("nodeId", preview.nodeId)
            put("receivedAtMs", preview.receivedAtMs)
            put("bytes", preview.image.size)
            put("image", Base64.encode(preview.image))
        }.toString()
    }
    return writeUtf8(result, buffer, capacity)
}

/**
 * 取当前这段 VHS 动画预览的全部帧（base64）。帧数几十、单帧几 KB，
 * 按"每来一批取一次"的方式拉取，不放进 500ms 状态帧。
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
@CName("cp_session_preview_animation")
fun cpSessionPreviewAnimation(buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val host = session
        ?: return writeUtf8("""{"ok":false,"message":"会话尚未创建"}""", buffer, capacity)
    val animation = host.state.previewAnimation
    if (animation == null || animation.frames.isEmpty()) {
        return writeUtf8("""{"ok":false,"message":"没有动画预览"}""", buffer, capacity)
    }
    val result = buildJsonObject {
        put("ok", true)
        put("promptId", animation.promptId)
        put("nodeId", animation.nodeId)
        put("rate", animation.rate)
        put("seq", animation.seq)
        put("count", animation.frames.size)
        putJsonArray("frames") {
            animation.frames.forEach { frame -> add(JsonPrimitive(Base64.encode(frame))) }
        }
    }.toString()
    return writeUtf8(result, buffer, capacity)
}

/**
 * AI 工具桥：输入工作流视图 + 编辑状态，输出 digest 或应用结果。
 * 请求/响应都是 JSON，约定与 cp_session_pump 相同。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_ai_digest")
fun cpAiDigest(requestJson: CPointer<ByteVar>?, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val raw = requestJson?.toKString() ?: "{}"
    return writeUtf8(com.comfypilot.core.ai.AiBridge.digest(raw), buffer, capacity)
}

@OptIn(ExperimentalForeignApi::class)
@CName("cp_ai_apply")
fun cpAiApply(requestJson: CPointer<ByteVar>?, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val raw = requestJson?.toKString() ?: "{}"
    return writeUtf8(com.comfypilot.core.ai.AiBridge.apply(raw), buffer, capacity)
}

private fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

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
