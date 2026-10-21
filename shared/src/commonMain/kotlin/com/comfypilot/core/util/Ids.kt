package com.comfypilot.core.util

import kotlin.random.Random

object Ids {
    private const val HEX = "0123456789abcdef"

    /** ComfyUI 接受任意客户端自带 id；每次建连重新生成，天然携带代际。 */
    fun clientId(): String = buildString(32) {
        repeat(32) { append(HEX[Random.nextInt(16)]) }
    }
}

/** UTF-8 百分号编码；commonMain 不能用 java.net.URLEncoder。 */
fun percentEncode(value: String): String {
    val bytes = value.encodeToByteArray()
    return buildString(bytes.size) {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%')
                append(HEX_UPPER[c shr 4])
                append(HEX_UPPER[c and 0x0F])
            }
        }
    }
}

private const val HEX_UPPER = "0123456789ABCDEF"
