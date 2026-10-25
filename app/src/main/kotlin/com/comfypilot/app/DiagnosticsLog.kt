package com.comfypilot.app

import android.util.Log
import com.comfypilot.core.DiagnosticTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 诊断日志：共享层通过 [DiagnosticTrace] 把事件喊出来，这里存最近若干条给诊断页看。
 *
 * 设计取舍：
 * - 只留内存，最多 [MAX_LINES] 条，退出即丢——这条链路的日志是给"当场排查"用的，
 *   不该把用户的 prompt 内容长期写进磁盘；
 * - 同时写一条 Logcat（tag=ComfyPilot），拿到设备时可以不进 App 直接看；
 * - 用 StateFlow 而不是 Compose 状态，写入来自 OkHttp/协程线程，读在 UI 线程。
 */
object DiagnosticsLog {
    private const val MAX_LINES = 400
    private const val TAG = "ComfyPilot"

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** 装上共享层的 sink；重复调用无副作用。 */
    fun install() {
        DiagnosticTrace.sink = { raw -> append(raw) }
    }

    fun append(raw: String) {
        synchronized(lock) {
            buffer.addLast("${timeFormat.format(Date())} $raw")
            while (buffer.size > MAX_LINES) {
                buffer.removeFirst()
            }
            _lines.value = buffer.toList()
        }
        Log.d(TAG, raw)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    /** 供"复制全部"用：按当前顺序拼成多行文本。 */
    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }
}
