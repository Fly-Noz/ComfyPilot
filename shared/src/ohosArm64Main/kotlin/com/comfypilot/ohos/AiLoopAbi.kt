@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.comfypilot.ohos

import com.comfypilot.core.ai.sharedAiLoop
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.native.CName

/**
 * AI 循环的 C ABI：鸿蒙只做两件事——按 [cpAiStep] 拿下一步、把执行结果用 [cpAiSend] 送回来。
 *
 * 与 `cp_session_pump` 同一套缓冲区约定：调用方给缓冲区，Kotlin 写 UTF-8 + 结尾 0，
 * 返回完整字节数；返回值 >= capacity 表示要扩容后重试。
 *
 * 重试的幂等性由协议层负责（它按请求字符串缓存上一次的动作）：动作 JSON 可能超过
 * 16KB（请求体带摘要和工具结果），NAPI 扩容后会再调一次同一个请求，那次不能把
 * `acceptReply` / `acceptToolResult` 再算一遍。
 *
 * 协议本身在 `core/ai/AiLoopProtocol.kt`——放在 commonMain 是为了能在 JVM 上按 ArkTS
 * 的真实报文测；这里只负责跨语言边界：读字符串、写缓冲区。
 */
@OptIn(ExperimentalForeignApi::class)
@CName("cp_ai_step")
fun cpAiStep(requestJson: CPointer<ByteVar>?, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val raw = requestJson?.toKString() ?: "{}"
    return writeUtf8(sharedAiLoop.step(raw), buffer, capacity)
}

@OptIn(ExperimentalForeignApi::class)
@CName("cp_ai_send")
fun cpAiSend(requestJson: CPointer<ByteVar>?, buffer: CPointer<ByteVar>?, capacity: Int): Int {
    val raw = requestJson?.toKString() ?: "{}"
    return writeUtf8(sharedAiLoop.send(raw), buffer, capacity)
}
