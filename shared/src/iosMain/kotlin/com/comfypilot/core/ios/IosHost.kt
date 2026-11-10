package com.comfypilot.core.ios

import com.comfypilot.core.DiagnosticTrace
import com.comfypilot.core.api.ApiCall
import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.ApiPaths
import com.comfypilot.core.api.ImageUpload
import com.comfypilot.core.host.SessionHost
import com.comfypilot.core.host.restoredTasksFromJson
import com.comfypilot.core.host.sessionConfigFromJson
import com.comfypilot.core.host.sessionWorkflowJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * iOS 侧的唯一入口。
 *
 * 只暴露四个方法，Swift 不直接接触 [SessionHost]——它的构造参数带 Kotlin 默认值，
 * 而默认参数不会导出到 Objective-C，硬调会要求把内部调优项全写一遍。
 *
 * 线程约束：所有方法必须在同一个线程（主线程）调用。底层 [SessionHost] 用的
 * PumpDispatcher 是「宿主驱动、单线程、不加锁」的设计，跨线程会直接破坏状态机。
 * Swift 侧因此把所有 URLSession 回调都先切回主线程再 deliver。
 *
 * 生命周期：start() 一次 → 宿主按固定节奏调 pump(nowMs) → 把 IO 结果 deliver 回来。
 * 没有 pump 就没有进展，这正是 iOS 进后台被冻结时想要的行为。
 */
class IosHost(configJson: String, tasksJson: String) {

    private val host: SessionHost = SessionHost(
        config = sessionConfigFromJson(configJson),
        initialTasks = restoredTasksFromJson(tasksJson),
    )

    /** 诊断日志环形缓冲：只留最近 [LOG_LIMIT] 条，退出即丢。 */
    private val logRing = ArrayDeque<String>()
    /** 自启动以来写入的总条数（含已被挤掉的），Swift 靠它算增量。 */
    private var logTotal = 0

    init {
        installDiagnostics()
    }

    private fun installDiagnostics() {
        // 监控帧每秒两条，会把真正有用的事件挤掉；和安卓一样直接过滤。
        DiagnosticTrace.sink = { raw ->
            if (!raw.contains("\"crystools.monitor\"")) {
                if (logRing.size >= LOG_LIMIT) logRing.removeFirst()
                logRing.addLast(raw)
                logTotal += 1
            }
        }
    }

    fun start() {
        host.start()
    }

    fun shutdown() {
        host.shutdown()
    }

    /** 推进到 [nowMs]（Unix 毫秒），返回一帧 JSON：{revision, commands, state, nextDelayMs}。 */
    fun pump(nowMs: Long): String = host.pump(nowMs)

    /** 日志总条数；界面对比上次读到的位置决定要不要取增量。 */
    fun diagnosticsTotal(): Int = logTotal

    /**
     * 取 [consumed] 之后的日志，一行一条。已被环形缓冲挤掉的从最老的开始给。
     *
     * 时间戳交给 Swift 在收到时打：Kotlin/Native 这边取本地时区要拉 posix 结构体，
     * 而宿主本来每 250ms 就来取一次，误差足够小。
     */
    fun diagnosticsSince(consumed: Int): String {
        val oldest = logTotal - logRing.size
        val start = maxOf(consumed, oldest)
        if (start >= logTotal) return ""
        val skip = start - oldest
        return logRing.drop(skip).joinToString("\n")
    }

    fun diagnosticsClear() {
        logRing.clear()
        logTotal = 0
    }

    private companion object {
        const val LOG_LIMIT = 400
    }

    /** 宿主执行完 IO 后把结果送回来；返回 false 表示这条消息无法识别。 */
    fun deliver(raw: String): Boolean = host.deliver(raw)

