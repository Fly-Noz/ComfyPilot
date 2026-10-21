package com.comfypilot.core.event

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewDecoderTest {

    @Test
    fun `type 1 carries raw image bytes without metadata`() {
        val image = byteArrayOf(1, 2, 3, 4)
        val preview = PreviewDecoder.decode(frame(type = 1, image = image))

        assertEquals(null, preview?.promptId)
        assertEquals(null, preview?.nodeId)
        assertContentEquals(image, preview?.image)
    }

    @Test
    fun `type 4 reads prompt and node from the metadata header`() {
        val image = byteArrayOf(9, 8, 7)
        val preview = PreviewDecoder.decode(
            frame(
                type = 4,
                metadata = """{"prompt_id":"p1","node_id":"3"}""",
                image = image,
            ),
        )

        assertEquals("p1", preview?.promptId)
        assertEquals("3", preview?.nodeId)
        assertContentEquals(image, preview?.image)
    }

    @Test
    fun `unknown or malformed frames are ignored`() {
        assertNull(PreviewDecoder.decode(byteArrayOf()))
        assertNull(PreviewDecoder.decode(byteArrayOf(0, 0, 0, 1)))
        assertNull(PreviewDecoder.decode(frame(type = 9, image = byteArrayOf(1))))

        // metadata 长度超出实际缓冲区
        val broken = intBytes(4) + intBytes(100) + "{}".encodeToByteArray() + byteArrayOf(1)
        assertNull(PreviewDecoder.decode(broken))
    }

    private fun frame(type: Int, metadata: String? = null, image: ByteArray): ByteArray {
        val head = intBytes(type)
        if (metadata == null) return head + image
        val meta = metadata.encodeToByteArray()
        return head + intBytes(meta.size) + meta + image
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
