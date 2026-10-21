package com.comfypilot.core.api

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
                url = base + USERDATA + "/" + encodePath(call.path),
                body = null,
                contentType = null,
            )

            is ApiCall.FetchObjectInfo -> ResolvedRequest(
                method = "GET",
                url = base + OBJECT_INFO + "/" + percentEncode(call.nodeClass),
                body = null,
                contentType = null,
            )

            // multipart 需要 boundary 与字段拼装，阶段一不启用；显式失败好过发出一个畸形请求。
            is ApiCall.UploadImage -> throw UnsupportedOperationException(
                "/upload/image 尚未实现（阶段二接入）",
            )
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

    /** 路径按段编码：`a b/c.json` -> `a%20b/c.json`，斜杠要保留。 */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { percentEncode(it) }
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
