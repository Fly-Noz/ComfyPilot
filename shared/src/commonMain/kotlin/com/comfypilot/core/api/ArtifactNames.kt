package com.comfypilot.core.api

/**
 * 产物文件名的纯规则，和 Android 的 MediaStore / 下载实现解耦。
 *
 * ComfyUI 的 filename 来自服务端，可能包含子目录、控制字符或平台不接受的字符；
 * 下载前必须收敛成单个安全的显示名，同时保留扩展名供 MIME 判断。
 */
object ArtifactNames {

    private const val MAX_LENGTH = 180
    private val unsafe = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(filename: String): String {
        val leaf = filename.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(leaf.length) {
            for (ch in leaf) {
                append(if (ch.isISOControl() || ch in unsafe) '_' else ch)
            }
        }.trim().trim('.')
        val fallback = cleaned.ifBlank { "artifact" }
        if (fallback.length <= MAX_LENGTH) return fallback

        val ext = extension(fallback)
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        return fallback.take((MAX_LENGTH - suffix.length).coerceAtLeast(1)) + suffix
    }

    fun extension(filename: String): String =
        filename.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }

    fun mimeType(filename: String): String = when (extension(filename)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun isImage(filename: String): Boolean = extension(filename) in setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp",
    )

    fun isVideo(filename: String): Boolean = extension(filename) in setOf(
        "mp4", "webm", "mov", "mkv", "avi",
    )

    fun isAudio(filename: String): Boolean = extension(filename) in setOf(
        "mp3", "wav", "flac", "ogg", "m4a",
    )

    /** 若 [original] 已存在，则依次尝试 `name (1).ext`、`name (2).ext`…… */
    fun uniqueName(original: String, exists: (String) -> Boolean): String {
        if (!exists(original)) return original
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$ext"
            if (!exists(candidate)) return candidate
            index++
        }
    }
}
