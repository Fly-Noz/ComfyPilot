package com.comfypilot.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactNamesTest {

    @Test
    fun `sanitize keeps readable unicode and strips path and unsafe characters`() {
        assertEquals(
            "测试图_17_.png",
            ArtifactNames.sanitize("dir/测试图:17?.png"),
        )
        assertEquals("artifact", ArtifactNames.sanitize("  ..  "))
        assertEquals("b.png", ArtifactNames.sanitize("a\\b.png"))
    }

    @Test
    fun `extension and mime type are case insensitive`() {
        assertEquals("png", ArtifactNames.extension("A.PNG"))
        assertEquals("image/png", ArtifactNames.mimeType("A.PNG"))
        assertEquals("video/mp4", ArtifactNames.mimeType("clip.mp4"))
        assertEquals("audio/wav", ArtifactNames.mimeType("voice.wav"))
        assertEquals("application/octet-stream", ArtifactNames.mimeType("no-extension"))
    }

    @Test
    fun `media detection covers common ComfyUI outputs`() {
        assertTrue(ArtifactNames.isImage("x.webp"))
        assertTrue(ArtifactNames.isVideo("x.webm"))
        assertTrue(ArtifactNames.isAudio("x.flac"))
        assertFalse(ArtifactNames.isImage("x.txt"))
        assertFalse(ArtifactNames.isVideo("x.png"))
    }

    @Test
    fun `unique name preserves extension and skips existing names`() {
        val existing = mutableSetOf("result.png")
        val first = ArtifactNames.uniqueName("result.png", existing::contains)
        assertEquals("result (1).png", first)
        existing += first
        assertEquals("result (2).png", ArtifactNames.uniqueName("result.png", existing::contains))
    }

    @Test
    fun `unique name handles names without extension`() {
        val existing = mutableSetOf("result")
        assertEquals("result (1)", ArtifactNames.uniqueName("result", existing::contains))
    }
}
