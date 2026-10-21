package com.comfypilot.core.api

import com.comfypilot.core.util.Ids
import com.comfypilot.core.util.percentEncode

/**
 * 所有出站调用的单一枚举。上游增删路由只改这里 + ApiPaths。
 */
sealed interface ApiCall {
    data class SubmitPrompt(val body: PromptRequestBody, val workflowJson: String) : ApiCall

    data class FetchHistory(val promptId: String) : ApiCall

    data object FetchQueue : ApiCall

    data class Interrupt(val promptId: String?) : ApiCall

    /** 列服务器 `user_data/workflows` 目录。 */
    data class ListUserData(val dir: String = "workflows", val recurse: Boolean = true) : ApiCall

    /** 读一个 userdata 文件（工作流原文）。 */
    data class FetchUserData(val path: String) : ApiCall

    /** 只取工作流用到的节点定义：整份 `/object_info` 在装了大量自定义节点的机器上能有几 MB。 */
    data class FetchObjectInfo(val nodeClass: String) : ApiCall

    /** 服务器内存/显存等基础状态；官方路由没有 CPU 利用率。 */
    data object SystemStats : ApiCall

    class UploadImage(
        val filename: String,
        val subfolder: String,
        val type: String,
        val bytes: ByteArray,
    ) : ApiCall
}

data class ResolvedRequest(
    val method: String,
    val url: String,
    val body: ByteArray?,
    val contentType: String?,
)

object ApiPaths {
    const val PROMPT = "/prompt"
    const val QUEUE = "/queue"
    const val HISTORY = "/history"
    const val INTERRUPT = "/interrupt"
    const val VIEW = "/view"
    const val UPLOAD_IMAGE = "/upload/image"
    const val USERDATA = "/userdata"
    const val OBJECT_INFO = "/object_info"
    const val SYSTEM_STATS = "/system_stats"

    fun resolve(baseUrl: String, call: ApiCall, codec: ApiCodec = ApiCodec): ResolvedRequest {
        val base = baseUrl.trimEnd('/')
        return when (call) {
            is ApiCall.SubmitPrompt -> ResolvedRequest(
                method = "POST",
                url = base + PROMPT,
                body = codec.encodePrompt(call.body).encodeToByteArray(),
                contentType = "application/json",
            )

            is ApiCall.FetchHistory -> ResolvedRequest(
                method = "GET",
                url = base + HISTORY + "/" + percentEncode(call.promptId),
                body = null,
                contentType = null,
            )

            ApiCall.FetchQueue -> ResolvedRequest(
                method = "GET",
                url = base + QUEUE,
                body = null,
                contentType = null,
            )

            is ApiCall.Interrupt -> ResolvedRequest(
                method = "POST",
                url = base + INTERRUPT,
                body = (call.promptId?.let { """{"prompt_id":""" + quote(it) + "}" } ?: "{}").encodeToByteArray(),
                contentType = "application/json",
            )

            is ApiCall.ListUserData -> ResolvedRequest(
                method = "GET",
                url = base + USERDATA +
                    "?dir=" + percentEncode(call.dir) +
                    "&recurse=" + call.recurse +
                    // full_info 才有 size/mtime 可排序；旧服务端忽略未知参数。
                    "&full_info=true",
                body = null,
                contentType = null,
            )

            is ApiCall.FetchUserData -> ResolvedRequest(
                method = "GET",
                // 真实 ComfyUI 前端用 encodeURIComponent("workflows/<path>")，
                // 斜杠也编码在同一个路径段里；拆成多段会被服务端当成 404。
                url = base + USERDATA + "/" + percentEncode(userDataPath(call.path)),
                body = null,
                contentType = null,
            )

            is ApiCall.FetchObjectInfo -> ResolvedRequest(
                method = "GET",
                url = base + OBJECT_INFO + "/" + percentEncode(call.nodeClass),
                body = null,
                contentType = null,
            )

            ApiCall.SystemStats -> ResolvedRequest(
                method = "GET",
                url = base + SYSTEM_STATS,
                body = null,
                contentType = null,
            )

            // ComfyUI 的 /upload/image 是 multipart/form-data；boundary 每次随机，避免和文件内容撞上。
            is ApiCall.UploadImage -> {
                val boundary = "----ComfyPilot" + Ids.clientId()
                ResolvedRequest(
                    method = "POST",
                    url = base + UPLOAD_IMAGE,
                    body = multipartBody(boundary, call),
                    contentType = "multipart/form-data; boundary=$boundary",
                )
            }
        }
    }

    /** 图片不跨设备搬运：接续方按 prompt_id 直接向服务端取图。 */
    fun viewUrl(baseUrl: String, request: ViewRequest): String = buildString {
        append(baseUrl.trimEnd('/'))
        append(VIEW)
        append("?filename=").append(percentEncode(request.filename))
        append("&subfolder=").append(percentEncode(request.subfolder))
        append("&type=").append(percentEncode(request.type))
        if (request.preview) append("&preview=true")
    }

    private fun quote(value: String): String = JsonStringWriter.encode(value)

    /** 列表返回的是相对 workflows 的路径；读取时要带上这个前缀。 */
    private fun userDataPath(path: String): String {
        val normalized = path.trimStart('/')
        return if (normalized.startsWith("workflows/")) normalized else "workflows/$normalized"
    }

    /**
     * ComfyUI 只认 `image` 文件字段，`type` 默认 input；`subfolder` 为空时省略。
     * 这里手写 multipart，避免为了一个上传接口把平台 HTTP 客户端搬进 commonMain。
     */
    private fun multipartBody(boundary: String, call: ApiCall.UploadImage): ByteArray {
        val parts = mutableListOf<ByteArray>()

        fun text(value: String) {
            parts += value.encodeToByteArray()
        }

        fun field(name: String, value: String) {
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            text(value)
            text("\r\n")
        }

        field("type", call.type.ifBlank { "input" })
        if (call.subfolder.isNotBlank()) {
            field("subfolder", call.subfolder.replace("\r", " ").replace("\n", " "))
        }
        text("--$boundary\r\n")
        text("Content-Disposition: form-data; name=\"image\"; filename=\"${safeFilename(call.filename)}\"\r\n")
        text("Content-Type: application/octet-stream\r\n\r\n")
        parts += call.bytes
        text("\r\n--$boundary--\r\n")

        val total = parts.sumOf { it.size }
        val body = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(body, offset)
            offset += part.size
        }
        return body
    }

    private fun safeFilename(name: String): String =
        name.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
            .ifBlank { "image.png" }
}

internal object JsonStringWriter {
    fun encode(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
