package com.comfypilot.core.event

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.stringOrNull
import kotlinx.serialization.json.JsonObject

/** 一帧采样预览；[promptId] 在旧格式（type=1）里没有，只能挂到当前在跑的任务上。 */
data class ComfyPreview(
    val promptId: String?,
    val nodeId: String?,
    val image: ByteArray,
)

/**
 * ComfyUI 的二进制预览帧格式（见上游 `protocol.py`）：
 * - 4 字节大端事件类型；
 * - type=1：后面直接是图片字节；
 * - type=4：再跟 4 字节大端 metadata 长度 + JSON metadata + 图片字节。
 *
 * 其他类型（未编码预览、文本）不参与画面显示。
 */
object PreviewDecoder {
    private const val PREVIEW_IMAGE = 1
    private const val PREVIEW_IMAGE_WITH_METADATA = 4

    fun decode(frame: ByteArray): ComfyPreview? {
        if (frame.size <= 4) return null
        return when (readInt(frame, 0)) {
            PREVIEW_IMAGE -> ComfyPreview(
                promptId = null,
                nodeId = null,
                image = frame.copyOfRange(4, frame.size),
            ).takeIf { it.image.isNotEmpty() }

            PREVIEW_IMAGE_WITH_METADATA -> decodeWithMetadata(frame)
            else -> null
        }
    }

    private fun decodeWithMetadata(frame: ByteArray): ComfyPreview? {
        if (frame.size <= 8) return null
        val length = readInt(frame, 4)
        if (length < 0) return null
        val start = 8L + length
        if (start >= frame.size) return null
        val metadata = runCatching {
            ApiCodec.json.parseToJsonElement(
                frame.copyOfRange(8, start.toInt()).decodeToString(),
            ) as? JsonObject
        }.getOrNull() ?: return null
        return ComfyPreview(
            promptId = metadata.stringOrNull("prompt_id"),
            nodeId = metadata.stringOrNull("node_id") ?: metadata.stringOrNull("node"),
            image = frame.copyOfRange(start.toInt(), frame.size),
        ).takeIf { it.image.isNotEmpty() }
    }

    private fun readInt(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 0xFF) shl 24) or
            ((frame[offset + 1].toInt() and 0xFF) shl 16) or
            ((frame[offset + 2].toInt() and 0xFF) shl 8) or
            (frame[offset + 3].toInt() and 0xFF)
}
