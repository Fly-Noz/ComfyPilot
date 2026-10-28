@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.comfypilot.ohos

import com.comfypilot.core.selftest.runSelfTestJson
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlin.native.CName

/**
 * ArkTS 侧唯一入口：只暴露稳定的 C ABI，不把 Kotlin 对象/异常泄漏给 NAPI 层。
 *
 * 字符串约定：调用方提供缓冲区，Kotlin 写入 UTF-8 + 结尾 0，返回完整字节数。
 * 这样跨语言边界没有内存所有权问题，也不需要 free 函数。
 */

/**
 * ABI 版本。2 起新增会话级接口（cp_session_*）；3 起新增工作流按需视图；
 * 4 起新增采样预览按需接口（cp_session_preview）；5 起新增 AI 循环（cp_ai_step / cp_ai_send）。
 */
@CName("cp_version")
fun cpVersion(): Int = 5

/**
 * 把自检 JSON 写入 [buffer]（容量 [capacity] 字节），返回完整 JSON 的字节数。
 * 返回长度 >= capacity 表示缓冲区不够，调用方应扩容后重试。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_self_test_json")
fun cpSelfTestJson(buffer: CPointer<ByteVar>?, capacity: Int): Int =
    writeUtf8(runSelfTestJson(), buffer, capacity)

/**
 * 把 [text] 以 UTF-8 + NUL 写进调用方缓冲区，返回完整字节数（不含结尾 0）。
 * 缓冲区不够时不截断内容，只写能写的部分并返回真实长度。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeUtf8(text: String, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val bytes = text.encodeToByteArray()
    if (buffer == null || capacity <= 0) return bytes.size
    val written = minOf(bytes.size, capacity - 1)
    for (index in 0 until written) {
        buffer[index] = bytes[index]
    }
    buffer[written] = 0
    return bytes.size
}
