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
