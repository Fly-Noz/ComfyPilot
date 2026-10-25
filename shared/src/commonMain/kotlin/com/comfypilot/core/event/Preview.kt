package com.comfypilot.core.event

import com.comfypilot.core.api.ApiCodec
import com.comfypilot.core.api.stringOrNull
import kotlinx.serialization.json.JsonObject

/** 一帧采样预览；[promptId] 在 type=1 帧里没有，只能挂到当前在跑的任务上。 */
data class ComfyPreview(
    val promptId: String?,
    val nodeId: String?,
    val image: ByteArray,
)

/**
 * ComfyUI 的二进制预览帧格式（见上游 `server.py` / `protocol.py`）：
 * - 4 字节大端事件类型；
 * - type=1：再跟 4 字节大端图片格式（1=JPEG，2=PNG），然后才是图片字节；
 * - type=4：再跟 4 字节大端 metadata 长度 + JSON metadata + 图片字节。
 *
 * 早期 mock 的 type=1 没有图片格式头，这里按图片魔数兼容。
 * 其他类型（未编码预览、文本）不参与画面显示。
 */
object PreviewDecoder {
    private const val PREVIEW_IMAGE = 1
    private const val PREVIEW_IMAGE_WITH_METADATA = 4
    private const val IMAGE_FORMAT_JPEG = 1
    private const val IMAGE_FORMAT_PNG = 2

    fun decode(frame: ByteArray): ComfyPreview? {
        if (frame.size <= 4) return null
        return when (readInt(frame, 0)) {
            PREVIEW_IMAGE -> decodeRawImage(frame)
            PREVIEW_IMAGE_WITH_METADATA -> decodeWithMetadata(frame)
            else -> null
        }
    }

    private fun decodeRawImage(frame: ByteArray): ComfyPreview? {
        // ComfyUI 真实帧：type + 4 字节图片格式 + 图片；旧 mock 没有格式头。
        val format = if (frame.size >= 8) readInt(frame, 4) else -1
        val hasFormat = format == IMAGE_FORMAT_JPEG || format == IMAGE_FORMAT_PNG
        var start = if (hasFormat) 8 else 4
        if (hasFormat && !looksLikeImage(frame, start)) {
            // VHS 的动画帧在图片前多了 [帧序号(4) + 节点 id(16)] 的包头，
            // 图片起点不固定；直接扫描 JPEG/PNG 魔数，避免整帧被丢弃。
            val scanned = scanImageStart(frame)
            if (scanned < 0) return null
            start = scanned
        }
        if (start >= frame.size) return null
        return ComfyPreview(
            promptId = null,
            nodeId = null,
            image = frame.copyOfRange(start, frame.size),
        ).takeIf { it.image.isNotEmpty() }
    }

    private fun looksLikeImage(frame: ByteArray, offset: Int): Boolean {
        fun at(index: Int): Int = frame.getOrNull(offset + index)?.toInt()?.and(0xFF) ?: -1
        return (at(0) == 0xFF && at(1) == 0xD8) ||
            (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||
            (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46)
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

    /** 在前 64 字节里找 JPEG/PNG/RIFF 的起点；VHS 包头长度不固定时用它。 */
    private fun scanImageStart(frame: ByteArray): Int {
        val limit = minOf(frame.size - 4, 64)
        for (offset in 0..limit) {
            if (looksLikeImage(frame, offset)) return offset
        }
        return -1
    }
}

/**
 * VideoHelperSuite 的 latent 预览：先发一条 VHS_latentpreview 声明本次动画的
 * 帧数（length）与帧率（rate），随后持续发二进制帧。网页端按 rate 循环播放。
 */
data class PreviewBurst(
    val length: Int,
    val rate: Int,
    val nodeId: String?,
)

data class PreviewAnimation(
    val promptId: String?,
    val nodeId: String?,
    val rate: Int,
    val frames: List<ByteArray>,
)
