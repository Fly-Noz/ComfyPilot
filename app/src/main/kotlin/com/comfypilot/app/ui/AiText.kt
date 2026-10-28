package com.comfypilot.app.ui

/**
 * 把模型回复收敛成适合聊天气泡的纯文本。
 *
 * 为什么需要：模型走的是 Markdown（标题、加粗、代码围栏、表格），
 * 而气泡只是一个普通 Text，不处理就会把 `**`、`###`、`|` 这些符号原样显示出来。
 *
 * 取舍：不做完整渲染（富文本要 AnnotatedString 拼装，收益不大），
 * 只保证"读起来像正常句子"——去掉标记、保留结构与缩进。
 * 规则要和鸿蒙的 AiText.ets 保持一致，别只改一边。
 */
private val RULE_RULE_LINE = Regex("^[-*_]{3,}$")
private val RULE_TABLE_DIVIDER = Regex("^[\\s|:-]+$")
private val RULE_QUOTE = Regex("^\\s{0,3}>\\s?")
private val RULE_HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
private val RULE_BULLET = Regex("^\\s{0,3}[-*+]\\s+")
private val RULE_IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
private val RULE_LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
private val RULE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")
private val RULE_BOLD_ALT = Regex("__([^_]+)__")
private val RULE_STRIKE = Regex("~~([^~]+)~~")
private val RULE_CODE = Regex("`([^`]+)`")
private val RULE_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val RULE_BLANK = Regex("\n{3,}")

fun aiPlainText(raw: String): String {
    val out = StringBuilder()
    var inCode = false
    raw.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { original ->
        val line = original.trimEnd()
        val trimmed = line.trim()
        // 代码围栏本身不显示，里面的内容保持原样（缩进有意义，不参与下面的替换）。
        if (trimmed.startsWith("```")) {
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            out.appendLine(line)
            return@forEach
        }
        if (RULE_RULE_LINE.matches(trimmed)) return@forEach
        // 表格：保留单元格内容，去掉竖线与分隔行
        if (trimmed.startsWith("|")) {
            if (RULE_TABLE_DIVIDER.matches(trimmed)) return@forEach
            val cells = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            out.appendLine(cells.joinToString("  "))
            return@forEach
        }
        var body = line.replace(RULE_QUOTE, "").replace(RULE_HEADING, "")
        // 无序列表统一成中点，比 - 更好认；有序列表原样保留
        body = body.replace(RULE_BULLET, "· ")
        out.appendLine(body)
    }
    return out.toString()
        .replace(RULE_IMAGE, "${'$'}1")
        .replace(RULE_LINK, "${'$'}1")
        .replace(RULE_BOLD, "${'$'}1")
        .replace(RULE_BOLD_ALT, "${'$'}1")
        .replace(RULE_STRIKE, "${'$'}1")
        .replace(RULE_CODE, "${'$'}1")
        .replace(RULE_BR, "\n")
        .replace(RULE_BLANK, "\n\n")
        .trim()
}

/** 工具结果是内部协议，不该出现在聊天气泡里。 */
fun isAiToolResult(content: String): Boolean = content.startsWith("（工具结果）")
