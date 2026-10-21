package com.comfypilot.android.transport

import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpResponse
import com.comfypilot.core.transport.HttpTransport
import com.comfypilot.core.transport.httpFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OkHttpHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        try {
            val mediaType = request.contentType?.toMediaTypeOrNull()
            val body = request.body?.toRequestBody(mediaType)
            val builder = Request.Builder().url(request.url)
            if (request.method.equals("GET", ignoreCase = true)) {
                builder.get()
            } else {
                builder.method(request.method.uppercase(), body)
            }
            request.headers.forEach { (key, value) -> builder.header(key, value) }

            client.newBuilder()
                .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
                .execute()
                .use { response -> HttpResponse(status = response.code, body = response.body?.bytes()) }
        } catch (t: Throwable) {
            httpFailure(t.message ?: t::class.simpleName ?: "HTTP 调用失败")
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
