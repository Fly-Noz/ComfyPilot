package com.comfypilot.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManifestTest {

    private val manifest = """
        {
          "versionCode": 4,
          "versionName": "0.2.2",
          "publishedAt": "2026-11-01",
          "notes": "修复：代理断开后不重连",
          "android": {
            "github": "https://github.com/Fly-Noz/ComfyPilot/releases/latest",
            "gitee": "https://gitee.com/Fly-Noz/ComfyPilot/releases"
          },
          "harmony": {
            "github": "https://github.com/Fly-Noz/ComfyPilot/releases",
            "gitee": "https://gitee.com/Fly-Noz/ComfyPilot/releases"
          }
        }
    """.trimIndent()

    @Test
    fun `parses the fields used by the dialog`() {
        val parsed = assertNotNull(UpdateCheck.parse(manifest))
        assertEquals(4, parsed.versionCode)
        assertEquals("0.2.2", parsed.versionName)
        assertEquals("2026-11-01", parsed.publishedAt)
        assertTrue(parsed.notes!!.contains("代理断开"))
    }

    @Test
    fun `an older install sees the new version and gets its own platform links`() {
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_ANDROID, currentVersionCode = 3)
        val available = assertIs<UpdateVerdict.Available>(verdict)
        assertEquals("0.2.2", available.manifest.versionName)
        assertEquals("https://gitee.com/Fly-Noz/ComfyPilot/releases", available.links.gitee)
        assertTrue(available.links.github!!.endsWith("/latest"))
    }

    @Test
    fun `harmony gets the harmony links, not android ones`() {
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_HARMONY, 3)
        val available = assertIs<UpdateVerdict.Available>(verdict)
        assertFalse(available.links.github!!.endsWith("/latest"), "鸿蒙不应拿 Android 的链接")
    }

    @Test
    fun `a platform without its own block falls back to the top level links`() {
        val flat = """
            {"versionCode": 9, "versionName": "0.3.0",
             "github": "https://github.com/x", "gitee": "https://gitee.com/x"}
        """.trimIndent()
        val verdict = UpdateCheck.evaluate(flat, UpdateCheck.PLATFORM_ANDROID, 3)
        val available = assertIs<UpdateVerdict.Available>(verdict)
        assertEquals("https://gitee.com/x", available.links.gitee)
    }

    @Test
    fun `same version is not an update`() {
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_ANDROID, 4)
        assertIs<UpdateVerdict.UpToDate>(verdict)
    }

    @Test
    fun `a local build newer than the manifest stays quiet`() {
        // 装了内测包的人不该被提示「有新版」。
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_ANDROID, 7)
        assertIs<UpdateVerdict.UpToDate>(verdict)
    }

    @Test
    fun `version names are never compared as strings`() {
        // 0.2.10 的 versionCode 是 10，比 0.2.9 的 9 大；按字符串比会判反。
        val raw = """{"versionCode": 10, "versionName": "0.2.10"}"""
        assertIs<UpdateVerdict.Available>(UpdateCheck.evaluate(raw, UpdateCheck.PLATFORM_ANDROID, 9))
    }

    @Test
    fun `broken or incomplete manifests are unavailable instead of throwing`() {
        assertNull(UpdateCheck.parse(""))
        assertNull(UpdateCheck.parse("not json"))
        assertNull(UpdateCheck.parse("[]"))
        assertNull(UpdateCheck.parse("""{"versionName":"0.2.2"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode":0,"versionName":"x"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode":4}"""))
        assertIs<UpdateVerdict.Unavailable>(UpdateCheck.evaluate("{}", UpdateCheck.PLATFORM_ANDROID, 3))
    }

    @Test
    fun `dialog is suppressed only for versions the user already skipped`() {
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_ANDROID, 3)
        assertTrue(UpdateCheck.shouldPrompt(verdict, skippedVersionCode = 0))
        assertTrue(UpdateCheck.shouldPrompt(verdict, skippedVersionCode = 3))
        // 用户点过「稍后」的正是 4，就不再弹。
        assertFalse(UpdateCheck.shouldPrompt(verdict, skippedVersionCode = 4))
    }

    @Test
    fun `up to date never prompts`() {
        val verdict = UpdateCheck.evaluate(manifest, UpdateCheck.PLATFORM_ANDROID, 4)
        assertFalse(UpdateCheck.shouldPrompt(verdict, skippedVersionCode = 0))
    }
}
