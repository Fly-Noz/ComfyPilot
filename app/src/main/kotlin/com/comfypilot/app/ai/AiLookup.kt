package com.comfypilot.app.ai

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 让模型自己去查提示词规范。
 *
 * 为什么不在代码里枚举模型：ComfyUI 生态的新模型太多，写死规则永远追不上。
 * 做法是"先搜、再读"两步，由模型决定用不用：
 * - lookupPromptGuide：关键词搜索，返回标题/链接/摘要，模型自己挑一个；
 * - fetchDoc：把挑中的页面抓成纯文本回填。
 *
 * 搜索用 cn.bing.com：HuggingFace、DuckDuckGo 在部分网络下不可达，
 * 而搜索页在手机上通常能打开；结果里指向 bing 自身的跳转链接会被跳过。
 * 约束：只走 https、单次超时 15s、返回截断，避免撑爆上下文。
 *
 * 规则要和鸿蒙的 AiLookup.ets 保持一致。
 */
object AiLookup {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val MAX_CHARS = 2600
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

    fun lookupPromptGuide(model: String): String {
        val name = model.trim()
        if (name.isBlank()) return """{"ok":false,"error":"model 为空"}"""
        val query = "$name prompt guide 提示词"
        val html = fetchText("https://cn.bing.com/search?q=" + encode(query))
            ?: return """{"ok":false,"error":"搜索失败：网络不可达，可以先按通用规范写，或让用户给文档链接"}"""
        val results = parseBing(html)
        if (results.isEmpty()) {
            return """{"ok":false,"error":"没搜到结果；可以换更短的关键词重试，或直接用你的知识写"}"""
        }
        return buildJsonObject {
            put("ok", true)
            put("query", query)
            putJsonArray("results") {
                results.take(5).forEach { item ->
                    add(
                        buildJsonObject {
                            put("title", item.title)
                            put("url", item.url)
                            put("snippet", item.snippet.take(200))
                        },
                    )
                }
            }
            put("hint", "挑一个最像官方文档的 url 再用 fetch_doc 读正文")
        }.toString()
    }

    fun fetchDoc(url: String): String {
        val target = url.trim()
        if (!target.startsWith("https://")) return """{"ok":false,"error":"只支持 https 地址"}"""
        val body = fetchText(target) ?: return """{"ok":false,"error":"抓取失败，可能是 404 或站点拒绝"}"""
        val text = stripHtml(body).take(MAX_CHARS)
        return """{"ok":true,"source":${quote(target)},"text":${quote(text)}}"""
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun parseBing(html: String): List<Hit> {
        val pattern = Regex(
            """<li class="b_algo".*?<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>[\s\S]*?<p[^>]*>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(html).mapNotNull { match ->
            val url = decode(match.groupValues[1])
            // bing 的 ck/a 是跳转壳，取不到真实地址，直接跳过。
            if (!url.startsWith("https://") || url.contains("bing.com/ck/")) return@mapNotNull null
            val title = plain(match.groupValues[2])
            val snippet = plain(match.groupValues[3])
            if (title.isBlank()) null else Hit(title, url, snippet)
        }.distinctBy { it.url }.toList()
    }

    private fun fetchText(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }.getOrNull()

    private fun plain(raw: String): String = decode(raw.replace(Regex("<[^>]+>"), " "))
        .replace(Regex("[\\s\\u00a0]+"), " ")
        .trim()

    private fun decode(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&ensp;", " ")
        .replace("&emsp;", " ")
        .replace(Regex("&#\\d+;"), " ")

    /** 抓页面时保留段落：先干掉 script/style，再把标签换成换行。 */
    private fun stripHtml(raw: String): String = decode(
        raw
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " "),
    )
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n\\s*\n+"), "\n\n")
        .trim()

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun quote(text: String): String = JsonPrimitive(text).toString()
}
