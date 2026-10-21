package com.comfypilot.android.transport

import com.comfypilot.core.transport.WebSocketHandle
import com.comfypilot.core.transport.WebSocketListener
import com.comfypilot.core.transport.WebSocketTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import java.util.concurrent.TimeUnit
import okhttp3.WebSocketListener as OkHttpListener

/**
 * 传输层心跳用 OkHttp 的 pingInterval；应用层还有独立的进度看门狗。
 * 每次建连都是新的 WebSocket 对象，旧连接只能关掉自己。
 */
class OkHttpWebSocketTransport(
    private val client: OkHttpClient = defaultClient(),
) : WebSocketTransport {
    override suspend fun open(url: String, listener: WebSocketListener): WebSocketHandle =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val socket = client.newWebSocket(
                request,
                object : OkHttpListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        listener.onOpen()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        listener.onText(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        listener.onBinary(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        listener.onClosed(code, reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        listener.onFailure(t.message ?: "WebSocket 失败")
                    }
                },
            )

            object : WebSocketHandle {
                override suspend fun close(code: Int, reason: String) {
                    socket.close(code, reason)
                }
            }
        }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
