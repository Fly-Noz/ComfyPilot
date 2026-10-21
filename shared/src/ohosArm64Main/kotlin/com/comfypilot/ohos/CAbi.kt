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
@CName("cp_version")
fun cpVersion(): Int = 1

/**
 * 把自检 JSON 写入 [buffer]（容量 [capacity] 字节），返回完整 JSON 的字节数。
 * 返回长度 >= capacity 表示缓冲区不够，调用方应扩容后重试。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_self_test_json")
fun cpSelfTestJson(buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val bytes = runSelfTestJson().encodeToByteArray()
    if (buffer == null || capacity <= 0) return bytes.size
    val written = minOf(bytes.size, capacity - 1)
    for (index in 0 until written) {
        buffer[index] = bytes[index]
    }
    buffer[written] = 0
    return bytes.size
}