    /**
     * 按需拉取工作流视图：参数模型、节点开关、图分组、问题清单。
     *
     * 状态帧里只有轻量元数据，几百 KB 的工作流原文和字段模型走这条同步调用，
     * 免得每 250ms 的帧都被一份大工作流拖慢。请求形如：
     * `{"op":"view","overrides":{"3":{"seed":42}}}`，
     * 或 `{"op":"prompt","overrides":{...}}` 拿可提交的 prompt 图。
     *
     * 和鸿蒙的 `cp_session_workflow` 是同一个实现，只是换了调用方式。
     */
    fun workflowView(requestJson: String): String = sessionWorkflowJson(host.state, requestJson)

    /**
     * VHS 多帧动画预览：第一行 `OK|seq|rate|帧数`，之后每行一帧的 base64。
     *
     * 帧数通常几十、单帧几十 KB，只在 seq 变化时取一次；没有动画返回 `ERR\n...`。
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun previewAnimationBase64(): String {
        val animation = host.state.previewAnimation
        if (animation == null || animation.frames.isEmpty()) return "ERR\n没有动画预览"
        return buildString {
            append("OK|")
            append(animation.seq).append('|')
            append(animation.rate).append('|')
            append(animation.frames.size).append('\n')
            animation.frames.forEach { frame ->
                append(Base64.encode(frame)).append('\n')
            }
        }
    }

    /**
     * 组装 `/upload/image` 的 multipart 请求，Swift 拿着 body 自己 POST。
     *
     * 上传不进入状态机：它只为 LoadImage 准备一个服务器文件名，失败也不该影响
     * 已有任务。multipart 的拼法复用共享层的 [ApiPaths]，和安卓发出去的字节一致。
     *
     * 返回四行（base64 与 url 都不含换行，用换行分隔比再包一层 JSON 简单）：
     * ```
     * OK
     * <文件名>
     * <Content-Type>
     * <url>
     * <body 的 base64>
     * ```
     * 失败时返回 `ERR\n<原因>`。
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun uploadRequest(base64: String, filename: String, baseUrl: String, nowMs: Long): String {
        if (baseUrl.isBlank()) return "ERR\n尚未连接"
        val bytes = runCatching { Base64.decode(base64) }.getOrNull()
            ?: return "ERR\n图片解码失败"
        if (bytes.isEmpty()) return "ERR\n图片内容为空"

        val name = ImageUpload.uniqueFilename(filename, nowMs, Random.nextInt())
        val resolved = runCatching {
            ApiPaths.resolve(
                baseUrl,
                ApiCall.UploadImage(filename = name, subfolder = "", type = "input", bytes = bytes),
            )
        }.getOrElse { return "ERR\n" + (it.message ?: "请求构造失败") }

        val body = resolved.body ?: return "ERR\n请求体为空"
        return buildString {
            append("OK\n")
            append(name).append('\n')
            append(resolved.contentType ?: "application/octet-stream").append('\n')
            append(resolved.url).append('\n')
            append(Base64.encode(body))
        }
    }

    /** 解析 `/upload/image` 的响应：成功 `OK\n<引用名>`，失败 `ERR\n<原因>`。 */
    fun uploadResult(status: Int, body: String): String {
        if (status !in 200..299) return "ERR\n服务器返回 $status"
        val parsed = runCatching { ApiCodec.decodeUploadImage(body) }.getOrNull()
            ?: return "ERR\n响应不是有效 JSON"
        val reference = if (parsed.subfolder.isBlank()) parsed.name else parsed.subfolder + "/" + parsed.name
        if (reference.isBlank()) return "ERR\n响应里没有文件名"
        return "OK\n$reference"
    }

    /**
     * 按需取最新一帧采样预览，直接返回 base64（没有帧时返回空串）。
     *
     * 不再套一层 JSON：Swift 侧少一次解析，失败点也就只剩 base64 解码和图片解码两处。
     * 和鸿蒙的 `cp_session_preview` 是同一份数据，只是那边包在 JSON 里方便 C ABI。
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun previewBase64(): String {
        val frame = host.state.preview ?: return ""
        if (frame.image.isEmpty()) return ""
        return Base64.encode(frame.image)
    }
}
