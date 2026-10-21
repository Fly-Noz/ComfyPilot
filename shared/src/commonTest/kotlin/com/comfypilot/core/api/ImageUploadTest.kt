package com.comfypilot.core.api

import com.comfypilot.core.transport.HttpRequest
import com.comfypilot.core.transport.HttpResponse
import com.comfypilot.core.transport.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ImageUploadTest {

    @Test
    fun `multipart carries the file and the fields`() {
        val bytes = "PNG-BYTES".encodeToByteArray()
        val resolved = ApiPaths.resolve(
            "http://host:8188/",
            ApiCall.UploadImage(filename = "my photo.png", subfolder = "sub", type = "input", bytes = bytes),
        )

        assertEquals("POST", resolved.method)
        assertEquals("http://host:8188/upload/image", resolved.url)
        val contentType = assertNotNull(resolved.contentType)
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val boundary = contentType.substringAfter("boundary=")
        val body = assertNotNull(resolved.body).decodeToString()

        assertTrue(body.contains("Content-Disposition: form-data; name=\"type\"\r\n\r\ninput"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"subfolder\"\r\n\r\nsub"))
        assertTrue(body.contains("filename=\"my photo.png\""))
        assertTrue(body.contains("PNG-BYTES"))
        assertTrue(body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun `file name is reduced to its base name`() {
        val resolved = ApiPaths.resolve(
            "http://host",
            ApiCall.UploadImage(filename = "..\\evil\\dir/a.png", subfolder = "", type = "input", bytes = byteArrayOf(1)),
        )
        val body = assertNotNull(resolved.body).decodeToString()
        assertTrue(body.contains("filename=\"a.png\""))
        assertTrue(!body.contains("evil"))
    }

    @Test
    fun `upload parses the server reference`() = runTest {
        val transport = FakeTransport(
            HttpResponse(status = 200, body = """{"name":"up.png","subfolder":"inputs","type":"input"}""".encodeToByteArray()),
        )

        val result = ImageUpload.upload(
            http = transport,
            baseUrl = "http://host",
            filename = "a.png",
            bytes = "x".encodeToByteArray(),
        )

        assertEquals(ImageUploadResult.Success("up.png", "inputs", "input"), result)
        assertEquals("inputs/up.png", (result as ImageUploadResult.Success).reference)
        assertNotNull(transport.request)
        assertTrue(transport.request!!.contentType!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `non-2xx and transport failure are surfaced`() = runTest {
        val rejected = ImageUpload.upload(
            http = FakeTransport(HttpResponse(status = 500, body = "oops".encodeToByteArray())),
            baseUrl = "http://host",
            filename = "a.png",
            bytes = byteArrayOf(1),
        )
        assertEquals(ImageUploadResult.Failure("服务器返回 500"), rejected)

        val failed = ImageUpload.upload(
            http = FakeTransport(HttpResponse(status = 0, failure = "timeout")),
            baseUrl = "http://host",
            filename = "a.png",
            bytes = byteArrayOf(1),
        )
        assertEquals(ImageUploadResult.Failure("timeout"), failed)
    }

    @Test
    fun `empty image is rejected before any request`() = runTest {
        val transport = FakeTransport(HttpResponse(status = 200))
        val result = ImageUpload.upload(transport, "http://host", "a.png", byteArrayOf())
        assertEquals(ImageUploadResult.Failure("图片内容为空"), result)
        assertEquals(null, transport.request)
    }
}

private class FakeTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null

    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}
