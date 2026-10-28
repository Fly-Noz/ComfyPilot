package com.comfypilot.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具执行的宿主能力：两端各自实现，路由器只负责分派与结果格式。
 *
 * 为什么先做这一层：目前两端各写一份 `executeTool`，参数解析、错误文案、
 * JSON 形状全靠人工同步。把「分派 + 结果编码」收进共享层后，漂移面只剩
 * 宿主能力本身。
 *
 * 注意：`propose_edits` 与 `ask_user` 依赖运行时状态（待确认提案、待回答
 * 问题、自动提交开关），本轮仍由两端处理；`execute` 对它们返回 null。
 */
interface AiToolEnv {
    fun submitCurrent(): Boolean
    fun openWorkflow(path: String): Boolean

    /** 当前 AI 工作区里允许切换的工作流。 */
    fun allowedPaths(): List<String>

    fun queryGraphs(): String
    fun queryNodes(argsJson: String): String
    fun queryNode(argsJson: String): String

    /** 最近任务摘要；[waitSeconds] > 0 时等到没有活动任务或超时。 */
    fun taskSummary(waitSeconds: Int): String

    /** 查模型提示词规范（网络在宿主侧），返回 JSON。 */
    fun lookupPromptGuide(model: String): String

    /** 抓文档正文（网络在宿主侧），返回 JSON。 */
    fun fetchDoc(url: String): String
}

/**
 * 无状态工具的分派器。返回值是回填给模型的 JSON 字符串；
 * 不认识或需要运行时状态的工具返回 null，由调用方继续处理。
 */
object AiToolRouter {

    fun execute(name: String, argsJson: String, env: AiToolEnv): String? = when (name) {
        "submit" -> {
            if (env.submitCurrent()) {
                """{"ok":true,"queued":true,"hint":"用 task_status（wait_seconds 例如 30）确认真实结果，别直接说已完成"}"""
            } else {
                """{"ok":false,"error":"工作流还有未填字段或未就绪"}"""
            }
        }

        "task_status" -> {
            val wait = args(argsJson)["wait_seconds"]?.trim()?.toIntOrNull() ?: 0
            env.taskSummary(wait.coerceIn(0, 120))
        }

        "open_workflow" -> {
            val path = args(argsJson)["path"].orEmpty()
            when {
                path.isBlank() -> """{"ok":false,"error":"path 为空"}"""
                path !in env.allowedPaths() -> """{"ok":false,"error":"该工作流不在当前 AI 工作区内"}"""
                else -> """{"ok":${env.openWorkflow(path)}}"""
            }
        }

        "list_graphs" -> env.queryGraphs()
        "list_nodes" -> env.queryNodes(argsJson)
        "get_node" -> env.queryNode(argsJson)

        "lookup_prompt_guide" -> {
            val model = args(argsJson)["model"].orEmpty()
            if (model.isBlank()) """{"ok":false,"error":"model 为空"}"""
            else env.lookupPromptGuide(model)
        }

        "fetch_doc" -> {
            val url = args(argsJson)["url"].orEmpty()
            if (url.isBlank()) """{"ok":false,"error":"url 为空"}"""
            else env.fetchDoc(url)
        }

        else -> null
    }

    /**
     * 解析工具参数：只取顶层标量，够 router 用；复杂结构仍交给原实现。
     * 解析失败返回空表，而不是抛异常——一条坏参数不该打断整个工具循环。
     */
    private fun args(json: String): Map<String, String> {
        val element = runCatching {
            Json.parseToJsonElement(json)
        }.getOrNull() as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (entry in element.entries) {
            val primitive = entry.value as? JsonPrimitive ?: continue
            val text = primitive.contentOrNull ?: continue
            result[entry.key] = text
        }
        return result
    }
}
