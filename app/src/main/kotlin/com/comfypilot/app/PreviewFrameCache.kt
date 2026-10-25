package com.comfypilot.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.comfypilot.core.net.PreviewFrame

/**
 * 采样预览帧的环形缓存。
 *
 * 为什么需要：ComfyUI 对视频模型也是"每步推一张 JPEG"，网页端那只看似连续的画面，
 * 是前端把收到的历史帧循环播放出来的。只显示最新一帧，就会看起来一直是静态图。
 *
 * 这里按任务缓存最近若干帧（解码后的 Bitmap），界面按顺序轮播即可复现同样的观感。
 */
object PreviewFrameCache {
    private const val MAX_FRAMES = 24

    private val lock = Any()
    private val frames = mutableMapOf<String, ArrayDeque<Bitmap>>()
    private var lastKey: String = ""
    private var lastStamp: Long = -1

    /** 由 SessionHost 的状态收集器调用；同一帧重复回调会被忽略。 */
    fun record(frame: PreviewFrame?) {
        if (frame == null) return
        val key = frame.promptId ?: "unknown"
        if (key == lastKey && frame.receivedAtMs == lastStamp) return
        lastKey = key
        lastStamp = frame.receivedAtMs
        val bitmap = BitmapFactory.decodeByteArray(frame.image, 0, frame.image.size) ?: return
        synchronized(lock) {
            val queue = frames.getOrPut(key) { ArrayDeque() }
            queue.addLast(bitmap)
            while (queue.size > MAX_FRAMES) {
                queue.removeFirst()
            }
        }
    }

    fun frames(promptId: String?): List<Bitmap> {
        if (promptId == null) return emptyList()
        synchronized(lock) {
            return frames[promptId]?.toList().orEmpty()
        }
    }

    fun clear(promptId: String?) {
        if (promptId == null) return
        synchronized(lock) {
            frames.remove(promptId)
        }
        if (promptId == lastKey) {
            lastKey = ""
            lastStamp = -1
        }
    }
}
