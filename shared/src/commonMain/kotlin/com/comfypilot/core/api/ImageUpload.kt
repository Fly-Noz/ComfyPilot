package com.comfypilot.core.api

import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpTransport

sealed interface ImageUploadResult {
    data class Success(
        val name: String,
        val subfolder: String,
        val type: String,
    ) : ImageUploadResult {
        /** LoadImage 的 image 控件要的是服务器上的引用；有子目录时带上子目录。 */
        val reference: String get() = if (subfolder.isBlank()) name else "$subfolder/$name"
    }

    data class Failure(val message: String) : ImageUploadResult
}

/**
 * 相册图片上传。上传本身不进入任务状态机：它只是为 LoadImage 准备一个服务器端文件名，
 * 失败也不会影响已有任务；调用方拿到 [ImageUploadResult.Success.reference] 后再覆盖对应控件值。
 */
object ImageUpload {

    /**
     * 上传前换一个唯一文件名。
     *
     * ComfyUI 的 `/upload/image` 默认不覆盖同名文件：可能直接报错，也可能被服务器自动改名，
     * 于是模型刚写进节点的名字和服务器上的实际文件对不上。带时间戳 + 随机后缀最稳；
     * 扩展名要保留（LoadImage 靠它识别格式），其余非法字符换成下划线。
     */
    fun uniqueFilename(filename: String, nowMs: Long, salt: Int): String {
        val trimmed = filename.trim()
        val dot = trimmed.lastIndexOf('.')
        val rawBase = if (dot > 0) trimmed.substring(0, dot) else trimmed
        val ext = if (dot > 0) trimmed.substring(dot) else ""
        val base = rawBase.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "image" }
        val suffix = nowMs.toString(36) + "-" + (salt and 0x7fffffff).toString(36)
        return "$base-$suffix$ext"
    }
    suspend fun upload(
        http: HttpTransport,
        baseUrl: String,
        filename: String,
        bytes: ByteArray,
        subfolder: String = "",
        type: String = "input",
        timeoutMs: Long = 60_000,
    ): ImageUploadResult {
        if (bytes.isEmpty()) return ImageUploadResult.Failure("图片内容为空")

        val resolved = runCatching {
            ApiPaths.resolve(
                baseUrl,
                ApiCall.UploadImage(filename = filename, subfolder = subfolder, type = type, bytes = bytes),
            )
        }.getOrElse { error ->
            return ImageUploadResult.Failure(error.message ?: "上传请求构造失败")
        }

        val response = try {
            http.execute(
                HttpRequest(
                    method = resolved.method,
                    url = resolved.url,
                    body = resolved.body,
                    contentType = resolved.contentType,
                    timeoutMs = timeoutMs,
                ),
            )
        } catch (t: Throwable) {
            return ImageUploadResult.Failure(t.message ?: "上传失败")
        }

        if (response.failure != null) return ImageUploadResult.Failure(response.failure)
        if (response.status !in 200..299) {
            return ImageUploadResult.Failure("服务器返回 ${response.status}")
        }

        val parsed = runCatching { ApiCodec.decodeUploadImage(response.text() ?: "") }
            .getOrElse { return ImageUploadResult.Failure("无法解析上传响应") }
        if (parsed.name.isBlank()) return ImageUploadResult.Failure("服务器没有返回文件名")
        return ImageUploadResult.Success(parsed.name, parsed.subfolder, parsed.type)
    }
}
